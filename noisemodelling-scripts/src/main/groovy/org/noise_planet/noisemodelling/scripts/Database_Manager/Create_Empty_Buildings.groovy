package org.noise_planet.noisemodelling.scripts.Database_Manager

import java.sql.Connection

title = 'Create an empty buildings table'
description = 'Creates the BUILDINGS table required by NoiseModelling when there are no buildings to import.'

inputs = [
        srid: [
                name: 'Projection identifier',
                title: 'Projection identifier',
                type: Integer.class
        ]
]

outputs = [
        result: [
                name: 'Result',
                title: 'Result',
                type: String.class
        ]
]

def exec(Connection connection, Map input) {
    int srid = input['srid'] as Integer
    def statement = connection.createStatement()
    statement.execute('DROP TABLE IF EXISTS BUILDINGS')
    statement.execute('CREATE TABLE BUILDINGS (PK INTEGER PRIMARY KEY, THE_GEOM GEOMETRY, HEIGHT REAL)')
    statement.execute("SELECT UpdateGeometrySRID('BUILDINGS', 'THE_GEOM', ${srid})")
    return 'Created empty BUILDINGS table'
}
