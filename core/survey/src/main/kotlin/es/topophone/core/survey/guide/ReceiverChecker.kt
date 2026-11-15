package es.topophone.core.survey.guide

import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.gnss.rtcm.RtcmKind
import es.topophone.core.gnss.rtcm.RtcmMessages
import es.topophone.core.gnss.rtcm.RtcmSystem
import es.topophone.core.model.guide.CheckCause
import es.topophone.core.model.guide.CheckId
import es.topophone.core.model.guide.CheckStatus
import es.topophone.core.model.guide.ReceiverCheck
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.CorrectionsDatum
import java.util.Locale

/**
 * Lo que el comprobador sabe fuera de las épocas (S043/RF-7, RF-8): si hay un NTRIP abierto, el modo de inclinación del receptor y si
 * está comprobado (S016/RF-2, RF-10), el datum de las correcciones que declara el proyecto (S006) y el año ITRF de la última base de las
 * correcciones: `null` si no ha llegado ningún 1005 o 1006 y 0 si ha llegado sin declarar el año.
 */
data class CheckContext(
    val ntripOpen: Boolean = false,
    val tiltMode: TiltMode? = null,
    val tiltVerified: Boolean = false,
    val projectDatum: CorrectionsDatum? = null,
    val itrfYear: Int? = null,
)

/**
 * Las comprobaciones previas de lo que llega del receptor, en las últimas épocas (S043/RF-5 a RF-10, plan D1, D3 y A2). Puro, sin
 * Android: no escribe nada en el receptor y nunca impide medir (RF-11). [stepFor] da, para una comprobación, el paso de la lista que la
 * resuelve (RF-9); sin él, no se indica ninguno.
 *
 * Con la ventana vacía todas salen «Sin datos» (RF-10). Las frecuencias se miden con la hora UTC de las épocas, no con la de
 * llegada: así una reproducción a ×5 da la frecuencia de la grabación.
 */
class ReceiverChecker(private val stepFor: (CheckId) -> String? = { null }) {

    /** Las comprobaciones que aplican, en un orden fijo: las de NMEA, la de inclinación si hay ETC y las de RTCM si hay NTRIP. */
    fun check(window: WindowSnapshot, context: CheckContext = CheckContext()): List<ReceiverCheck> {
        val epochs = window.epochs
        val nmea = if (epochs.isEmpty()) {
            nmeaChecks(context).map { noData(it, CheckCause.NO_DATA) }
        } else {
            buildList {
                add(ggaRate(epochs))
                add(sentences(epochs))
                add(solution(epochs))
                correctionAge(epochs, context)?.let(::add)
                add(utcTime(epochs))
                tilt(epochs, context)?.let(::add)
            }
        }
        val rtcm = if (context.ntripOpen) rtcmChecks(window, context) else emptyList()
        return (nmea + rtcm).map { check ->
            if (check.status == CheckStatus.OK) check.copy(stepId = null) else check.copy(stepId = check.stepId ?: stepFor(check.id))
        }
    }

    private fun nmeaChecks(context: CheckContext): List<CheckId> = buildList {
        add(CheckId.GGA_RATE)
        add(CheckId.SENTENCES)
        add(CheckId.SOLUTION)
        if (context.ntripOpen) add(CheckId.CORRECTION_AGE)
        add(CheckId.UTC_TIME)
    }

    private fun noData(id: CheckId, cause: CheckCause) =
        ReceiverCheck(id, CheckStatus.NO_DATA, causes = listOf(cause), stepId = stepFor(id))

    // ----- GGA_RATE -----

