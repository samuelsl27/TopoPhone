package es.topophone.core.survey

import es.topophone.core.model.point.AutoTrigger
import kotlin.math.hypot
import kotlin.math.max

/** El suelo de una época en el SC del proyecto, en planta y en metros: lo que mide la distancia del disparo (S017/RF-21). */
data class GroundEN(val east: Double, val north: Double)

/** Por qué el modo automático omite un punto que él mismo ve (S017/RF-24); los demás los cuenta quien mide, con su causa. */
enum class OmitCause { NOT_FINISHED, }

/** Lo que el modo automático pide a quien mide (S017/RF-19, RF-24). */
sealed interface AutoAction {
    /** Empezar un punto nuevo. */
    data object StartPoint : AutoAction

    /** Descartar el punto en curso, que no terminó antes del siguiente disparo; [warn] si hay que avisar (una vez). */
    data class OmitCurrent(val cause: OmitCause, val warn: Boolean) : AutoAction
}

/** Cómo acabó el punto que pidió [AutoAction.StartPoint] (S017/RF-24, RF-25). */
sealed interface AutoOutcome {
    /** Guardado; su Este y Norte, para el disparo por distancia. */
    data class Saved(val east: Double?, val north: Double?) : AutoOutcome

    /** Omitido por [cause] (el control de precisión, un fallo de la medida…). */
    data class Omitted(val cause: String) : AutoOutcome
}

/** Si hay que avisar de la omisión (S017/RF-24: una sola vez hasta que se guarda un punto). */
data class AutoOutcomeResult(val warn: Boolean)

/** Lo que dice el modo automático al pararlo (S017/RF-27). */
data class AutoSummary(val saved: Int, val omitted: Int)

/**
 * El modo automático (S017/RF-15 a RF-28; plan A5): decide cuándo medir un punto, por tiempo o por distancia, y lleva la
 * cuenta de lo guardado y lo omitido. No mide, no guarda y no sabe de Android: quien lo usa le da las épocas y cómo
 * acaba cada punto, y hace lo que pide.
 *
 * El tiempo es el de la hora UTC de las épocas (RNF-T10), así que una reproducción a ×5 da los intervalos de verdad.
 * Un salto atrás de menos de medio día (el bucle de una reproducción) no suma tiempo, y el de la medianoche sí.
 *
 * Con el modo en marcha, el disparo y su intervalo o distancia no cambian (S017/RF-32): se dan al crearlo.
 */
class AutoMeasure(private val trigger: AutoTrigger, intervalS: Int, private val distanceM: Double) {
    private val intervalMs = intervalS * MS_PER_S

    var running = false
        private set
    var paused = false
        private set

    /** Hay un punto midiéndose que aún no ha dado su resultado. */
    var inProgress = false
        private set
    var saved = 0
        private set
    var omitted = 0
        private set

    private var clockMs = 0L
    private var lastUtc: Int? = null
    private var lastTriggerMs = 0L
    private var lastSaved: GroundEN? = null
    private var attemptStart: GroundEN? = null
    private var retryPending = false
    private var omittedStretch = false
    private var warned = false

    /** Empieza el modo con la última época a la hora [utcMillisOfDay]: el primer punto, de inmediato (S017/RF-19). */
    fun start(utcMillisOfDay: Int, ground: GroundEN? = null): List<AutoAction> {
        running = true
        paused = false
        saved = 0
        omitted = 0
        clockMs = 0
        lastUtc = utcMillisOfDay
        lastTriggerMs = 0
        lastSaved = null
        retryPending = false
        omittedStretch = false
        warned = false
        return listOf(startPoint(ground))
    }

    /** Una época a la hora UTC [utcMillisOfDay], con su suelo si se pudo calcular; lo que hay que hacer con ella. */
    fun onEpoch(utcMillisOfDay: Int, ground: GroundEN?): List<AutoAction> {
        if (!running) return emptyList()
        advance(utcMillisOfDay)
        if (paused) return emptyList()
        if (inProgress && attemptStart == null && ground != null) attemptStart = ground
        val due = when (trigger) {
            AutoTrigger.TIME -> clockMs - lastTriggerMs >= intervalMs
            AutoTrigger.DISTANCE -> distanceDue(ground)
        }
        if (!due) return emptyList()
        val actions = mutableListOf<AutoAction>()
        if (inProgress) {
            // S017/RF-24: no terminó antes del siguiente disparo.
            actions += AutoAction.OmitCurrent(OmitCause.NOT_FINISHED, warn = registerOmission())
        }
        actions += startPoint(ground)
        return actions
    }

