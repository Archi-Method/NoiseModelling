package org.noise_planet.noisemodelling.scripts

import groovy.json.JsonSlurper
import groovy.sql.Sql
import org.h2gis.functions.factory.H2GISFunctions
import org.h2gis.utilities.wrapper.ConnectionWrapper
import org.junit.jupiter.api.Test
import org.noise_planet.noisemodelling.scripts.Import_and_Export.Export_Rays_Table
import org.noise_planet.noisemodelling.scripts.Import_and_Export.Export_Table
import java.nio.file.Files
import java.sql.DriverManager

class TestExportRaysTable {
    @Test
    void testDefaultExportsAllRays() {
        withFixture { connection, sql, output ->
            def features = export(connection, output, [:])
            assert features.size() == 6
            assert features.collect { it.get('properties').get('PERIOD') }.toSet() == ['D', 'E', 'N'] as Set
            assert features.collect { it.get('properties').get('METEO') }.toSet() == ['homogeneous', 'favorable'] as Set
        }
    }

    @Test
    void testHomogeneousAndPeriodFiltersPreserveSpectra() {
        withFixture { connection, sql, output ->
            assert export(connection, output, [confExportHomogeneousRaysOnly: true]).size() == 3
            def features = export(connection, output,
                    [confExportHomogeneousRaysOnly: true, confExportRaysPeriod: ' d '])
            assert features.size() == 1
            assert features[0].get('properties').get('METEO') == 'homogeneous'
            assert features[0].get('properties').get('IDRECEIVER') == 1
            assert features[0].get('properties').get('IDSOURCE') == 2
            assert features[0].get('properties').get('LEQ') == -20
            assert features[0].get('properties').keySet() == ['IDRECEIVER', 'IDSOURCE', 'LEQ', 'METEO', 'PATH'] as Set
            assert new JsonSlurper().parseText(features[0].get('properties').get('PATH')).aGlobal == [-30, 0, 5]
            assert features[0].geometry.type == 'LineString'
            assert export(connection, output, [confExportRaysPeriod: 'E']).size() == 2
        }
    }

    @Test
    void testLegacyRaysWithoutPath() {
        withFixture { connection, sql, output ->
            sql.execute('ALTER TABLE RAYS DROP COLUMN PATH')
            def features = export(connection, output, [confExportHomogeneousRaysOnly: true, confExportRaysPeriod: 'D'])
            assert features.size() == 1
            assert !features[0].get('properties').containsKey('PATH')
        }
    }

    @Test
    void testEmptyExports() {
        withFixture { connection, sql, output ->
            sql.execute("DELETE FROM RAYS WHERE PERIOD = 'N'")
            assert export(connection, output, [confExportRaysPeriod: 'N']).empty
            sql.execute('DELETE FROM RAYS')
            assert export(connection, output, [:]).empty
        }
    }

    @Test
    void testInvalidArguments() {
        withFixture { connection, sql, output ->
            try {
                export(connection, output, [confExportRaysPeriod: "D' OR 1=1"])
                assert false: 'Invalid period must be rejected'
            } catch (IllegalArgumentException ex) {
                assert ex.message.contains('confExportRaysPeriod')
            }
            assert !output.exists()
            try {
                new Export_Rays_Table().exec(connection, [tableToExport: 'RAYS', exportPath: output.path + '.csv'])
                assert false: 'Unsupported file format must be rejected'
            } catch (IllegalArgumentException ex) {
                assert ex.message.contains('.geojson')
            }
            assert !output.exists()
        }
    }

    @Test
    void testGenericExporterRemainsUnfiltered() {
        withFixture { connection, sql, output ->
            def generic = new Export_Table()
            generic.run()
            assert !generic.binding.getVariable('inputs').containsKey('confExportHomogeneousRaysOnly')
            assert !generic.binding.getVariable('inputs').containsKey('confExportRaysPeriod')
            generic.exec(connection, [tableToExport: 'RAYS', exportPath: output.path])
            assert new JsonSlurper().parse(output).features.size() == 6
        }
    }

    private static List export(connection, File output, Map options) {
        new Export_Rays_Table().exec(connection, [tableToExport: 'RAYS', exportPath: output.path] + options)
        def collection = new JsonSlurper().parse(output)
        assert collection.type == 'FeatureCollection'
        collection.features
    }

    private static void withFixture(Closure test) {
        def directory = Files.createTempDirectory('export-rays-test').toFile()
        def connection = new ConnectionWrapper(DriverManager.getConnection('jdbc:h2:mem:rays_' + UUID.randomUUID(), 'sa', ''))
        try {
            H2GISFunctions.load(connection)
            def sql = new Sql(connection)
            sql.execute('CREATE TABLE RAYS(THE_GEOM GEOMETRY(LINESTRINGZ,2154), IDRECEIVER BIGINT, IDSOURCE BIGINT, ' +
                    'LEQ DOUBLE, METEO VARCHAR, PERIOD VARCHAR, PATH VARCHAR, UNUSED_DIAGNOSTIC DOUBLE)')
            ['D', 'E', 'N'].each { period ->
                ['homogeneous', 'favorable'].each { meteo ->
                    sql.execute("INSERT INTO RAYS VALUES(ST_GEOMFROMTEXT('LINESTRING Z(700000 6600000 0.5,700000 6600020 4)',2154), " +
                            "1,2,-20,?,?,?,123)", [meteo, period, '{"aGlobal":[-30,0,5]}'])
                }
            }
            test(connection, sql, new File(directory, 'rays.geojson'))
        } finally {
            connection.close()
            directory.deleteDir()
        }
    }
}
