package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodesyFixtures

/** Una fila de `fixtures/geodesia/stereo_polar.csv` (generada con pyproj por `tools/geodesia/proyecciones.py`). */
internal data class StereoPolarVector(
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
        fun all(): List<StereoPolarVector> = GeodesyFixtures.rows("stereo_polar.csv").map(::parse)

        private fun parse(row: List<String>): StereoPolarVector {
            val inverseFlattening = row[3].toDouble()
            val ellipsoid = Ellipsoid(row[2].toDouble(), if (inverseFlattening == 0.0) Double.POSITIVE_INFINITY else inverseFlattening)
            val latitude = row[6].toDouble()
            val longitude = row[7].toDouble()
            val falseEasting = row[9].toDouble()
            val falseNorthing = row[10].toDouble()
            val parameters: ProjectionParameters = when (row[1]) {
                "A" -> PolarStereographicA(latitude, longitude, row[8].toDouble(), falseEasting, falseNorthing)
                "B" -> PolarStereographicB(latitude, longitude, falseEasting, falseNorthing)
                else -> PolarStereographicC(latitude, longitude, falseEasting, falseNorthing)
            }
            val setup = ProjectionSetup(ellipsoid, parameters, LccVector.unitOf(row[5].toDouble()), primeMeridianDeg = row[4].toDouble())
            return StereoPolarVector(
                row[0],
                setup,
                row[11].toDouble(),
                row[12].toDouble(),
                row[13].toDouble(),
                row[14].toDouble(),
                row[15].toDouble(),
                row[16].toDouble(),
            )
        }
    }
}
