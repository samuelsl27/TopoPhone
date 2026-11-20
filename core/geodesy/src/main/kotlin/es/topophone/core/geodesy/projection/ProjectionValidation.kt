package es.topophone.core.geodesy.projection

import kotlin.math.abs

/**
 * Los parámetros de un SC que una proyección no puede calcular, con el parámetro que falla y su causa (S032/RF-3, RF-6, RF-9, RF-12, RF-15).
 * Devuelve **todos** los problemas, en el orden en que se declaran los parámetros. Cada tarea añade las reglas de su familia.
 */
internal object ProjectionValidation {

    fun problems(parameters: ProjectionParameters): List<ParameterProblem> = buildList {
        when (parameters) {
            is LambertConic1Sp -> {
                addNotNull(latitude(ProjectionParameter.LATITUDE_OF_ORIGIN, parameters.latitudeOfOriginDeg, equatorAllowed = false))
                addNotNull(longitude(ProjectionParameter.LONGITUDE_OF_ORIGIN, parameters.longitudeOfOriginDeg))
                addNotNull(scaleFactor(parameters.scaleFactor))
            }
            is LambertConic2Sp -> {
                addNotNull(latitude(ProjectionParameter.LATITUDE_OF_ORIGIN, parameters.latitudeOfFalseOriginDeg))
                addNotNull(longitude(ProjectionParameter.LONGITUDE_OF_ORIGIN, parameters.longitudeOfFalseOriginDeg))
                val first = latitude(ProjectionParameter.STANDARD_PARALLEL_1, parameters.standardParallel1Deg)
                val second = latitude(ProjectionParameter.STANDARD_PARALLEL_2, parameters.standardParallel2Deg)
                addNotNull(first)
                addNotNull(second)
                // Los dos paralelos opuestos (también los dos en el ecuador) dejan la constante del cono en 0: solo se mira si cada uno vale.
                if (first == null && second == null && parameters.standardParallel1Deg == -parameters.standardParallel2Deg) {
                    add(ParameterProblem(ProjectionParameter.STANDARD_PARALLEL_2, ProblemKind.OPPOSITE_PARALLELS))
                }
            }
            else -> Unit
        }
    }

    /** Una latitud en grados: finita, dentro de ±90°, y sin polo ni ecuador si el método no los admite. */
    private fun latitude(
        parameter: ProjectionParameter,
        valueDeg: Double,
        poleAllowed: Boolean = false,
        equatorAllowed: Boolean = true,
    ): ParameterProblem? {
        val kind = when {
            !valueDeg.isFinite() -> ProblemKind.NOT_FINITE
            abs(valueDeg) > 90.0 -> ProblemKind.OUT_OF_RANGE
            abs(valueDeg) == 90.0 && !poleAllowed -> ProblemKind.AT_POLE
            valueDeg == 0.0 && !equatorAllowed -> ProblemKind.AT_EQUATOR
            else -> null
        }
        return kind?.let { ParameterProblem(parameter, it) }
    }

    /** Una longitud en grados: finita y dentro de ±180°. */
    private fun longitude(parameter: ProjectionParameter, valueDeg: Double): ParameterProblem? {
        val kind = when {
            !valueDeg.isFinite() -> ProblemKind.NOT_FINITE
            abs(valueDeg) > 180.0 -> ProblemKind.OUT_OF_RANGE
            else -> null
        }
        return kind?.let { ParameterProblem(parameter, it) }
    }

    private fun scaleFactor(value: Double): ParameterProblem? {
        val kind = when {
            !value.isFinite() -> ProblemKind.NOT_FINITE
            value <= 0.0 -> ProblemKind.NOT_POSITIVE
            else -> null
        }
        return kind?.let { ParameterProblem(ProjectionParameter.SCALE_FACTOR, it) }
    }

    private fun MutableList<ParameterProblem>.addNotNull(problem: ParameterProblem?) {
        if (problem != null) add(problem)
    }
}
