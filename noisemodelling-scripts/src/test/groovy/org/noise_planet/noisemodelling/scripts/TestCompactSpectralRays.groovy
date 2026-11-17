package org.noise_planet.noisemodelling.scripts

import groovy.json.JsonSlurper
import groovy.sql.Sql
import org.h2gis.api.EmptyProgressVisitor
import org.h2gis.functions.factory.H2GISFunctions
import org.junit.jupiter.api.Test
import org.noise_planet.noisemodelling.jdbc.NoiseMapByReceiverMaker
import org.noise_planet.noisemodelling.jdbc.NoiseMapDatabaseParameters
import org.noise_planet.noisemodelling.scripts.NoiseModelling.CompactSpectralRayProcessing
import org.noise_planet.noisemodelling.scripts.NoiseModelling.Noise_level_from_source
import java.sql.DriverManager

class TestCompactSpectralRays {
    private static final List<Integer> ROAD_BANDS = [63,125,250,500,1000,2000,4000,8000]
    private static final List<Integer> RAIL_BANDS = [100,125,160,200,250,315,400,500,630,800,
                                                   1000,1250,1600,2000,2500,3150,4000,5000,6300,8000]

    @Test
    void testRoadSpectra() { compareWriters(ROAD_BANDS, true) }

    @Test
    void testRailwaySpectra() { compareWriters(RAIL_BANDS, false) }

    @Test
    void testScriptSelectsCompactWriter() {
        [ROAD_BANDS, RAIL_BANDS].each { bands ->
            withFixture(bands, bands == ROAD_BANDS) { connection, sql ->
                new Noise_level_from_source().exec(connection, [tableBuilding: 'BUILDINGS', tableSources: 'SOURCES',
                        tableReceivers: 'RECEIVERS', confRaysName: 'RAYS', confExportSpectralRays: true,
                        confReflOrder: 0, confMaxSrcDist: 300, confSourcesZIsAltitude: true,
                        confReceiversZIsAltitude: true])
                def rays = sql.rows('SELECT PATH FROM RAYS')
                assert !rays.empty
                rays.each { row ->
                    def path = new JsonSlurper().parseText(row.PATH.toString())
                    assert path.keySet() == ['aGlobal'] as Set
                    assert path.aGlobal.size() == bands.size()
                }
            }
        }
    }

    @Test
    void testLegacyExportHasNoPath() {
        withFixture(ROAD_BANDS, true) { connection, sql ->
            new Noise_level_from_source().exec(connection, [tableBuilding: 'BUILDINGS', tableSources: 'SOURCES',
                    tableReceivers: 'RECEIVERS', confRaysName: 'RAYS', confReflOrder: 0])
            assert !sql.rows('SELECT * FROM RAYS').empty
            assert !sql.rows('SELECT * FROM RAYS')[0].containsKey('PATH')
        }
    }

