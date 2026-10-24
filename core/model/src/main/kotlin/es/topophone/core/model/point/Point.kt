package es.topophone.core.model.point

import java.math.BigInteger
import java.text.Normalizer
import java.util.Locale

/** Nombres de los puntos (S010/RF-2, RF-3; S011/RF-16, RF-21). */
object PointNames {
    private val TRAILING_NUMBER = Regex("""^(.*?)(\d+)$""")

    /** El nombre siguiente a [last]: su número final aumentado en uno con los ceros a la izquierda; sin número, se añade 1. */
    fun next(last: String?): String {
        if (last == null) return "1"
        val match = TRAILING_NUMBER.matchEntire(last) ?: return last + "1"
        val (prefix, digits) = match.destructured
        return prefix + (digits.toBigInteger() + BigInteger.ONE).toString().padStart(digits.length, '0')
    }

    /** El siguiente a [last] que no esté en [taken] (S011/RF-16, RF-21). */
    fun nextFree(last: String?, taken: Set<String>): String {
        var name = next(last)
        while (name in taken) name = next(name)
        return name
    }

    fun clean(name: String): String = name.trim()

    fun isValid(name: String): Boolean = clean(name).isNotEmpty()
}

/** Posición geodésica en grados y altura elipsoidal en metros, sin redondear (AGENTS.md). */
data class GeodeticPosition(val latitudeDeg: Double, val longitudeDeg: Double, val ellipsoidalHeightM: Double?)

/**
 * Metadatos de calidad de un punto (S010/RF-18). [solution] es el nombre del estado de la solución del receptor
 * (`core:model` no depende de `core:gnss`); con el promediado, la peor de las épocas usadas.
 */
data class PointQuality(
    val solution: String,
    val satellites: Int?,
    val hdop: Double?,
    val correctionAgeS: Double?,
    val horizontalM: Double?,
    val verticalM: Double?,
    val mode: MeasurementMode,
    val epochsUsed: Int,
    val epochsDiscarded: Int,
    val fromReplay: Boolean,
    /** El tiempo realmente promediado en segundos, y las desviaciones típicas de las épocas usadas; null si no se promedió (S017/RF-12). */
    val averagedTimeS: Double? = null,
    val deviationHorizontalM: Double? = null,
    val deviationVerticalM: Double? = null,
    /** Medido por el modo automático; null en los puntos de antes de S017 y en los manuales (S017/RF-14). */
    val auto: Boolean? = null,
)

/** Medido con el recolector (S010), escrito a mano o duplicado (S011) o medido al replantear un punto de diseño (S018/RF-27). */
enum class PointOrigin { MEASURED, MANUAL, STAKED }

/** Un punto con medida del receptor: el medido y el replanteado; el manual solo tiene lo escrito (S011, S018). */
val PointOrigin.hasMeasurement: Boolean get() = this != PointOrigin.MANUAL

/**
 * Coordenadas de un punto del suelo: [ground] en geográficas del datum de los cálculos (ETRS89 o REGCAN95), con la
 * altura elipsoidal null si el punto no tiene altura, y Este, Norte y altura en el SC del proyecto (null lo que no
 * se puede calcular o no tiene).
 */
data class PointCoordinates(val ground: GeodeticPosition, val easting: Double?, val northing: Double?, val height: Double?)

/**
 * La medida de un punto (S010/RF-16 a RF-18): la antena tal como la da el receptor (media si es promediado), las
 * frases NMEA de las épocas usadas, la calidad y las condiciones del control de precisión que no cumple. No cambia
 * nunca (RNF-T6), salvo el jalón y sus [coordinates], que se recalculan desde la antena al corregirlo (S011/RF-14).
 */