    private fun ggaRate(epochs: List<Epoch>): ReceiverCheck {
        val ggaTimes = epochs.filter { it.gga != null }.map { it.utcMillisOfDay }
        if (ggaTimes.isEmpty()) return ReceiverCheck(CheckId.GGA_RATE, CheckStatus.FAIL, causes = listOf(CheckCause.NO_GGA))
        val rate = rateHz(ggaTimes, spanOf(epochs))
            ?: return ReceiverCheck(CheckId.GGA_RATE, CheckStatus.NO_DATA, causes = listOf(CheckCause.NO_DATA))
        val measured = hz(rate)
        if (rate < MIN_GGA_HZ) {
            return ReceiverCheck(CheckId.GGA_RATE, CheckStatus.WARNING, measured, listOf(CheckCause.GGA_BELOW_1HZ))
        }
        // El GNSS puede ir más deprisa que la salida de posición: otra frase con hora llega al menos al doble de la GGA.
        val others = listOf<(Epoch) -> Boolean>({ it.gst != null }, { it.rmc != null }, { it.zda != null }, { it.tilt != null })
            .mapNotNull { has -> rateHz(epochs.filter(has).map { it.utcMillisOfDay }, spanOf(epochs)) }
        if (others.any { it >= SLOWER_FACTOR * rate }) {
            return ReceiverCheck(CheckId.GGA_RATE, CheckStatus.WARNING, measured, listOf(CheckCause.GGA_SLOWER_THAN_GNSS))
        }
        return ReceiverCheck(CheckId.GGA_RATE, CheckStatus.OK, measured)
    }

    // ----- SENTENCES -----

    private fun sentences(epochs: List<Epoch>): ReceiverCheck {
        val present = buildList {
            if (epochs.any { it.gst != null }) add("GST")
            if (epochs.any { it.gsv.isNotEmpty() }) add("GSV")
            if (epochs.any { it.zda != null }) add("ZDA")
            if (epochs.any { it.rmc != null }) add("RMC")
        }
        val missing = listOf(
            "GST" to CheckCause.MISSING_GST,
            "GSV" to CheckCause.MISSING_GSV,
            "ZDA" to CheckCause.MISSING_ZDA,
            "RMC" to CheckCause.MISSING_RMC,
        ).filter { (name, _) -> name !in present }
        val measured = present.joinToString(", ").ifEmpty { null }
        return if (missing.isEmpty()) {
            ReceiverCheck(CheckId.SENTENCES, CheckStatus.OK, measured)
        } else {
            ReceiverCheck(CheckId.SENTENCES, CheckStatus.WARNING, measured, missing.map { it.second })
        }
    }

    // ----- SOLUTION -----

    private fun solution(epochs: List<Epoch>): ReceiverCheck {
        val gga = epochs.lastOrNull { it.gga != null }?.gga
            ?: return ReceiverCheck(CheckId.SOLUTION, CheckStatus.NO_DATA, causes = listOf(CheckCause.NO_GGA))
        return when (gga.status) {
            SolutionStatus.NONE -> ReceiverCheck(CheckId.SOLUTION, CheckStatus.FAIL, gga.status.name, listOf(CheckCause.NO_POSITION))
            SolutionStatus.FIX -> ReceiverCheck(CheckId.SOLUTION, CheckStatus.OK, gga.status.name)
            else -> ReceiverCheck(CheckId.SOLUTION, CheckStatus.WARNING, gga.status.name, listOf(CheckCause.SOLUTION_NOT_FIX))
        }
    }

    // ----- CORRECTION_AGE -----

    private fun correctionAge(epochs: List<Epoch>, context: CheckContext): ReceiverCheck? {
        val age = epochs.lastOrNull { it.gga != null }?.gga?.correctionAgeS
        if (age == null) {
            // Sin NTRIP ni edad no hay correcciones que mirar; con el NTRIP abierto, no están llegando al receptor.
            val stale = ReceiverCheck(CheckId.CORRECTION_AGE, CheckStatus.FAIL, causes = listOf(CheckCause.CORRECTIONS_STALE))
            return if (context.ntripOpen) stale else null
        }
        val measured = String.format(Locale.ROOT, "%.1f s", age)
        return when {
            age <= CORRECTIONS_OK_S -> ReceiverCheck(CheckId.CORRECTION_AGE, CheckStatus.OK, measured)
            age <= CORRECTIONS_WARNING_S ->
                ReceiverCheck(CheckId.CORRECTION_AGE, CheckStatus.WARNING, measured, listOf(CheckCause.CORRECTIONS_OLD))
            else -> ReceiverCheck(CheckId.CORRECTION_AGE, CheckStatus.FAIL, measured, listOf(CheckCause.CORRECTIONS_STALE))
        }
    }

