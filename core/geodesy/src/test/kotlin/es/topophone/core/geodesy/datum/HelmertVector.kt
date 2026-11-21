package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.geodesy.GeodeticPoint

/** Una fila de `fixtures/geodesia/helmert.csv` (generada con pyproj por `tools/geodesia/datum.py`): la transformación, el punto y lo que da PROJ. */
internal data class HelmertVector(
    val case: String,
    val parameterCount: Int,
    val source: Ellipsoid,
    val target: Ellipsoid,
    val parameters: HelmertParameters,
    val input: GeodeticPoint,
    val forward: GeodeticPoint,
    val inverse: GeodeticPoint,
) {
    fun transformation(): HelmertTransformation = HelmertTransformation(parameters, source, target, TransformationInfo(case))

    companion object {
        fun all(): List<HelmertVector> = GeodesyFixtures.rows("helmert.csv").map(::parse)

        private fun parse(row: List<String>): HelmertVector {
            val convention = if (row[2] == "PV") HelmertConvention.POSITION_VECTOR else HelmertConvention.COORDINATE_FRAME
            val t = Triple(row[7].toDouble(), row[8].toDouble(), row[9].toDouble())
            val r = Triple(row[10].toDouble(), row[11].toDouble(), row[12].toDouble())
            val ppm = row[13].toDouble()
            val parameterCount = row[1].toInt()
            val result = when (parameterCount) {
                3 -> HelmertParameters.translations3(t)
                4 -> HelmertParameters.translationsScale4(t, ppm)
                6 -> HelmertParameters.rotations6(t, r, RotationUnit.ARC_SECOND, convention)
                else -> HelmertParameters.seven(t, r, RotationUnit.ARC_SECOND, ppm, convention)
            }
            return HelmertVector(
                case = row[0],
                parameterCount = parameterCount,
                source = Ellipsoid(row[3].toDouble(), row[4].toDouble()),
                target = Ellipsoid(row[5].toDouble(), row[6].toDouble()),
                parameters = (result as HelmertParametersResult.Ok).parameters,
                input = GeodeticPoint(row[14].toDouble(), row[15].toDouble(), row[16].toDouble()),
                forward = GeodeticPoint(row[17].toDouble(), row[18].toDouble(), row[19].toDouble()),
                inverse = GeodeticPoint(row[20].toDouble(), row[21].toDouble(), row[22].toDouble()),
            )
        }
    }
}
