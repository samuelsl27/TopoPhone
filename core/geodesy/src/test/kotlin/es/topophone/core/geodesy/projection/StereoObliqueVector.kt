package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodesyFixtures

/** Una fila de `fixtures/geodesia/stereo_oblicua.csv` (generada con pyproj por `tools/geodesia/proyecciones.py`). */
internal data class StereoObliqueVector(
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
        fun all(): List<StereoObliqueVector> = GeodesyFixtures.rows("stereo_oblicua.csv").map(::parse)

        private fun parse(row: List<String>): StereoObliqueVector {
            val inverseFlattening = row[2].toDouble()
            val ellipsoid = Ellipsoid(row[1].toDouble(), if (inverseFlattening == 0.0) Double.POSITIVE_INFINITY else inverseFlattening)
            val parameters =
                ObliqueStereographic(row[5].toDouble(), row[6].toDouble(), row[7].toDouble(), row[8].toDouble(), row[9].toDouble())
            val setup = ProjectionSetup(ellipsoid, parameters, LccVector.unitOf(row[4].toDouble()), primeMeridianDeg = row[3].toDouble())
            return StereoObliqueVector(
                row[0],
                setup,
                row[10].toDouble(),
                row[11].toDouble(),
                row[12].toDouble(),
                row[13].toDouble(),
                row[14].toDouble(),
                row[15].toDouble(),
            )
        }
    }
}
