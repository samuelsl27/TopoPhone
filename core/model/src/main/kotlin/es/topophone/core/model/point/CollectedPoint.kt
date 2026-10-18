package es.topophone.core.model.point

import java.math.BigInteger

/** Instantánea (primera época que llega) o promediada (S010/RF-8, RF-9). */
enum class MeasurementMode { INSTANT, AVERAGED }

/** Condición del control de precisión que una medida no cumple (S010/RF-14). */
enum class PrecisionFailure { NOT_FIX, HORIZONTAL, VERTICAL, NO_ACCURACY }

/**
 * Control de precisión (S010/RF-13): solo FIX, activo por defecto, y límites de precisión horizontal y vertical
 * en metros, desactivados (null) por defecto.
 */
data class PrecisionControl(val fixOnly: Boolean = true, val horizontalLimitM: Double? = null, val verticalLimitM: Double? = null) {
    /** Condiciones que incumple una medida; vacío si cumple (S010/RF-14). Con la precisión igual al límite, cumple. */
    fun check(isFix: Boolean, horizontalM: Double?, verticalM: Double?): Set<PrecisionFailure> = buildSet {
        if (fixOnly && !isFix) add(PrecisionFailure.NOT_FIX)
        checkLimit(horizontalLimitM, horizontalM, PrecisionFailure.HORIZONTAL)
        checkLimit(verticalLimitM, verticalM, PrecisionFailure.VERTICAL)
    }

    private fun MutableSet<PrecisionFailure>.checkLimit(limit: Double?, value: Double?, failure: PrecisionFailure) {
        when {
            limit == null -> Unit
            value == null -> add(PrecisionFailure.NO_ACCURACY) // sin GST no hay precisión que comprobar
            value > limit -> add(failure)
        }
    }
}

/** Modo de medida, tiempo de promediado y control de precisión, los mismos para todos los proyectos (S010/RF-13). */
data class CollectorSettings(
    val mode: MeasurementMode = MeasurementMode.INSTANT,
    val averagingSeconds: Int = 5,
    val precision: PrecisionControl = PrecisionControl(),
) {
    /** El tiempo de promediado dentro de [AVERAGING_SECONDS]. */
    val validAveragingSeconds: Int get() = averagingSeconds.coerceIn(AVERAGING_SECONDS)

    companion object {
        /** S010/RF-9; S016 lo amplía a 60 min. */
        val AVERAGING_SECONDS = 1..60
    }
}

/** Nombres de los puntos (S010/RF-2, RF-3). */
object PointNames {
    private val TRAILING_NUMBER = Regex("""^(.*?)(\d+)$""")

    /** El nombre siguiente a [last]: su número final aumentado en uno con los ceros a la izquierda; sin número, se añade 1. */
    fun next(last: String?): String {
        if (last == null) return "1"
        val match = TRAILING_NUMBER.matchEntire(last) ?: return last + "1"
        val (prefix, digits) = match.destructured
        return prefix + (digits.toBigInteger() + BigInteger.ONE).toString().padStart(digits.length, '0')
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
)

/**
 * Un punto recolectado (S010/RF-16 a RF-18): la antena tal como la da el receptor (media si es promediado), las
 * frases NMEA de las épocas usadas, el jalón, el punto del suelo y sus coordenadas en el SC del proyecto (null si
 * no se pueden calcular), la calidad y las condiciones del control de precisión que no cumple.
 */
data class CollectedPoint(
    val id: String,
    val projectId: String,
    val name: String,
    val description: String,
    val antenna: GeodeticPosition,
    val poleHeightM: Double,
    val ground: GeodeticPosition,
    val easting: Double?,
    val northing: Double?,
    val height: Double?,
    val quality: PointQuality,
    val rawNmea: String,
    val outOfTolerance: Set<PrecisionFailure>,
    val measuredFromMs: Long,
    val measuredToMs: Long,
    val createdAtMs: Long,
)
