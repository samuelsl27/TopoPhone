package es.topophone.app.ui.collector

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.Project
import es.topophone.core.survey.AutoAction
import es.topophone.core.survey.AutoMeasure
import es.topophone.core.survey.AutoOutcome
import es.topophone.core.survey.AutoOutcomeResult
import es.topophone.core.survey.AutoSummary
import es.topophone.core.survey.GroundEN
import es.topophone.core.survey.LiveTip
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.receiver.platform.Alerts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lo que se ve del modo automático (S017/RF-26, RF-27): si está en marcha o en pausa, los puntos guardados y omitidos y, al
 * pararlo, el resumen.
 */
data class AutoUiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val saved: Int = 0,
    val omitted: Int = 0,
    val summary: AutoSummary? = null,
)

/** Lo que falta para el siguiente punto: segundos por tiempo o metros por distancia (S017/RF-26). */
data class AutoLive(val nextInS: Double?, val nextDistanceM: Double?)

/**
 * Lleva el modo automático del recolector (S017/RF-15 a RF-28, RF-31; plan A5): la máquina de [AutoMeasure], sus avisos y
 * sus anotaciones en el log, y el suelo de cada época para el disparo por distancia. Medir, guardar y el control de
 * precisión los hace el recolector, que le dice cómo acaba cada punto.
 */
internal class AutoCollector(private val log: AppLog, private val alerts: Alerts) {
    private var auto: AutoMeasure? = null
    private var origin: GeodeticPoint? = null
    private val uiFlow = MutableStateFlow(AutoUiState())
    private val liveFlow = MutableStateFlow<AutoLive?>(null)

    val ui: StateFlow<AutoUiState> = uiFlow
    val live: StateFlow<AutoLive?> = liveFlow

    val running: Boolean get() = auto?.running == true
    val paused: Boolean get() = auto?.paused == true

    /** El primer punto, de inmediato, con la última época a la hora [utc] (S017/RF-19). */
    fun start(settings: CollectorSettings, utc: Int, ground: GroundEN?): List<AutoAction> {
        val measure = AutoMeasure(settings.autoTrigger, settings.validAutoIntervalS, settings.validAutoDistanceM)
        auto = measure
        origin = null
        log.info(LogCategory.POINTS, "Modo automático iniciado: ${trigger(settings)}") // S014/RF-5
        return measure.start(utc, ground).also { publish() }
    }

    /** Lo que hay que hacer con una época: empezar un punto u omitir el que no terminó (S017/RF-20, RF-21, RF-24). */
    fun onEpoch(utc: Int, ground: GroundEN?): List<AutoAction> {
        val measure = auto ?: return emptyList()
        val actions = measure.onEpoch(utc, ground)
        for (action in actions) {
            if (action is AutoAction.OmitCurrent) omitted("no terminó antes del siguiente disparo", action.warn)
        }
        if (actions.isNotEmpty()) publish()
        return actions
    }

    /** Cómo acabó el punto en curso (S017/RF-22, RF-24, RF-25). */
    fun onOutcome(outcome: AutoOutcome) {
        val measure = auto ?: return
        val result: AutoOutcomeResult = measure.onPointOutcome(outcome)
        if (outcome is AutoOutcome.Omitted) omitted(outcome.cause, result.warn)
        publish()
    }

    /** El punto en curso se perdió por un corte: no se cuenta como omitido (S017/RF-28). */
    fun onPointCancelled() {
        auto?.onPointCancelled()
    }

    /** Un corte (S017/RF-28); [alert] si no lo avisa ya la medida en curso. */
    fun pause(alert: Boolean) {
        val measure = auto ?: return
        if (measure.paused) return
        measure.pause()
        log.warning(LogCategory.POINTS, "Modo automático en pausa: sin datos del receptor") // S014/RF-5
        if (alert) alerts.measurementPaused()
        publish()
    }

    fun resume(utc: Int) {
        val measure = auto ?: return
        if (!measure.paused) return
        measure.resume(utc)
        log.info(LogCategory.POINTS, "Modo automático reanudado: han vuelto los datos del receptor") // S014/RF-5
        publish()
    }

    /** Para el modo y deja el resumen (S017/RF-27). */
    fun stop(): AutoSummary? {
        val measure = auto ?: return null
        val summary = measure.stop()
        auto = null
        log.info(LogCategory.POINTS, "Modo automático parado: ${summary.saved} guardados y ${summary.omitted} omitidos") // S014/RF-5
        uiFlow.value = AutoUiState(summary = summary)
        liveFlow.value = null
        return summary
    }

    /** Lo que falta para el siguiente punto, con el suelo de ahora (S017/RF-26). */
    fun refreshLive(ground: GroundEN?) {
        val measure = auto
        liveFlow.value = if (measure == null) null else AutoLive(measure.nextInS(), measure.distanceToNextM(ground))
    }

    /** El suelo de la época en planta, en el SC del proyecto, o en el plano local si es geográfico (S017/RF-21). */
    fun ground(epoch: Epoch, tiltMode: TiltMode?, poleHeightM: Double, project: Project, calculator: ProjectPositionCalculator): GroundEN? {
        val fix = epoch.gga?.takeIf { it.hasPosition } ?: return null
        val tip = LiveTip.of(fix, epoch.tilt, tiltMode, poleHeightM)?.ground ?: return null
        val position = calculator.fromGround(tip, project.crs, project.heightSystem)
        val east = position.easting
        val north = position.northing
        if (east != null && north != null) return GroundEN(east, north)
        // Un SC geográfico no tiene Este ni Norte: se mide en el plano tangente de la primera posición.
        val point = GeodeticPoint(tip.latitudeDeg, tip.longitudeDeg, tip.ellipsoidalHeightM ?: 0.0)
        val enu = LocalTangentPlane.between(origin ?: point.also { origin = it }, point)
        return GroundEN(enu.east, enu.north)
    }

    private fun omitted(cause: String, warn: Boolean) {
        log.warning(LogCategory.POINTS, "Punto omitido del modo automático: $cause") // S014/RF-5; S017/RF-31
        if (warn) alerts.pointOmitted() // S017/RF-24
    }

    private fun publish() {
        val measure = auto ?: return
        uiFlow.value = AutoUiState(measure.running, measure.paused, measure.saved, measure.omitted)
    }

    private fun trigger(settings: CollectorSettings): String = when (settings.autoTrigger) {
        AutoTrigger.TIME -> "cada ${settings.validAutoIntervalS} s"
        AutoTrigger.DISTANCE -> "cada ${settings.validAutoDistanceM} m"
    }
}