    /** El resultado del punto que pidió [AutoAction.StartPoint] (S017/RF-24, RF-25). */
    fun onPointOutcome(outcome: AutoOutcome): AutoOutcomeResult {
        if (!running || !inProgress) return AutoOutcomeResult(warn = false)
        inProgress = false
        return when (outcome) {
            is AutoOutcome.Saved -> {
                saved++
                warned = false
                omittedStretch = false
                retryPending = false
                val east = outcome.east
                val north = outcome.north
                if (east != null && north != null) lastSaved = GroundEN(east, north)
                AutoOutcomeResult(warn = false)
            }
            is AutoOutcome.Omitted -> AutoOutcomeResult(warn = registerOmission())
        }
    }

    /**
     * El punto en curso se pierde sin culpa del punto, por un corte (S017/RF-28): no cuenta como omitido y el siguiente
     * disparo lo decide [resume].
     */
    fun onPointCancelled() {
        if (running) inProgress = false
    }

    /** Hay un corte (S017/RF-28): no se mide hasta [resume]. */
    fun pause() {
        if (running) paused = true
    }

    /**
     * Han vuelto las épocas, la primera a la hora [utcMillisOfDay] (S017/RF-28): por tiempo, el intervalo se cuenta de nuevo
     * desde ahora; por distancia, si ya se cumple sale un único punto, con la siguiente época.
     */
    fun resume(utcMillisOfDay: Int) {
        if (!running || !paused) return
        advance(utcMillisOfDay)
        paused = false
        lastTriggerMs = clockMs
    }

    /** Para el modo y dice cuántos puntos se guardaron y se omitieron (S017/RF-27). */
    fun stop(): AutoSummary {
        running = false
        paused = false
        inProgress = false
        return AutoSummary(saved, omitted)
    }

    /** Segundos hasta el siguiente disparo por tiempo (S017/RF-26); null por distancia o sin el modo en marcha. */
    fun nextInS(): Double? {
        if (!running || paused || trigger != AutoTrigger.TIME) return null
        return max(0L, intervalMs - (clockMs - lastTriggerMs)) / MS_PER_S.toDouble()
    }

    /** Metros que faltan hasta el siguiente disparo por distancia (S017/RF-26); null por tiempo, sin suelo o sin punto guardado. */
    fun distanceToNextM(ground: GroundEN?): Double? {
        val reference = lastSaved
        if (!running || trigger != AutoTrigger.DISTANCE || ground == null || reference == null) return null
        return max(0.0, distanceM - hypot(ground.east - reference.east, ground.north - reference.north))
    }

    private fun startPoint(ground: GroundEN?): AutoAction {
        inProgress = true
        lastTriggerMs = clockMs
        attemptStart = ground
        retryPending = false
        return AutoAction.StartPoint
    }

    /** Por distancia toca si hay un punto en curso y el suelo ha avanzado esa distancia, o si no hay y está lejos del guardado. */
    private fun distanceDue(ground: GroundEN?): Boolean {
        if (ground == null) return false
        val reference = (if (inProgress) attemptStart else lastSaved)
        if (!inProgress && (retryPending || reference == null)) return true
        if (reference == null) return false
        return hypot(ground.east - reference.east, ground.north - reference.north) >= distanceM
    }

    /** Cuenta una omisión: por distancia, una racha seguida cuenta como una (S017/RF-25); true si hay que avisar. */
    private fun registerOmission(): Boolean {
        if (trigger == AutoTrigger.DISTANCE) {
            if (!omittedStretch) omitted++
            omittedStretch = true
            retryPending = true
        } else {
            omitted++
        }
        val warn = !warned
        warned = true
        return warn
    }

    private fun advance(utcMillisOfDay: Int) {
        val previous = lastUtc
        lastUtc = utcMillisOfDay
        if (previous == null) return
        val delta = utcMillisOfDay - previous
        clockMs += when {
            delta >= 0 -> delta.toLong()
            delta < -HALF_DAY_MS -> delta + DAY_MS.toLong()
            else -> 0L // una reproducción que vuelve a empezar
        }
    }

    private companion object {
        const val MS_PER_S = 1000L
        const val DAY_MS = 86_400_000
        const val HALF_DAY_MS = DAY_MS / 2
    }
}
