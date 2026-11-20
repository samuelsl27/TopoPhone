package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodesyFixtures

/**
 * Una fila de `fixtures/geodesia/meridiano_origen.csv` o de `unidades_proyeccion.csv` (mismo formato; generadas con pyproj por
 * `tools/geodesia/proyecciones.py`): un SC de cualquiera de los diez métodos. Las longitudes del punto son las de Greenwich.
 */
internal data class PrimeMeridianVector(
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
        fun all(file: String = "meridiano_origen.csv"): List<PrimeMeridianVector> = GeodesyFixtures.rows(file).map(::parse)

        private fun parse(row: List<String>): PrimeMeridianVector {
            val inverseFlattening = row[3].toDouble()
            val ellipsoid = Ellipsoid(row[2].toDouble(), if (inverseFlattening == 0.0) Double.POSITIVE_INFINITY else inverseFlattening)
            val p = row.subList(6, 13).map { it.toDoubleOrNull() ?: Double.NaN }
            val parameters: ProjectionParameters = when (ProjectionMethod.valueOf(row[1])) {
                ProjectionMethod.LAMBERT_CONIC_1SP -> LambertConic1Sp(p[0], p[1], p[2], p[3], p[4])
                ProjectionMethod.LAMBERT_CONIC_2SP -> LambertConic2Sp(p[0], p[1], p[2], p[3], p[4], p[5])
                ProjectionMethod.MERCATOR_A -> MercatorA(p[0], p[1], p[2], p[3])
                ProjectionMethod.MERCATOR_B -> MercatorB(p[0], p[1], p[2], p[3])
                ProjectionMethod.OBLIQUE_MERCATOR_A -> ObliqueMercatorA(p[0], p[1], p[2], p[3], p[4], p[5], p[6])
                ProjectionMethod.OBLIQUE_MERCATOR_B -> ObliqueMercatorB(p[0], p[1], p[2], p[3], p[4], p[5], p[6])
                ProjectionMethod.OBLIQUE_STEREOGRAPHIC -> ObliqueStereographic(p[0], p[1], p[2], p[3], p[4])
                ProjectionMethod.POLAR_STEREOGRAPHIC_A -> PolarStereographicA(p[0], p[1], p[2], p[3], p[4])
                ProjectionMethod.POLAR_STEREOGRAPHIC_B -> PolarStereographicB(p[0], p[1], p[2], p[3])
                ProjectionMethod.POLAR_STEREOGRAPHIC_C -> PolarStereographicC(p[0], p[1], p[2], p[3])
                else -> error("${row[1]} no está en el fichero de meridianos de origen")
            }
            val setup = ProjectionSetup(ellipsoid, parameters, LccVector.unitOf(row[5].toDouble()), primeMeridianDeg = row[4].toDouble())
            return PrimeMeridianVector(
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
