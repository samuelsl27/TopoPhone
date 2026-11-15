package es.topophone.core.model.guide

/** Lo que mira una comprobación previa de las épocas del receptor (S043/RF-6, RF-7, RF-8). */
enum class CheckId {
    GGA_RATE,
    SENTENCES,
    SOLUTION,
    CORRECTION_AGE,
    UTC_TIME,
    TILT,
    RTCM_BASE,
    RTCM_OBSERVATIONS,
    RTCM_GLONASS,
    DATUM,
}

/** El resultado de una comprobación; nunca impide medir (S043, términos). [NO_DATA] es «Sin datos» (RF-10). */
enum class CheckStatus { OK, WARNING, FAIL, NO_DATA }

/** La causa probable de un aviso o un fallo (S043/RF-9); el texto de cada una está en los recursos de la app. */
enum class CheckCause {
    NO_DATA,
    NO_GGA,
    GGA_BELOW_1HZ,
    GGA_SLOWER_THAN_GNSS,
    MISSING_GST,
    MISSING_GSV,
    MISSING_ZDA,
    MISSING_RMC,
    NO_POSITION,
    SOLUTION_NOT_FIX,
    CORRECTIONS_OLD,
    CORRECTIONS_STALE,
    NO_TIME,
    TIME_NOT_ADVANCING,
    TILT_NOT_COMPENSATING,
    TILT_NOTICE,
    TILT_MODE_UNSET,
    TILT_MODE_UNVERIFIED,
    NO_BASE_MESSAGES,
    NO_OBSERVATIONS,
    NO_GLONASS,
    DATUM_MISMATCH,
    DATUM_UNKNOWN,
}

/**
 * El resultado de una comprobación: [measured] es el valor medido para enseñarlo (por ejemplo «5,0 Hz»), [causes] las causas probables
 * si no es correcta y [stepId] el paso de la lista que la resuelve (S043/RF-9).
 */
data class ReceiverCheck(
    val id: CheckId,
    val status: CheckStatus,
    val measured: String? = null,
    val causes: List<CheckCause> = emptyList(),
    val stepId: String? = null,
)
