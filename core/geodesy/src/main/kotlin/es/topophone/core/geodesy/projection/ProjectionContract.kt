package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.PointFactors
import es.topophone.core.model.project.LinearUnit

/**
 * Los métodos de proyección que la app sabe calcular o que el catálogo de SC (S034) puede nombrar (S032/RF-20).
 * [implemented] es la lista que consume el generador del catálogo (ADR-3): lo que no esté aquí queda fuera.
 */
enum class ProjectionMethod {
    TRANSVERSE_MERCATOR,
    LAMBERT_CONIC_1SP,
    LAMBERT_CONIC_2SP,
    MERCATOR_A,
    MERCATOR_B,
    OBLIQUE_MERCATOR_A,
    OBLIQUE_MERCATOR_B,
    OBLIQUE_STEREOGRAPHIC,
    POLAR_STEREOGRAPHIC_A,
    POLAR_STEREOGRAPHIC_B,
    POLAR_STEREOGRAPHIC_C,
    WEB_MERCATOR,
    ;

    companion object {
        /** Los métodos que se calculan hoy: la Transversa de Mercator (S008) y Web Mercator (S009); cada tarea de S032 añade los suyos. */
        val implemented: List<ProjectionMethod> =
            listOf(
                TRANSVERSE_MERCATOR,
                WEB_MERCATOR,
                LAMBERT_CONIC_1SP,
                LAMBERT_CONIC_2SP,
                MERCATOR_A,
                MERCATOR_B,
                OBLIQUE_MERCATOR_A,
                OBLIQUE_MERCATOR_B,
                OBLIQUE_STEREOGRAPHIC,
                POLAR_STEREOGRAPHIC_A,
                POLAR_STEREOGRAPHIC_B,
                POLAR_STEREOGRAPHIC_C,
            )
    }
}

/** Este y Norte en metros. */
data class ProjectedPoint(val eastingM: Double, val northingM: Double)

/**
 * Una proyección ya construida y validada. Las longitudes que entran y salen son **de Greenwich**; las coordenadas proyectadas, en metros.
 * Un punto sin imagen en la proyección (el polo opuesto, las antípodas del origen…) devuelve `null`: nunca un valor extrapolado (S032/RF-19, ADR-5).
 */
interface MapProjection {
    /** Geográficas (grados) → (Este, Norte) en metros. */
    fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint?

    /** (Este, Norte) en metros → geográficas (grados). */
    fun inverse(eastingM: Double, northingM: Double): GeoPoint?

    /** Factor de escala y convergencia de meridianos con el criterio de S008/RF-13 y RF-14. */
    fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors?
}

/** Cada uno de los parámetros que puede fallar al validar una proyección (S032/RF-3, RF-6, RF-9, RF-12, RF-15). */
enum class ProjectionParameter {
    METHOD,
    LATITUDE_OF_ORIGIN,
    LONGITUDE_OF_ORIGIN,
    LATITUDE_OF_CENTRE,
    LONGITUDE_OF_CENTRE,
    STANDARD_PARALLEL,
    STANDARD_PARALLEL_1,
    STANDARD_PARALLEL_2,
    AZIMUTH,
    RECTIFIED_GRID_ANGLE,
    SCALE_FACTOR,
    FALSE_EASTING,
    FALSE_NORTHING,
}

/** Por qué falla un parámetro. S034 y S035 lo traducen a un texto de `strings.xml`. */
enum class ProblemKind {
    /** El método existe en la lista pero todavía no se calcula. */
    UNSUPPORTED_METHOD,
    NOT_FINITE,
    OUT_OF_RANGE,
    AT_POLE,
    AT_EQUATOR,

    /** El método exige un polo (la A de la estereográfica polar) y el valor no lo es. */
    NOT_A_POLE,
    OPPOSITE_PARALLELS,
    NOT_POSITIVE,
    WRONG_HEMISPHERE,
}

data class ParameterProblem(val parameter: ProjectionParameter, val kind: ProblemKind)

/**
 * Todo lo que fija un SC para proyectar: el elipsoide, los parámetros del método, la unidad lineal en que se escriben el falso Este y el
 * falso Norte (S032/RF-16) y el meridiano de origen respecto a Greenwich, positivo al este (S032/RF-17).
 */
data class ProjectionSetup(
    val ellipsoid: Ellipsoid,
    val parameters: ProjectionParameters,
    val linearUnit: LinearUnit = LinearUnit.METRE,
    val primeMeridianDeg: Double = 0.0,
)

sealed interface ProjectionResult {
    data class Ok(val projection: MapProjection) : ProjectionResult

    /** Todos los problemas a la vez, cada uno con su parámetro y su causa. */
    data class Invalid(val problems: List<ParameterProblem>) : ProjectionResult
}