    // ----- UTC_TIME -----

    private fun utcTime(epochs: List<Epoch>): ReceiverCheck {
        val times = epochs.map { it.utcMillisOfDay }
        // Una bajada de más de 1 s que no es el paso de medianoche: la hora del receptor no avanza con normalidad.
        val goesBack = times.zipWithNext().any { (a, b) -> b < a && a - b < HALF_DAY_MS && a - b > BACKWARD_TOLERANCE_MS }
        if (goesBack) return ReceiverCheck(CheckId.UTC_TIME, CheckStatus.FAIL, causes = listOf(CheckCause.TIME_NOT_ADVANCING))
        val date = epochs.lastOrNull { it.date != null }?.date
            ?: return ReceiverCheck(CheckId.UTC_TIME, CheckStatus.WARNING, causes = listOf(CheckCause.NO_TIME))
        return ReceiverCheck(CheckId.UTC_TIME, CheckStatus.OK, date.toString())
    }

    // ----- TILT -----

    private fun tilt(epochs: List<Epoch>, context: CheckContext): ReceiverCheck? {
        // Sin ETC el receptor no está inclinado: no hay nada que mirar.
        val data = epochs.lastOrNull { it.tilt != null }?.tilt ?: return null
        val causes = buildList {
            if (data.state != TiltState.COMPENSATING) add(CheckCause.TILT_NOT_COMPENSATING)
            if (data.notification != TiltNotification.NONE && data.notification != TiltNotification.OFF) add(CheckCause.TILT_NOTICE)
            if (context.tiltMode == null) {
                add(CheckCause.TILT_MODE_UNSET)
            } else if (!context.tiltVerified) {
                add(CheckCause.TILT_MODE_UNVERIFIED)
            }
        }
        val status = when {
            data.state == TiltState.FATAL || data.state == TiltState.OFF -> CheckStatus.FAIL
            causes.isNotEmpty() -> CheckStatus.WARNING
            else -> CheckStatus.OK
        }
        return ReceiverCheck(CheckId.TILT, status, data.state.name, causes)
    }

    // ----- RTCM y datum -----

    private fun rtcmChecks(window: WindowSnapshot, context: CheckContext): List<ReceiverCheck> {
        val delta = window.rtcmDelta
            ?: return listOf(CheckId.RTCM_BASE, CheckId.RTCM_OBSERVATIONS).map { noData(it, CheckCause.NO_DATA) }
        val kinds = delta.keys.associateWith { RtcmMessages.kind(it) }
        val base = delta.keys.filter { kinds[it] == RtcmKind.ReferenceStation }.sorted()
        val observations = delta.keys.filter { fullObservation(kinds[it]) }.sorted()
        val glonass = observations.filter { observationSystem(kinds[it]) == RtcmSystem.GLONASS }
        return buildList {
            add(
                if (base.isEmpty()) {
                    ReceiverCheck(CheckId.RTCM_BASE, CheckStatus.FAIL, causes = listOf(CheckCause.NO_BASE_MESSAGES))
                } else {
                    ReceiverCheck(CheckId.RTCM_BASE, CheckStatus.OK, base.joinToString(", "))
                },
            )
            add(
                if (observations.isEmpty()) {
                    ReceiverCheck(CheckId.RTCM_OBSERVATIONS, CheckStatus.FAIL, causes = listOf(CheckCause.NO_OBSERVATIONS))
                } else {
                    ReceiverCheck(CheckId.RTCM_OBSERVATIONS, CheckStatus.OK, observations.joinToString(", "))
                },
            )
            if (glonassInView(window.epochs)) {
                add(
                    if (glonass.isEmpty()) {
                        ReceiverCheck(CheckId.RTCM_GLONASS, CheckStatus.WARNING, causes = listOf(CheckCause.NO_GLONASS))
                    } else {
                        ReceiverCheck(CheckId.RTCM_GLONASS, CheckStatus.OK, glonass.joinToString(", "))
                    },
                )
            }
            datum(context)?.let(::add)
        }
    }

