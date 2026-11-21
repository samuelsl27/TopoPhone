package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.geodesy.GeodeticPoint

/** Una fila de `fixtures/geodesia/molodensky_badekas.csv` (generada con pyproj por `tools/geodesia/datum.py`). */
internal data class MolodenskyBadekasVector(
    val case: String,
    val source: Ellipsoid,
    val target: Ellipsoid,
    val helmert: HelmertParameters,
    val pivot: Triple<Double, Double, Double>,
    val input: GeodeticPoint,
    val forward: GeodeticPoint,
    val inverse: GeodeticPoint,
) {
    fun transformation(): MolodenskyBadekas {
        val parameters = (MolodenskyBadekasParameters.of(helmert, pivot) as MolodenskyBadekasParametersResult.Ok).parameters
        return MolodenskyBadekas(parameters, source, target, TransformationInfo(case))
    }

    companion object {
        fun all(): List<MolodenskyBadekasVector> = GeodesyFixtures.rows("molodensky_badekas.csv").map(::parse)

        private fun parse(row: List<String>): MolodenskyBadekasVector {
            val convention = if (row[2] == "PV") HelmertConvention.POSITION_VECTOR else HelmertConvention.COORDINATE_FRAME
            val helmert = HelmertParameters.seven(
                Triple(row[7].toDouble(), row[8].toDouble(), row[9].toDouble()),
                Triple(row[10].toDouble(), row[11].toDouble(), row[12].toDouble()),
                RotationUnit.ARC_SECOND,
                row[13].toDouble(),
                convention,
            )
            return MolodenskyBadekasVector(
                case = row[0],
                source = Ellipsoid(row[3].toDouble(), row[4].toDouble()),
                target = Ellipsoid(row[5].toDouble(), row[6].toDouble()),
                helmert = (helmert as HelmertParametersResult.Ok).parameters,
                pivot = Triple(row[14].toDouble(), row[15].toDouble(), row[16].toDouble()),
                input = GeodeticPoint(row[17].toDouble(), row[18].toDouble(), row[19].toDouble()),
                forward = GeodeticPoint(row[20].toDouble(), row[21].toDouble(), row[22].toDouble()),
                inverse = GeodeticPoint(row[23].toDouble(), row[24].toDouble(), row[25].toDouble()),
            )
        }
    }
}
