package es.topophone.core.model.point

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
        /** S010/RF-9; S017 lo amplía a 60 min. */
        val AVERAGING_SECONDS = 1..60
    }
}
