package es.topophone.core.model.project

/** Unidades lineales del proyecto, con su valor en metros (S006/RF-9, RNF-T9). */
enum class LinearUnit(val metres: Double) {
    METRE(1.0),
    INTERNATIONAL_FOOT(0.3048),

    /** Pie topográfico de EE. UU.: 1200/3937 m. */
    US_SURVEY_FOOT(1200.0 / 3937.0),
    ;

    fun fromMetres(valueM: Double): Double = valueM / metres

    /** Lo que se escribe en la unidad del proyecto, en metros (S011/RF-17). */
    fun toMetres(value: Double): Double = value * metres
}

/** Unidades angulares del proyecto (S006/RF-9, RNF-T9). */
enum class AngularUnit { DECIMAL_DEGREES, DMS, GON }

/** Sistema de referencia en el que llegan las correcciones (S006/RF-10). */
enum class CorrectionsDatum { ETRS89, REGCAN95, ITRF_WGS84 }

/** Datum geodésico de un sistema de coordenadas (S008/RF-1). */
enum class GeodeticDatum { ETRS89, REGCAN95, ED50 }

/**
 * Altura del proyecto (S008/RF-2): altitud con el modelo EGM08-REDNAP de su ámbito (Península y
 * Baleares, o Canarias; S008/RF-4) o altura elipsoidal.
 */
enum class HeightSystem { ORTHOMETRIC, ELLIPSOIDAL }

/** Territorio de un SC (S008/RF-9), con el recuadro en el que se calcula. */
enum class CrsArea(private val latitudes: ClosedFloatingPointRange<Double>, private val longitudes: ClosedFloatingPointRange<Double>) {
    PENINSULA_BALEARES(35.0..44.5, -10.0..5.0),
    CANARIAS(27.0..29.5, -18.5..-13.0),
    ;

    fun contains(latitudeDeg: Double, longitudeDeg: Double): Boolean = latitudeDeg in latitudes && longitudeDeg in longitudes

    companion object {
        fun of(latitudeDeg: Double, longitudeDeg: Double): CrsArea? = entries.firstOrNull { it.contains(latitudeDeg, longitudeDeg) }
    }
}

/**
 * Sistemas de coordenadas de los proyectos: UTM en un huso fijo (huso forzado) o geográficas, en
 * ETRS89 (Península y Baleares), REGCAN95 (Canarias) o ED50 (solo UTM) (S006/RF-8, S008/RF-1). Los
 * nombres de S006 no cambian: se guardan por nombre.
 *
 * @property utmZone huso UTM, o `null` en coordenadas geográficas.
 */
enum class ProjectCrs(val datum: GeodeticDatum, val utmZone: Int?, val epsg: Int) {
    ETRS89_UTM29(GeodeticDatum.ETRS89, 29, 25829),
    ETRS89_UTM30(GeodeticDatum.ETRS89, 30, 25830),
    ETRS89_UTM31(GeodeticDatum.ETRS89, 31, 25831),
    ETRS89_GEOGRAPHIC(GeodeticDatum.ETRS89, null, 4258),
    REGCAN95_UTM27(GeodeticDatum.REGCAN95, 27, 4082),
    REGCAN95_UTM28(GeodeticDatum.REGCAN95, 28, 4083),
    REGCAN95_GEOGRAPHIC(GeodeticDatum.REGCAN95, null, 4081),
    ED50_UTM29(GeodeticDatum.ED50, 29, 23029),
    ED50_UTM30(GeodeticDatum.ED50, 30, 23030),
    ED50_UTM31(GeodeticDatum.ED50, 31, 23031),
    ;

    val isUtm: Boolean get() = utmZone != null

    /** S008/RF-9. */
    val area: CrsArea get() = if (datum == GeodeticDatum.REGCAN95) CrsArea.CANARIAS else CrsArea.PENINSULA_BALEARES

    /** S008/RF-2, RF-3: ED50 solo con altitud. */
    val heightSystems: List<HeightSystem>
        get() = if (datum == GeodeticDatum.ED50) listOf(HeightSystem.ORTHOMETRIC) else HeightSystem.entries

    /** Datum en el que deberían llegar las correcciones (S008/RF-17): ED50 se calcula desde ETRS89. */
    val correctionsDatum: CorrectionsDatum
        get() = if (datum == GeodeticDatum.REGCAN95) CorrectionsDatum.REGCAN95 else CorrectionsDatum.ETRS89

    /**
     * El SC de [target] más parecido a este, al cambiar de datum en el formulario: el mismo huso si existe,
     * geográficas si lo eran y el datum las tiene, y si no el huso habitual del datum (S008/RF-1).
     */
    fun closestIn(target: GeodeticDatum): ProjectCrs = of(target, utmZone)
        ?: of(target, null)?.takeIf { !isUtm }
        ?: of(target, defaultZone(target))!!

    companion object {
        private const val DEGREES_PER_ZONE = 6.0

        /** Huso habitual de cada datum: el de Madrid, y el de las islas centrales en Canarias. */
        private fun defaultZone(datum: GeodeticDatum): Int = if (datum == GeodeticDatum.REGCAN95) 28 else 30

        fun of(datum: GeodeticDatum, utmZone: Int?): ProjectCrs? = entries.firstOrNull { it.datum == datum && it.utmZone == utmZone }

        /**
         * SC UTM del huso y del territorio de la posición, partiendo del de por defecto (S008/RF-11): REGCAN95
         * en Canarias; en la Península y Baleares, ED50 si el de por defecto es ED50 y si no ETRS89. `null`
         * con geográficas por defecto o fuera de España.
         */
        fun suggested(default: ProjectCrs, latitudeDeg: Double, longitudeDeg: Double): ProjectCrs? {
            if (!default.isUtm) return null
            val datum = when (CrsArea.of(latitudeDeg, longitudeDeg) ?: return null) {
                CrsArea.CANARIAS -> GeodeticDatum.REGCAN95
                CrsArea.PENINSULA_BALEARES -> if (default.datum == GeodeticDatum.ED50) GeodeticDatum.ED50 else GeodeticDatum.ETRS89
            }
            val zone = kotlin.math.floor((longitudeDeg + 180.0) / DEGREES_PER_ZONE).toInt() + 1
            return of(datum, zone)
        }
    }
}
