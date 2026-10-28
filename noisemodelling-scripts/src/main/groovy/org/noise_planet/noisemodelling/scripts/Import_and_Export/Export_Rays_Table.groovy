/**
 * NoiseModelling is an open-source tool designed to produce environmental noise maps on very large urban areas. It can be used as a Java library or be controlled through a user friendly web interface.
 *
 * This version is developed by the DECIDE team from the Lab-STICC (CNRS) and by the Mixt Research Unit in Environmental Acoustics (Université Gustave Eiffel).
 * <http://noise-planet.org/noisemodelling.html>
 *
 * NoiseModelling is distributed under GPL 3 license. You can read a copy of this License in the file LICENCE provided with this software.
 *
 * Contact: contact@noise-planet.org
 *
 */

/**
 * @Author Pierre Aumond, Université Gustave Eiffel
 * @Author Nicolas Fortin, Université Gustave Eiffel
 */

package org.noise_planet.noisemodelling.scripts.Import_and_Export

import org.h2gis.api.ProgressVisitor
import org.h2gis.functions.io.geojson.GeoJsonDriverFunction
import org.h2gis.utilities.JDBCUtilities
import org.noise_planet.noisemodelling.pathfinder.utils.profiler.RootProgressVisitor
import org.slf4j.LoggerFactory
import java.sql.Connection

title = 'Export noise rays table'
description = 'Export noise rays to GeoJSON, optionally filtering propagation conditions and period.'

inputs = [
        exportPath: [name: 'Export path', title: 'GeoJSON output path', type: String.class],
        tableToExport: [name: 'Rays table', title: 'Name of the noise rays table', type: String.class],
        confExportHomogeneousRaysOnly: [
                name: 'Export homogeneous rays only',
                title: 'Export homogeneous rays only',
                description: 'Export only homogeneous rays and the columns used by the simulator.',
                default: false,
                type: Boolean.class
        ],
        confExportRaysPeriod: [
                name: 'Ray export period',
                title: 'Ray export period',
                description: 'Export rays for D, E, or N. Leave empty to export all periods.',
                default: '',
                type: String.class
        ]
]
outputs = [result: [name: 'Exported table name', title: 'Exported rays table', type: String.class]]

def exec(Connection connection, Map input, ProgressVisitor progress) {
    boolean homogeneousOnly = input.getOrDefault('confExportHomogeneousRaysOnly', false) as Boolean
    String rayPeriod = (input.get('confExportRaysPeriod') ?: '').toString().trim().toUpperCase(Locale.ROOT)
    if (rayPeriod && !(rayPeriod in ['D', 'E', 'N'])) {
        throw new IllegalArgumentException('confExportRaysPeriod must be D, E, N, or empty')
    }
    String exportPath = input['exportPath'] as String
    if (!exportPath.toLowerCase(Locale.ROOT).endsWith('.geojson')) {
        throw new IllegalArgumentException('Noise rays must be exported to a .geojson file')
    }
    String tableToExport = (input['tableToExport'] as String).toUpperCase(Locale.ROOT)
    def fields = JDBCUtilities.getColumnNames(connection, tableToExport)
    if (fields.empty) throw new IllegalArgumentException('The rays table does not exist or has no columns')

    if (homogeneousOnly || rayPeriod) {
        String columns = homogeneousOnly ? 'THE_GEOM, IDRECEIVER, IDSOURCE, LEQ, METEO' : '*'
        // Spectral rays need aGlobal even when exporting only homogeneous propagation.
        if (homogeneousOnly && fields.any { it.equalsIgnoreCase('PATH') }) columns += ', PATH'
        List<String> filters = []
        if (homogeneousOnly) filters.add("METEO = 'homogeneous'")
        if (rayPeriod) filters.add("PERIOD = '" + rayPeriod + "'")
        tableToExport = '(SELECT ' + columns + ' FROM ' + tableToExport + ' WHERE ' + filters.join(' AND ') + ')'
    }

    def logger = LoggerFactory.getLogger('org.noise_planet.noisemodelling')
    logger.info('Start : Export Noise Rays')
    boolean hasRows = connection.createStatement().withCloseable { statement ->
        statement.executeQuery('SELECT 1 FROM ' + tableToExport + ' LIMIT 1').withCloseable { rows -> rows.next() }
    }
    // H2GIS cannot infer geometry metadata from an empty query result.
    if (!hasRows) {
        new File(exportPath).setText('{"type":"FeatureCollection","features":[]}', 'UTF-8')
    } else {
        new GeoJsonDriverFunction().exportTable(connection, tableToExport, new File(exportPath), true, progress)
    }
    logger.info('End : Export Noise Rays')
    [result: tableToExport]
}

def exec(Connection connection, Map input) {
    exec(connection, input, new RootProgressVisitor(1, true, 5))
}
