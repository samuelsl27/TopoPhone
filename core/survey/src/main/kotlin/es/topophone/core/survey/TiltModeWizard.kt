package es.topophone.core.survey

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.TiltMode
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Lo que falta para empezar la prueba del asistente (S016/RF-5). */
enum class WizardMissing { FIX, TILT }

/** Por qué la prueba no es concluyente (S016/RF-9). */
enum class WizardInconclusive {
    /** La GGA no se ha movido como en ningún modo. */
    MISMATCH,

    /** Se ha perdido el FIX o el estado 30, o ha llegado un aviso del ETC. */
    LOST,

    /** En 60 s el jalón no llegó a 15° con 20 cm de movimiento esperado. */
    TIMEOUT,
}

/** El paso del asistente en que se está (S016/RF-5 a RF-9; plan A7). */
sealed interface WizardState {
    /** Antes de empezar: lo que falta, vacío si se puede empezar (S016/RF-5). */
    data class Ready(val missing: Set<WizardMissing>) : WizardState

    /** Con la punta quieta y el jalón a menos de 5°: [progressS] de 1 s (S016/RF-6). */
    data class Reference(val progressS: Double) : WizardState

    /**
     * Hay que inclinar el jalón: [tiltRad] y el movimiento esperado de la antena [expectedM]. [tiltMore] si ya pasa de
     * 15° pero el movimiento esperado no llega a 20 cm (S016/RF-6).
     */
    data class Tilt(val tiltRad: Double, val expectedM: Double, val tiltMore: Boolean) : WizardState

    /** Inclinado 15° o más con 20 cm de movimiento esperado: [progressS] de 1 s. */
    data class Hold(val progressS: Double) : WizardState

    /** El modo propuesto, con el movimiento de la GGA y el esperado sin compensar (S016/RF-7, RF-8). */
    data class Proposed(val mode: TiltMode, val movedM: Double, val expectedM: Double) : WizardState

    data class Inconclusive(val reason: WizardInconclusive) : WizardState
}

/**
 * La prueba del asistente del modo de inclinación (S016/RF-5 a RF-9; plan A7): con la punta quieta, se toma la
 * posición con el jalón vertical y luego inclinado. Si la GGA sigue a la antena, el receptor no compensa; si no se
 * mueve, compensa. [antennaHeightM] es la altura del jalón de la app más 0,134 m.
 */
class TiltModeWizard(private val antennaHeightM: Double) {

    var state: WizardState = WizardState.Ready(WizardMissing.entries.toSet())
        private set

    private var missing: Set<WizardMissing> = WizardMissing.entries.toSet()
    private var startedAtMs: Long? = null
    private var lastUtc: Int? = null
    private var intervalS = DEFAULT_INTERVAL_S
    private val referenceSamples = mutableListOf<Sample>()
    private val holdSamples = mutableListOf<Sample>()
    private var reference: Sample? = null

    /** Una época completa del receptor, a las [nowMs] del reloj del móvil. */
    fun onEpoch(epoch: Epoch, nowMs: Long): WizardState {
        updateInterval(epoch.utcMillisOfDay)
        val gga = epoch.gga
        val fix = gga?.takeIf { it.hasPosition && it.status == SolutionStatus.FIX }
        missing = buildSet {
            if (fix == null) add(WizardMissing.FIX)
            if (!TiltEpoch.isClean(epoch.tilt)) add(WizardMissing.TILT)
        }
        when (state) {
            is WizardState.Ready -> state = WizardState.Ready(missing)
            is WizardState.Proposed, is WizardState.Inconclusive -> Unit
            else -> state = running(epoch, nowMs)
        }
        return state
    }

    /** Sin épocas también se acaba el plazo de 60 s (S016/RF-9). */
    fun onTick(nowMs: Long): WizardState {
        val started = startedAtMs
        if (isRunning() && started != null && nowMs - started >= TIMEOUT_MS) {
            state = WizardState.Inconclusive(WizardInconclusive.TIMEOUT)
        }
        return state
    }

    /** Empieza la prueba si no falta nada (S016/RF-5, RF-6). */
    fun start(nowMs: Long): Boolean {
        val ready = state as? WizardState.Ready ?: return false
        if (ready.missing.isNotEmpty()) return false
        startedAtMs = nowMs
        referenceSamples.clear()
        holdSamples.clear()
        reference = null
        state = WizardState.Reference(0.0)
        return true
    }

    /** "Repetir": vuelve a antes de empezar (S016/RF-9). */
    fun reset() {
        startedAtMs = null
        state = WizardState.Ready(missing)
    }

    private fun isRunning() = state is WizardState.Reference || state is WizardState.Tilt || state is WizardState.Hold

