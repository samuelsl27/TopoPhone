package es.topophone.core.model.project

/** Unidades lineales del proyecto, con su valor en metros (S006/RF-9, RNF-T9). */
enum class LinearUnit(val metres: Double) {
    METRE(1.0),
    INTERNATIONAL_FOOT(0.3048),

    /** Pie topográfico de EE. UU.: 1200/3937 m. */
    US_SURVEY_FOOT(1200.0 / 3937.0),
    ;

    fun fromMetres(valueM: Double): Double = valueM / metres
}

/** Unidades angulares del proyecto (S006/RF-9, RNF-T9). */
enum class AngularUnit { DECIMAL_DEGREES, DMS, GON }

/** Sistema de referencia en el que llegan las correcciones (S006/RF-10). */
enum class CorrectionsDatum { ETRS89, REGCAN95, ITRF_WGS84 }

/** Datum geodésico de un sistema de coordenadas. */
enum class GeodeticDatum { ETRS89, }

/** Modelo de las altitudes de un sistema de coordenadas. */
enum class HeightModel { EGM08_REDNAP, }

/**
 * Sistemas de coordenadas de los proyectos en esta versión: ETRS89 / UTM en un huso fijo, con
 * altitudes EGM08-REDNAP (S006/RF-8). Los demás de España llegan en S008.
 */
enum class ProjectCrs(val utmZone: Int, val epsg: Int) {
    ETRS89_UTM29(29, 25829),
    ETRS89_UTM30(30, 25830),
    ETRS89_UTM31(31, 25831),
    ;

    val datum: GeodeticDatum get() = GeodeticDatum.ETRS89
    val heights: HeightModel get() = HeightModel.EGM08_REDNAP
}
