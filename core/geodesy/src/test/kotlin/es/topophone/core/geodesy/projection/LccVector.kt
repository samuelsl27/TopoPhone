package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.model.project.LinearUnit
import kotlin.math.abs

/** Una fila de `fixtures/geodesia/lcc.csv` (generada con pyproj por `tools/geodesia/proyecciones.py`): el SC, el punto y lo que da PROJ. */
internal data class LccVector(
    val case: String,
    val setup: ProjectionSetup,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val eastingM: Double,
    val northingM: Double,
    val scale: Double,
    val convergenceDeg: Double,
) {
    companion object {
        fun all(): List<LccVector> = GeodesyFixtures.rows("lcc.csv").map(::parse)

        fun unitOf(metresPerUnit: Double): LinearUnit = LinearUnit.entries.first { abs(it.metres - metresPerUnit) < 1e-9 }

        private fun parse(row: List<String>): LccVector {
            val inverseFlattening = row[3].toDouble()
            val ellipsoid = Ellipsoid(row[2].toDouble(), if (inverseFlattening == 0.0) Double.POSITIVE_INFINITY else inverseFlattening)
            val parameters: ProjectionParameters = if (row[1] == "2SP") {
                LambertConic2Sp(
                    row[6].toDouble(),
                    row[7].toDouble(),
                    row[8].toDouble(),
                    row[9].toDouble(),
                    row[11].toDouble(),
                    row[12].toDouble(),
                )
            } else {
                LambertConic1Sp(row[6].toDouble(), row[7].toDouble(), row[10].toDouble(), row[11].toDouble(), row[12].toDouble())
            }
            val setup = ProjectionSetup(ellipsoid, parameters, unitOf(row[5].toDouble()), primeMeridianDeg = row[4].toDouble())
            return LccVector(
                row[0],
                setup,
                row[13].toDouble(),
                row[14].toDouble(),
                row[15].toDouble(),
                row[16].toDouble(),
                row[17].toDouble(),
                row[18].toDouble(),
            )
        }
    }
}