    private static void compareWriters(List<Integer> bands, boolean road) {
        withFixture(bands, road) { connection, sql ->
            def run = { boolean compact ->
                sql.execute('DROP TABLE IF EXISTS RECEIVERS_LEVEL')
                sql.execute('DROP TABLE IF EXISTS RAYS')
                def maker = new NoiseMapByReceiverMaker('BUILDINGS', 'SOURCES', 'RECEIVERS')
                maker.setSourcesZIsAltitude(true)
                maker.setReceiversZIsAltitude(true)
                maker.setSoundReflectionOrder(0)
                maker.setComputeHorizontalDiffraction(false)
                maker.setComputeVerticalDiffraction(false)
                maker.setMaximumPropagationDistance(300)
                maker.setThreadCount(1)
                def parameters = maker.noiseMapDatabaseParameters
                parameters.exportReceiverPosition = true
                parameters.exportAttenuationMatrix = true
                parameters.exportAttenuationOutput = true
                parameters.keepAbsorption = false
                parameters.setRaysTable('RAYS')
                parameters.setExportRaysMethod(NoiseMapDatabaseParameters.ExportRaysMethods.TO_RAYS_TABLE)
                if (compact) maker.setComputeRaysOutFactory(new CompactSpectralRayProcessing(
                        parameters, maker.exitWhenDone, maker.aborted))
                maker.run(connection, new EmptyProgressVisitor())
                def rays = sql.rows('SELECT IDRECEIVER, IDSOURCE, METEO, PERIOD, PROBABILITY, LEQ, ' +
                        'ST_ASTEXT(THE_GEOM) WKT, PATH FROM RAYS ORDER BY IDRECEIVER, IDSOURCE, METEO, PERIOD, WKT')
                def receivers = sql.rows('SELECT IDRECEIVER, PERIOD, LEQ, LAEQ, ' +
                        bands.collect { 'HZ' + it }.join(',') + ' FROM RECEIVERS_LEVEL ORDER BY IDRECEIVER, PERIOD')
                [rays: rays, receivers: receivers]
            }
            def full = run(false)
            def compact = run(true)
            assert full.receivers == compact.receivers
            assert !full.rays.empty
            assert full.rays.size() == compact.rays.size()
            long fullBytes = 0, compactBytes = 0
            full.rays.eachWithIndex { fullRow, index ->
                def compactRow = compact.rays[index]
                def fullPath = new JsonSlurper().parseText(fullRow.PATH.toString())
                def compactPath = new JsonSlurper().parseText(compactRow.PATH.toString())
                assert compactPath.keySet() == ['aGlobal'] as Set
                assert fullPath.aGlobal == compactPath.aGlobal
                assert compactPath.aGlobal.size() == bands.size()
                fullBytes += fullRow.PATH.toString().length()
                compactBytes += compactRow.PATH.toString().length()
                assert fullRow.findAll { key, value -> key != 'PATH' } == compactRow.findAll { key, value -> key != 'PATH' }
            }
            assert compactBytes < fullBytes / 2
            println "${road ? 'Road' : 'Railway'}: ${full.rays.size()} rays; PATH ${fullBytes} -> ${compactBytes} bytes; levels unchanged"
        }
    }

    private static void withFixture(List<Integer> bands, boolean road, Closure test) {
        def connection = new org.h2gis.utilities.wrapper.ConnectionWrapper(
                DriverManager.getConnection('jdbc:h2:mem:compact_' + UUID.randomUUID(), 'sa', ''))
        try {
            H2GISFunctions.load(connection)
            def sql = new Sql(connection)
            sql.execute('CREATE TABLE BUILDINGS(PK INTEGER PRIMARY KEY, THE_GEOM GEOMETRY(POLYGONZ,2154), HEIGHT DOUBLE)')
            sql.execute('CREATE TABLE RECEIVERS(PK INTEGER PRIMARY KEY, THE_GEOM GEOMETRY(POINTZ,2154))')
            sql.execute("INSERT INTO RECEIVERS VALUES(1, ST_GEOMFROMTEXT('POINT Z(700000 6600020 4)',2154)), " +
                    "(2, ST_GEOMFROMTEXT('POINT Z(700010 6600040 4)',2154))")
            def fields = road ? ['D','E','N'].collectMany { period -> bands.collect { "HZ${period}${it}" } } : bands.collect { "HZ${it}" }
            sql.execute('CREATE TABLE SOURCES(PK INTEGER PRIMARY KEY, THE_GEOM GEOMETRY(LINESTRINGZ,2154), ' +
                    fields.collect { it + ' DOUBLE' }.join(',') + ')')
            sql.execute("INSERT INTO SOURCES VALUES(1, ST_GEOMFROMTEXT('LINESTRING Z(699950 6600000 0.5,700050 6600000 0.5)',2154), " +
                    fields.indices.collect { 60 + it % bands.size() }.join(',') + ')')
            test(connection, sql)
        } finally {
            connection.close()
        }
    }
}
