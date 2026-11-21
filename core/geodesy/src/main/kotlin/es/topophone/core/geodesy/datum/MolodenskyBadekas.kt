package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodeticPoint

sealed interface MolodenskyBadekasParametersResult {
    data class Ok(val parameters: MolodenskyBadekasParameters) : MolodenskyBadekasParametersResult

    /** Todos los problemas a la vez, cada uno con su parámetro (S033/RF-6). */
    data class Invalid(val problems: List<ParameterProblem>) : MolodenskyBadekasParametersResult
}

/** Los siete parámetros de Helmert y el punto de rotación (X₀, Y₀, Z₀) en metros, en el datum de origen (S033/RF-3). */
class MolodenskyBadekasParameters private constructor(val helmert: HelmertParameters, val pivot: Ecef) {
    companion object {
        fun of(helmert: HelmertParameters, pivotM: Triple<Double, Double, Double>): MolodenskyBadekasParametersResult {
            val problems = listOf(
                DatumParameter.PIVOT_X to pivotM.first,
                DatumParameter.PIVOT_Y to pivotM.second,
                DatumParameter.PIVOT_Z to pivotM.third,
            ).filter { !it.second.isFinite() }.map { ParameterProblem(it.first, ProblemKind.NOT_FINITE) }
            if (problems.isNotEmpty()) return MolodenskyBadekasParametersResult.Invalid(problems)
            return MolodenskyBadekasParametersResult.Ok(
                MolodenskyBadekasParameters(helmert, Ecef(pivotM.first, pivotM.second, pivotM.third)),
            )
        }
    }
}

/**
 * Molodensky-Badekas de 10 parámetros: Helmert de 7 referido a un punto de rotación propio, `X′ = T + P + (1 + s)·R·(X − P)`
 * (S033/RF-3; EPSG GN 7-2, método 9636). Con `P` en el centro de la Tierra coincide con el Helmert de 7 parámetros. El inverso es el
 * exacto del directo (S033/RF-5).
 */
class MolodenskyBadekas(
    parameters: MolodenskyBadekasParameters,
    private val source: Ellipsoid,
    private val target: Ellipsoid,
    override val info: TransformationInfo,
) : DatumTransformation {

    private val geocentric = GeocentricSevenParameter(parameters.helmert, parameters.pivot)

    internal val geocentricStep = GeocentricStep(source, target, geocentric)

    override fun forward(point: GeodeticPoint): TransformResult = geocentricResult(point, source, target, geocentric::forward)

    override fun inverse(point: GeodeticPoint): TransformResult = geocentricResult(point, target, source, geocentric::inverse)
}
