package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.geodesy.GeodeticPoint

/** Una fila de `fixtures/geodesia/cadena_tres_pasos.csv`: la cadena (columna `pasos`), el punto y lo que da la tubería completa de PROJ. */
internal data class ChainVector(
    val case: String,
    val steps: List<DatumTransformation>,
    val input: GeodeticPoint,
    val forward: GeodeticPoint,
) {
    fun chain(): TransformationChain = TransformationChain(steps, TransformationInfo(case))

    companion object {
        fun all(): List<ChainVector> = GeodesyFixtures.rows("cadena_tres_pasos.csv").map(::parse)

        /** `G` es la rejilla SPED2ETV2; `H:conv:a:rf:a:rf:tx:ty:tz:rx:ry:rz:s:n` es un Helmert de n parámetros. */
        fun parseSteps(encoded: String, name: String): List<DatumTransformation> = encoded.split('|').mapIndexed { index, step ->
            if (step == "G") {
                GridShiftTransformation(GridShiftVector.GRID_NAME, ClasspathGridSource("/shift/"), TransformationInfo("$name: rejilla"))
            } else {
                val f = step.split(':')
                val convention = if (f[1] == "PV") HelmertConvention.POSITION_VECTOR else HelmertConvention.COORDINATE_FRAME
                val t = Triple(f[6].toDouble(), f[7].toDouble(), f[8].toDouble())
                val r = Triple(f[9].toDouble(), f[10].toDouble(), f[11].toDouble())
                val scale = f[12].toDouble()
                val result = when (f[13].toInt()) {
                    3 -> HelmertParameters.translations3(t)
                    4 -> HelmertParameters.translationsScale4(t, scale)
                    6 -> HelmertParameters.rotations6(t, r, RotationUnit.ARC_SECOND, convention)
                    else -> HelmertParameters.seven(t, r, RotationUnit.ARC_SECOND, scale, convention)
                }
                HelmertTransformation(
                    (result as HelmertParametersResult.Ok).parameters,
                    Ellipsoid(f[2].toDouble(), f[3].toDouble()),
                    Ellipsoid(f[4].toDouble(), f[5].toDouble()),
                    TransformationInfo("$name: paso ${index + 1}"),
                )
            }
        }

        private fun parse(row: List<String>): ChainVector = ChainVector(
            case = row[0],
            steps = parseSteps(row[1], row[0]),
            input = GeodeticPoint(row[2].toDouble(), row[3].toDouble(), row[4].toDouble()),
            forward = GeodeticPoint(row[5].toDouble(), row[6].toDouble(), row[7].toDouble()),
        )
    }
}
