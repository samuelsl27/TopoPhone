package es.topophone.core.geodesy.datum

/** Convención de las rotaciones: «vector de posición» o «marco de coordenadas»; difieren en el signo (S033/RF-2). */
enum class HelmertConvention { POSITION_VECTOR, COORDINATE_FRAME }

/** Unidad en que se dan las rotaciones (S033/RF-4). */
enum class RotationUnit { ARC_SECOND, RADIAN }

sealed interface HelmertParametersResult {
    data class Ok(val parameters: HelmertParameters) : HelmertParametersResult

    /** Todos los problemas a la vez, cada uno con su parámetro (S033/RF-6). */
    data class Invalid(val problems: List<ParameterProblem>) : HelmertParametersResult
}

/**
 * Parámetros de un Helmert en SI: traslaciones en metros, rotaciones en radianes y escala adimensional (S033/RF-4). Los de 3, 4 y 6
 * parámetros son el de 7 con lo que no usan a cero (S033/RF-1).
 */
class HelmertParameters private constructor(
    val txM: Double,
    val tyM: Double,
    val tzM: Double,
    val rxRad: Double,
    val ryRad: Double,
    val rzRad: Double,
    val scale: Double,
    val convention: HelmertConvention,
) {
    companion object {
        private const val PPM = 1e-6

        /** Tres traslaciones. */
        fun translations3(translationM: Triple<Double, Double, Double>): HelmertParametersResult =
            create(translationM, Triple(0.0, 0.0, 0.0), 0.0, HelmertConvention.POSITION_VECTOR)

        /** Tres traslaciones y un factor de escala en ppm, sin rotaciones (S033/RF-1). */
        fun translationsScale4(translationM: Triple<Double, Double, Double>, scalePpm: Double): HelmertParametersResult =
            create(translationM, Triple(0.0, 0.0, 0.0), scalePpm, HelmertConvention.POSITION_VECTOR)

        /** Tres traslaciones y tres rotaciones. */
        fun rotations6(
            translationM: Triple<Double, Double, Double>,
            rotation: Triple<Double, Double, Double>,
            rotationUnit: RotationUnit,
            convention: HelmertConvention,
        ): HelmertParametersResult = create(translationM, toRadians(rotation, rotationUnit), 0.0, convention)

        /** Tres traslaciones, tres rotaciones y escala en ppm. */
        fun seven(
            translationM: Triple<Double, Double, Double>,
            rotation: Triple<Double, Double, Double>,
            rotationUnit: RotationUnit,
            scalePpm: Double,
            convention: HelmertConvention,
        ): HelmertParametersResult = create(translationM, toRadians(rotation, rotationUnit), scalePpm, convention)

        private fun toRadians(rotation: Triple<Double, Double, Double>, unit: RotationUnit): Triple<Double, Double, Double> = when (unit) {
            RotationUnit.RADIAN -> rotation
            RotationUnit.ARC_SECOND -> Triple(
                Math.toRadians(rotation.first / 3600.0),
                Math.toRadians(rotation.second / 3600.0),
                Math.toRadians(rotation.third / 3600.0),
            )
        }

        private fun create(
            t: Triple<Double, Double, Double>,
            r: Triple<Double, Double, Double>,
            scalePpm: Double,
            convention: HelmertConvention,
        ): HelmertParametersResult {
            val problems = listOf(
                DatumParameter.TRANSLATION_X to t.first,
                DatumParameter.TRANSLATION_Y to t.second,
                DatumParameter.TRANSLATION_Z to t.third,
                DatumParameter.ROTATION_X to r.first,
                DatumParameter.ROTATION_Y to r.second,
                DatumParameter.ROTATION_Z to r.third,
                DatumParameter.SCALE to scalePpm,
            ).filter { !it.second.isFinite() }.map { ParameterProblem(it.first, ProblemKind.NOT_FINITE) }
            if (problems.isNotEmpty()) return HelmertParametersResult.Invalid(problems)
            return HelmertParametersResult.Ok(
                HelmertParameters(t.first, t.second, t.third, r.first, r.second, r.third, scalePpm * PPM, convention),
            )
        }
    }
}