    /** Observaciones completas: las antiguas de GPS y GLONASS y los MSM 4 a 7 (los 1 a 3 no llevan fase). */
    private fun fullObservation(kind: RtcmKind?): Boolean =
        kind is RtcmKind.LegacyObservables || (kind is RtcmKind.Msm && kind.level >= MIN_FULL_MSM_LEVEL)

    private fun observationSystem(kind: RtcmKind?): RtcmSystem? = when (kind) {
        is RtcmKind.LegacyObservables -> kind.system
        is RtcmKind.Msm -> kind.system
        else -> null
    }

    /** El receptor da satélites GLONASS en las GSV de la ventana: «si el receptor las usa» (RF-8). */
    private fun glonassInView(epochs: List<Epoch>) =
        epochs.any { epoch -> epoch.gsv.any { (key, view) -> key.constellation == Constellation.GLONASS && view.count > 0 } }

    /**
     * El datum de las correcciones con el año ITRF de 1005/1006 (plan D4): con año, ITRF/WGS84; sin año, no se puede saber. Sin 1005
     * o 1006 o sin proyecto no hay nada que comparar.
     */
    private fun datum(context: CheckContext): ReceiverCheck? {
        val year = context.itrfYear ?: return null
        val declared = context.projectDatum ?: return null
        return when {
            year == 0 -> ReceiverCheck(CheckId.DATUM, CheckStatus.WARNING, declared.name, listOf(CheckCause.DATUM_UNKNOWN))
            declared == CorrectionsDatum.ITRF_WGS84 -> ReceiverCheck(CheckId.DATUM, CheckStatus.OK, declared.name)
            else -> ReceiverCheck(CheckId.DATUM, CheckStatus.WARNING, declared.name, listOf(CheckCause.DATUM_MISMATCH))
        }
    }

    // ----- Cálculos -----

    /** La duración que cubren las épocas con la hora del receptor, en ms. */
    private fun spanOf(epochs: List<Epoch>): Long {
        val unwrapped = unwrap(epochs.map { it.utcMillisOfDay })
        return unwrapped.last() - unwrapped.first()
    }

    /**
     * Frecuencia de unas frases con hora [times] en ms del día: los intervalos entre la primera y la última; con una sola, la de una
     * en el tiempo [spanMs] que cubren todas las épocas si ya son al menos 5 s, y si no, no se sabe todavía.
     */
    private fun rateHz(times: List<Int>, spanMs: Long): Double? {
        if (times.size >= 2) {
            val unwrapped = unwrap(times)
            val span = unwrapped.last() - unwrapped.first()
            return if (span > 0) (times.size - 1) * MILLIS_PER_SECOND / span else null
        }
        return if (times.size == 1 && spanMs >= MIN_SINGLE_SPAN_MS) MILLIS_PER_SECOND / spanMs else null
    }

    /** Las horas en ms del día sin el salto de medianoche: crecientes aunque pasen de las 24 h. */
    private fun unwrap(times: List<Int>): List<Long> {
        var offset = 0L
        var previous = times.first()
        return times.map { time ->
            if (time < previous && previous - time > HALF_DAY_MS) offset += DAY_MS
            previous = time
            time + offset
        }
    }

    private fun hz(rate: Double) = String.format(Locale.ROOT, "%.1f Hz", rate)

    private companion object {
        const val MIN_FULL_MSM_LEVEL = 4
        const val MIN_GGA_HZ = 0.99
        const val SLOWER_FACTOR = 2.0
        const val CORRECTIONS_OK_S = 5.0
        const val CORRECTIONS_WARNING_S = 30.0
        const val MIN_SINGLE_SPAN_MS = 5_000L
        const val MILLIS_PER_SECOND = 1000.0
        const val BACKWARD_TOLERANCE_MS = 1_000
        const val HALF_DAY_MS = 43_200_000
        const val DAY_MS = 86_400_000L
    }
}