data class Measurement(
    val antenna: GeodeticPosition,
    val poleHeightM: Double,
    val quality: PointQuality,
    val rawNmea: String,
    val outOfTolerance: Set<PrecisionFailure>,
    val measuredFromMs: Long,
    val measuredToMs: Long,
    val coordinates: PointCoordinates,
    /** La base con la que se midió, si la había (S015/RF-20). */
    val base: PointBase? = null,
    /** El modo de inclinación y la inclinación (S016/RF-22); null en los puntos de antes de S016. */
    val tilt: PointTilt? = null,
    /**
     * Cuántos tramos de frases NMEA tiene el punto fuera de su fila, cuando son demasiadas para ella; entonces [rawNmea]
     * está vacía (S017/RF-12). 0: todas las frases están en [rawNmea].
     */
    val nmeaChunks: Int = 0,
)

/**
 * La base con la que se midió un punto (S015/RF-20): su posición tal como la dio su fuente, la fuente, el identificador
 * de la estación y la altura de antena si venían en un 1005/1006, y la línea base en metros.
 */
data class PointBase(
    val position: GeodeticPosition,
    val source: BaseSource,
    val stationId: Int?,
    val antennaHeightM: Double?,
    val baselineM: Double,
)

/** Cómo se escriben las coordenadas de un punto manual (S011/RF-17). */
enum class EntryForm { PROJECT_CRS, GEOGRAPHIC }

/** La altura escrita: en el sistema de alturas del proyecto o elipsoidal (S011/RF-17). */
enum class HeightKind { PROJECT, ELLIPSOIDAL }

/**
 * Coordenadas tal como se escribieron (S011/RF-20): Este y Norte en metros en `PROJECT_CRS`, o latitud y longitud
 * en grados del datum del proyecto en `GEOGRAPHIC`; la altura en metros, o null si se dejó vacía (S011/RF-18).
 */
data class TypedCoordinates(val form: EntryForm, val first: Double, val second: Double, val height: Double?, val heightKind: HeightKind)

/**
 * De qué punto de diseño viene un punto replanteado (S018/RF-28): su identificador y su nombre, sus coordenadas de diseño
 * tal como eran al replantear y lo que se desvió lo medido (medido menos diseño) en Este, Norte, distancia horizontal y
 * cota, sin redondear. Con un SC geográfico, Este y Norte son del plano local de la punta.
 *
 * Desde S019 guarda también la cota de diseño usada ([designElevationM]: la del punto o la escrita a mano, y contra ella se
 * mide [dHeightM]), las tolerancias horizontal y vertical vigentes al medir y si el replanteo quedó dentro de ellas
 * ([withinTolerance], RF-13, RF-14). Un replanteo de antes de S019 no las tiene: está sin evaluar (RF-26).
 */
data class StakeoutRef(
    val designId: String,
    val designName: String,
    val design: PointCoordinates,
    val dEastM: Double,
    val dNorthM: Double,
    val dHorizontalM: Double,
    val dHeightM: Double?,
    val designElevationM: Double? = null,
    val toleranceHM: Double? = null,
    val toleranceVM: Double? = null,
    val withinTolerance: Boolean? = null,
) {
    /** Replanteado antes de S019, sin tolerancias ni veredicto guardados (S019/RF-26). */
    val notEvaluated: Boolean get() = withinTolerance == null

    /** Se replanteó con cota de diseño: hay diferencia de cota (S019/RF-14). */
    val hasElevation: Boolean get() = dHeightM != null
}

/**
 * Un punto del proyecto (S010, S011). [coordinates] son siempre las actuales: las de la medida, las escritas o las
 * editadas a mano.
 *
 * @property measurement solo en los puntos medidos.
 * @property typed lo escrito en un punto manual, o en un punto medido con las coordenadas editadas.
 * @property edited un punto medido cuyas coordenadas no son las de su medida (S011/RF-12).
 * @property hidden fuera del mapa (S011/RF-4).
 * @property copyOf nombre del punto del que es copia (S011/RF-21).
 * @property deletedAtMs en la papelera desde entonces (S011/RF-22).
 * @property staked de qué punto de diseño viene un punto replanteado, con sus diferencias (S018/RF-28).
 */
