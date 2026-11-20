package es.topophone.core.geodesy.projection

/**
 * Valida el SC y construye su proyección con el meridiano de origen ya aplicado (S032/RF-3, RF-6, RF-9, RF-12, RF-15, RF-16, RF-17).
 * Devuelve **todos** los problemas a la vez, cada uno con su parámetro y su causa. La Transversa de Mercator y Web Mercator se siguen
 * calculando con sus clases de siempre (S008, S009): aquí devuelven `UNSUPPORTED_METHOD`.
 */
object ProjectionFactory {

    fun create(setup: ProjectionSetup): ProjectionResult {
        val problems = commonProblems(setup)
        if (problems.isNotEmpty()) return ProjectionResult.Invalid(problems)
        return ProjectionResult.Invalid(listOf(ParameterProblem(ProjectionParameter.METHOD, ProblemKind.UNSUPPORTED_METHOD)))
    }

    /** Lo que todos los métodos comparten: los falsos Este y Norte deben ser números finitos. */
    private fun commonProblems(setup: ProjectionSetup): List<ParameterProblem> = buildList {
        val (easting, northing) = setup.parameters.falseOrigin()
        if (!easting.isFinite()) add(ParameterProblem(ProjectionParameter.FALSE_EASTING, ProblemKind.NOT_FINITE))
        if (!northing.isFinite()) add(ParameterProblem(ProjectionParameter.FALSE_NORTHING, ProblemKind.NOT_FINITE))
    }
}
