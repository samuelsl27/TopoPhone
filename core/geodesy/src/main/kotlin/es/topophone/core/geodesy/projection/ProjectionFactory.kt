package es.topophone.core.geodesy.projection

/**
 * Valida el SC y construye su proyección con el meridiano de origen ya aplicado (S032/RF-3, RF-6, RF-9, RF-12, RF-15, RF-16, RF-17).
 * Devuelve **todos** los problemas a la vez, cada uno con su parámetro y su causa. La Transversa de Mercator y Web Mercator se siguen
 * calculando con sus clases de siempre (S008, S009): aquí devuelven `UNSUPPORTED_METHOD`.
 */
object ProjectionFactory {

    fun create(setup: ProjectionSetup): ProjectionResult {
        val problems = ProjectionValidation.problems(setup.parameters) + commonProblems(setup)
        if (problems.isNotEmpty()) return ProjectionResult.Invalid(problems)
        // Los falsos pasan a metros una sola vez, aquí (RF-16); los métodos trabajan siempre en metros.
        val parameters = setup.parameters.inMetres(setup.linearUnit)
        val projection = when (parameters) {
            is LambertConic1Sp, is LambertConic2Sp -> LambertConic.create(setup.ellipsoid, parameters)
            is MercatorA, is MercatorB -> Mercator.create(setup.ellipsoid, parameters)
            is ObliqueMercatorA, is ObliqueMercatorB -> ObliqueMercator.create(setup.ellipsoid, parameters)
            else -> return ProjectionResult.Invalid(listOf(ParameterProblem(ProjectionParameter.METHOD, ProblemKind.UNSUPPORTED_METHOD)))
        }
        return ProjectionResult.Ok(withPrimeMeridian(projection, setup.primeMeridianDeg))
    }

    private fun withPrimeMeridian(projection: MapProjection, primeMeridianDeg: Double): MapProjection =
        if (primeMeridianDeg == 0.0) projection else PrimeMeridianProjection(projection, primeMeridianDeg)

    /** Lo que todos los métodos comparten: los falsos Este y Norte deben ser números finitos. */
    private fun commonProblems(setup: ProjectionSetup): List<ParameterProblem> = buildList {
        val (easting, northing) = setup.parameters.falseOrigin()
        if (!easting.isFinite()) add(ParameterProblem(ProjectionParameter.FALSE_EASTING, ProblemKind.NOT_FINITE))
        if (!northing.isFinite()) add(ParameterProblem(ProjectionParameter.FALSE_NORTHING, ProblemKind.NOT_FINITE))
    }
}