data class Point(
    val id: String,
    val projectId: String,
    val name: String,
    val description: String,
    val origin: PointOrigin,
    val coordinates: PointCoordinates,
    val measurement: Measurement?,
    val typed: TypedCoordinates?,
    val edited: Boolean,
    val hidden: Boolean,
    val copyOf: String?,
    val createdAtMs: Long,
    val deletedAtMs: Long?,
    val staked: StakeoutRef? = null,
) {
    /** Sin altura elipsoidal no hay altura en ningún sistema (S011/RF-18). */
    val hasHeight: Boolean get() = coordinates.ground.ellipsoidalHeightM != null

    val outOfTolerance: Boolean get() = measurement?.outOfTolerance?.isNotEmpty() == true

    /** Lo que necesitan la lista y el mapa (S011/RF-1, RF-3). */
    fun summary(): PointSummary = PointSummary(
        id = id,
        name = name,
        description = description,
        origin = origin,
        latitudeDeg = coordinates.ground.latitudeDeg,
        longitudeDeg = coordinates.ground.longitudeDeg,
        hasHeight = hasHeight,
        outOfTolerance = outOfTolerance,
        edited = edited,
        hidden = hidden,
        createdAtMs = createdAtMs,
    )

    companion object {
        /** Un punto recién medido: sus coordenadas son las de la medida (S010/RF-17). */
        fun measured(
            id: String,
            projectId: String,
            name: String,
            description: String,
            measurement: Measurement,
            createdAtMs: Long,
            staked: StakeoutRef? = null,
        ) = Point(
            id = id,
            projectId = projectId,
            name = name,
            description = description,
            origin = if (staked == null) PointOrigin.MEASURED else PointOrigin.STAKED,
            coordinates = measurement.coordinates,
            measurement = measurement,
            typed = null,
            edited = false,
            hidden = false,
            copyOf = null,
            createdAtMs = createdAtMs,
            deletedAtMs = null,
            staked = staked,
        )
    }
}

/** Qué cambió en una versión del historial (S011/RF-10): una edición, el jalón o la vuelta a la medida. */
enum class PointChange { EDIT, POLE, REVERT }

/** Un punto tal como era antes de un cambio (S011/RF-10; RNF-T6). */
data class PointSnapshot(
    val name: String,
    val description: String,
    val poleHeightM: Double?,
    val coordinates: PointCoordinates,
    val measuredCoordinates: PointCoordinates?,
    val typed: TypedCoordinates?,
    val edited: Boolean,
) {
    companion object {
        fun of(point: Point): PointSnapshot = PointSnapshot(
            name = point.name,
            description = point.description,
            poleHeightM = point.measurement?.poleHeightM,
            coordinates = point.coordinates,
            measuredCoordinates = point.measurement?.coordinates,
            typed = point.typed,
            edited = point.edited,
        )
    }
}

/** Una versión anterior de un punto, con la fecha del cambio (S011/RF-10, RF-11). */
data class PointHistoryEntry(
    val id: String,
    val pointId: String,
    val changedAtMs: Long,
    val change: PointChange,
    val previous: PointSnapshot,
)

/** Un punto en la lista de objetos o en el mapa, sin las frases NMEA (S011/RF-1, RF-3, RF-4). */
data class PointSummary(
    val id: String,
    val name: String,
    val description: String,
    val origin: PointOrigin,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val hasHeight: Boolean,
    val outOfTolerance: Boolean,
    val edited: Boolean,
    val hidden: Boolean,
    val createdAtMs: Long,
    /** Cuántas veces se ha replantado este punto de diseño (S018/RF-33); lo rellena el repositorio. */
    val stakeCount: Int = 0,
)

/** Búsqueda de la lista de objetos por nombre o descripción, sin distinguir mayúsculas ni acentos (S011/RF-2). */
object PointSearch {
    private val MARKS = Regex("""\p{Mn}+""")

    fun normalize(text: String): String = MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /** Si [summary] contiene [query]; una búsqueda vacía los incluye todos. */
    fun matches(summary: PointSummary, query: String): Boolean {
        val needle = normalize(query.trim())
        return needle.isEmpty() || needle in normalize(summary.name) || needle in normalize(summary.description)
    }
}
