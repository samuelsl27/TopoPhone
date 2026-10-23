package es.topophone.core.model.point

import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.project.LinearUnit

/** Instantánea (primera época que llega) o promediada (S010/RF-8, RF-9). */
enum class MeasurementMode { INSTANT, AVERAGED }

/** Condición del control de precisión que una medida no cumple (S010/RF-14; inclinación máxima, S016/RF-20). */
enum class PrecisionFailure { NOT_FIX, HORIZONTAL, VERTICAL, NO_ACCURACY, TILT }

/**
 * Control de precisión (S010/RF-13): solo FIX, activo por defecto, y límites de precisión horizontal y vertical
 * en metros, desactivados (null) por defecto. Con la inclinación, también la inclinación máxima en radianes, activa
 * por defecto con 60° (S016/RF-20).
 */
data class PrecisionControl(
    val fixOnly: Boolean = true,
    val horizontalLimitM: Double? = null,
    val verticalLimitM: Double? = null,
    val maxTiltRad: Double? = DEFAULT_MAX_TILT_RAD,
) {
    /**
     * Condiciones que incumple una medida; vacío si cumple (S010/RF-14). Con la precisión igual al límite, cumple.
     * [tiltRad] es la mayor inclinación de las épocas usadas, o null si el modo no usa la inclinación (S016/RF-20).
     */
    fun check(isFix: Boolean, horizontalM: Double?, verticalM: Double?, tiltRad: Double? = null): Set<PrecisionFailure> = buildSet {
        if (fixOnly && !isFix) add(PrecisionFailure.NOT_FIX)
        checkLimit(horizontalLimitM, horizontalM, PrecisionFailure.HORIZONTAL)
        checkLimit(verticalLimitM, verticalM, PrecisionFailure.VERTICAL)
        val maxTilt = maxTiltRad
        if (maxTilt != null && tiltRad != null && tiltRad > maxTilt) add(PrecisionFailure.TILT)
    }

    private fun MutableSet<PrecisionFailure>.checkLimit(limit: Double?, value: Double?, failure: PrecisionFailure) {
        when {
            limit == null -> Unit
            value == null -> add(PrecisionFailure.NO_ACCURACY) // sin GST no hay precisión que comprobar
            value > limit -> add(failure)
        }
    }

    companion object {
        /** S016/RF-20: 60°, el límite de la precisión publicada del RS3 con inclinación. */
        val DEFAULT_MAX_TILT_RAD = Math.toRadians(60.0)

        /** S016/RF-20: la inclinación máxima se ajusta en grados enteros de 1 a 60. */
        val MAX_TILT_RANGE_DEG = 1..60
    }
}

/** Cuándo mide un punto el modo automático: cada cierto tiempo o cada cierta distancia (S017/RF-15). */
enum class AutoTrigger { TIME, DISTANCE }

/**
 * Modo de medida, tiempo de promediado y control de precisión, los mismos para todos los proyectos (S010/RF-13), y el
 * disparo, el intervalo y la distancia del modo automático (S017/RF-16, RF-17). Que el modo automático esté encendido
 * no se guarda: el recolector abre siempre en manual.
 */
data class CollectorSettings(
    val mode: MeasurementMode = MeasurementMode.INSTANT,
    val averagingSeconds: Int = 5,
    val precision: PrecisionControl = PrecisionControl(),
    val autoTrigger: AutoTrigger = AutoTrigger.TIME,
    val autoIntervalS: Int = 5,
    val autoDistanceM: Double = 1.0,
) {
    /** El tiempo de promediado dentro de [AVERAGING_SECONDS]. */
    val validAveragingSeconds: Int get() = averagingSeconds.coerceIn(AVERAGING_SECONDS)

    /** El intervalo del disparo por tiempo dentro de [AUTO_INTERVAL_SECONDS]. */
    val validAutoIntervalS: Int get() = autoIntervalS.coerceIn(AUTO_INTERVAL_SECONDS)

    /** La distancia del disparo por distancia dentro de [AUTO_DISTANCE_M]. */
    val validAutoDistanceM: Double get() = autoDistanceM.coerceIn(AUTO_DISTANCE_M)

    companion object {
        /** S010/RF-9, ampliado a 60 min en S017/RF-1. */
        val AVERAGING_SECONDS = 1..3600

        /** S017/RF-16. */
        val AUTO_INTERVAL_SECONDS = 1..3600

        /** S017/RF-17: en metros, sea cual sea la unidad del proyecto. */
        val AUTO_DISTANCE_M = 0.1..1000.0

        /** Los milímetros que se perdonan al límite al escribirlo redondeado en pies: 0,328 ft son 0,09997 m. */
        private const val ROUNDING_MARGIN_M = 0.001

        /** Los límites de la distancia en la unidad del proyecto, para enseñarlos (S017/RF-17). */
        fun autoDistanceRangeIn(unit: LinearUnit): ClosedFloatingPointRange<Double> =
            unit.fromMetres(AUTO_DISTANCE_M.start)..unit.fromMetres(AUTO_DISTANCE_M.endInclusive)

        /** La distancia escrita en la unidad del proyecto, en metros; null si no es un número o está fuera de límites. */
        fun parseAutoDistance(text: String, unit: LinearUnit): Double? {
            val metres = NumberInput.parse(text)?.let(unit::toMetres) ?: return null
            return when {
                metres in AUTO_DISTANCE_M -> metres
                metres < AUTO_DISTANCE_M.start && metres >= AUTO_DISTANCE_M.start - ROUNDING_MARGIN_M -> AUTO_DISTANCE_M.start
                metres > AUTO_DISTANCE_M.endInclusive && metres <= AUTO_DISTANCE_M.endInclusive + ROUNDING_MARGIN_M ->
                    AUTO_DISTANCE_M.endInclusive
                else -> null
            }
        }
    }
}