    private fun running(epoch: Epoch, nowMs: Long): WizardState {
        val started = checkNotNull(startedAtMs)
        if (nowMs - started >= TIMEOUT_MS) return WizardState.Inconclusive(WizardInconclusive.TIMEOUT)
        if (missing.isNotEmpty()) return WizardState.Inconclusive(WizardInconclusive.LOST)
        val sample = sample(epoch)
        val ref = reference ?: return referencePhase(sample)
        val expected = sample.antennaEast - ref.antennaEast to sample.antennaNorth - ref.antennaNorth
        val expectedM = hypot(expected.first, expected.second)
        val tilted = sample.tiltRad >= TEST_TILT_RAD
        if (!tilted || expectedM < MIN_EXPECTED_M) {
            holdSamples.clear()
            return WizardState.Tilt(sample.tiltRad, expectedM, tiltMore = tilted)
        }
        holdSamples += sample
        val span = span(holdSamples)
        return if (span < ONE_SECOND_S - EPSILON_S) WizardState.Hold(span) else decide(ref)
    }

    private fun referencePhase(sample: Sample): WizardState {
        if (sample.tiltRad >= REFERENCE_TILT_RAD) {
            referenceSamples.clear()
            return WizardState.Reference(0.0)
        }
        referenceSamples += sample
        val span = span(referenceSamples)
        if (span < ONE_SECOND_S - EPSILON_S) return WizardState.Reference(span)
        reference = mean(referenceSamples)
        return WizardState.Tilt(sample.tiltRad, 0.0, tiltMore = false)
    }

    /** RF-7 y RF-8: con |ē| ≥ 20 cm y |m| < 5 cm, nunca se cumplen las dos (plan A7.5). */
    private fun decide(ref: Sample): WizardState {
        val tilted = mean(holdSamples)
        val moved = LocalTangentPlane.between(ref.position, tilted.position)
        val expectedEast = tilted.antennaEast - ref.antennaEast
        val expectedNorth = tilted.antennaNorth - ref.antennaNorth
        val movedM = moved.horizontalM
        val expectedM = hypot(expectedEast, expectedNorth)
        return when {
            movedM < STILL_M -> WizardState.Proposed(TiltMode.RECEIVER, movedM, expectedM)
            hypot(moved.east - expectedEast, moved.north - expectedNorth) < MATCH_FRACTION * expectedM ->
                WizardState.Proposed(TiltMode.APP, movedM, expectedM)
            else -> WizardState.Inconclusive(WizardInconclusive.MISMATCH)
        }
    }

    private fun sample(epoch: Epoch): Sample {
        val gga = checkNotNull(epoch.gga)
        val tilt = checkNotNull(epoch.tilt)
        val theta = checkNotNull(tilt.tiltRad)
        val alpha = checkNotNull(tilt.tiltDirectionRad)
        val h = gga.altitudeM?.let { a -> gga.geoidSeparationM?.let { a + it } } ?: 0.0
        return Sample(
            position = GeodeticPoint(gga.latitudeDeg, gga.longitudeDeg, h),
            antennaEast = antennaHeightM * sin(theta) * sin(alpha),
            antennaNorth = antennaHeightM * sin(theta) * cos(alpha),
            tiltRad = theta,
            utcMs = epoch.utcMillisOfDay,
        )
    }

    private fun mean(samples: List<Sample>) = Sample(
        position = GeodeticPoint(
            samples.map { it.position.latitudeDeg }.average(),
            samples.map { it.position.longitudeDeg }.average(),
            samples.map { it.position.ellipsoidalHeightM }.average(),
        ),
        antennaEast = samples.map { it.antennaEast }.average(),
        antennaNorth = samples.map { it.antennaNorth }.average(),
        tiltRad = samples.map { it.tiltRad }.average(),
        utcMs = samples.last().utcMs,
    )

    /** Tiempo que cubren unas épocas seguidas: de la primera a la última más un intervalo. */
    private fun span(samples: List<Sample>): Double = (samples.last().utcMs - samples.first().utcMs) / MS_PER_S + intervalS

    private fun updateInterval(utc: Int) {
        lastUtc?.let { previous ->
            val delta = (utc - previous) / MS_PER_S
            if (delta > 0) intervalS = delta.coerceIn(MIN_INTERVAL_S, DEFAULT_INTERVAL_S)
        }
        lastUtc = utc
    }

    /** Una época de la prueba: la GGA y el vector en planta de la punta a la antena según el ETC. */
    private data class Sample(
        val position: GeodeticPoint,
        val antennaEast: Double,
        val antennaNorth: Double,
        val tiltRad: Double,
        val utcMs: Int,
    )

    private companion object {
        val REFERENCE_TILT_RAD = Math.toRadians(5.0)
        val TEST_TILT_RAD = Math.toRadians(15.0)
        const val MIN_EXPECTED_M = 0.20
        const val STILL_M = 0.05
        const val MATCH_FRACTION = 0.25
        const val ONE_SECOND_S = 1.0
        const val TIMEOUT_MS = 60_000L
        const val DEFAULT_INTERVAL_S = 1.0
        const val MIN_INTERVAL_S = 0.05
        const val MS_PER_S = 1_000.0
        const val EPSILON_S = 1e-9
    }
}
