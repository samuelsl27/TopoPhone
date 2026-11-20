package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodesyFixtures

/** Una fila de `fixtures/geodesia/omerc.csv` (generada con pyproj por `tools/geodesia/proyecciones.py`). */
internal data class OmercVector(
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
        fun all(): List<OmercVector> = GeodesyFixtures.rows("omerc.csv").map(::parse)

        private fun parse(row: List<String>): OmercVector {
            val inverseFlattening = row[3].toDouble()
            val ellipsoid = Ellipsoid(row[2].toDouble(), if (inverseFlattening == 0.0) Double.POSITIVE_INFINITY else inverseFlattening)
            val parameters: ProjectionParameters = if (row[1] == "A") {
                ObliqueMercatorA(
                    row[6].toDouble(),
                    row[7].toDouble(),
                    row[8].toDouble(),
                    row[9].toDouble(),
                    row[10].toDouble(),
                    row[11].toDouble(),
                    row[12].toDouble(),
                )
            } else {
                ObliqueMercatorB(
                    row[6].toDouble(),
                    row[7].toDouble(),
                    row[8].toDouble(),
                    row[9].toDouble(),
                    row[10].toDouble(),
                    row[11].toDouble(),
                    row[12].toDouble(),
                )
            }
            val setup = ProjectionSetup(ellipsoid, parameters, LccVector.unitOf(row[5].toDouble()), primeMeridianDeg = row[4].toDouble())
            return OmercVector(
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
