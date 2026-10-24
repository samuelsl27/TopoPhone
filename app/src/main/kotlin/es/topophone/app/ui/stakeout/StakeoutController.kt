package es.topophone.app.ui.stakeout

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.Project
import es.topophone.core.survey.DianaScale
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.HeadingSource
import es.topophone.core.survey.LiveTip
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.ReceiverModel
import es.topophone.core.survey.StakeAlert
import es.topophone.core.survey.StakeZone
import es.topophone.core.survey.StakeoutGeometry
import es.topophone.core.survey.StakeoutZones
import es.topophone.core.survey.TiltEpoch
import es.topophone.core.survey.VerticalPole
import es.topophone.receiver.platform.Alerts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** El punto de diseño que se replantea (S018/RF-4). */
data class StakeTarget(val id: String, val name: String, val description: String, val coordinates: PointCoordinates)

/** Todo lo que la guía necesita saber de fuera con cada época: los ajustes, el punto, el control de precisión y el proyecto. */
class StakeContext(
    val settings: StakeoutSettings,
    val target: StakeTarget,
    val fixOnly: Boolean,
    val tiltMode: TiltMode?,
    val poleHeightM: Double,
    val project: Project,
    val calculator: ProjectPositionCalculator,
)

/** Por qué la guía está apagada (S018/RF-22, RF-24, RF-25, RF-35). */
enum class GuidanceOff { NO_POSITION, NO_DATA, NO_FIX, TILT_NOT_READY }

/**
 * Lo que se ve de la guía con una época (S018/RF-6 a RF-13, RF-15 a RF-17).
 *
 * @property tipEastM y [tipNorthM] la punta respecto al punto de diseño, para la diana.
 * @property dianaRadiusM el radio visible de la diana (A4).
 * @property horizontalAccuracyM la de la GST más la de la inclinación en planta si la punta se calcula en la app (S016/RF-21).
 */
data class GuidanceUi(
    val guidance: Guidance,
    val zone: StakeZone,
    val heading: HeadingResult,
    val tipEastM: Double,
    val tipNorthM: Double,
    val dianaRadiusM: Double,
    val solution: SolutionStatus,
    val horizontalAccuracyM: Double?,
    val tipUncompensated: Boolean,
    /** La punta en geográficas, para dibujarla en el mapa (S018/RF-14). */
    val tip: GeodeticPosition? = null,
)

sealed interface GuidanceState {
    data class On(val ui: GuidanceUi) : GuidanceState

    data class Off(val reason: GuidanceOff) : GuidanceState
}

/**
 * Lleva la guía del replanteo época a época (S018/A6): la punta del jalón, el rumbo del usuario, la geometría hasta el punto
 * de diseño, las zonas con sus avisos y las condiciones que la apagan. No sabe de pantallas: lo que se ve sale en [state].
 *
 * [publishIntervalMs] limita lo que se publica a 5 Hz (RNF-T13); un cambio de zona o de estado se publica al momento.
 */
class StakeoutController(
    private val alerts: Alerts,
    private val log: AppLog = AppLog.NONE,
    private val publishIntervalMs: Long = PUBLISH_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val flow = MutableStateFlow<GuidanceState>(GuidanceState.Off(GuidanceOff.NO_POSITION))
    val state: StateFlow<GuidanceState> = flow

    private var heading = HeadingSource()
    private var zone = StakeZone.FAR
    private var dianaRadius: Double? = null
    private var lastPublishedAtMs = Long.MIN_VALUE
    private var wasOn = false

    /** Una época nueva con lo que hay ahora fuera (S018/RF-5 a RF-25, RF-35). */
    @Synchronized
    fun onEpoch(epoch: Epoch, context: StakeContext) {
        val fix = epoch.gga?.takeIf { it.hasPosition }
        if (fix == null) {
            switchOff(GuidanceOff.NO_POSITION, context)
            return
        }
        if (context.fixOnly && fix.status != SolutionStatus.FIX) {
            switchOff(GuidanceOff.NO_FIX, context)
            return
        }
        if (context.tiltMode?.usesTilt == true && !TiltEpoch.isCompensating(epoch.tilt)) {
            switchOff(GuidanceOff.TILT_NOT_READY, context)
            return
        }
        val tip = LiveTip.of(fix, epoch.tilt, context.tiltMode, context.poleHeightM)
        if (tip == null) {
            switchOff(GuidanceOff.NO_POSITION, context)
            return
        }
        val position = context.calculator.fromGround(tip.ground, context.project.crs, context.project.heightSystem)
        val tipCoordinates = PointCoordinates(tip.ground, position.easting, position.northing, position.height)
        val design = context.target.coordinates

        // El rumbo de marcha sigue a la punta respecto al punto de diseño, que no se mueve: vale con cualquier SC.
        val toward = StakeoutGeometry.guidance(design, tipCoordinates, headingRad = null)
        val timeMs = epoch.utcMillisOfDay.toLong()
        heading.onTip(timeMs, -toward.dEastM, -toward.dNorthM)
        val userHeading = heading.heading(context.settings.heading, timeMs, epoch.tilt, position.convergenceRad)
        val guidance = StakeoutGeometry.guidance(design, tipCoordinates, (userHeading as? HeadingResult.Available)?.rad)

        val tolerance = context.settings.validToleranceM
        val step = StakeoutZones.next(zone, guidance.distanceM, tolerance)
        val zoneChanged = step.zone != zone
        zone = step.zone
        step.alert?.let { alert ->
            when (alert) {
                StakeAlert.APPROACH -> alerts.stakeoutApproach()
                StakeAlert.ARRIVED -> alerts.stakeoutArrived()
                StakeAlert.LEFT -> alerts.stakeoutLeft()
            }
            log.info(LogCategory.POINTS, "Replanteo de ${context.target.name}: ${alert.logText()}")
        }
        val radius = DianaScale.radius(dianaRadius, guidance.distanceM, tolerance).also { dianaRadius = it }
        val ui = GuidanceUi(
            guidance = guidance,
            zone = zone,
            heading = userHeading,
            tipEastM = -guidance.dEastM,
            tipNorthM = -guidance.dNorthM,
            dianaRadiusM = radius,
            solution = fix.status,
            horizontalAccuracyM = horizontalAccuracy(epoch, context, tip.uncompensated),
            tipUncompensated = tip.uncompensated,
            tip = tip.ground,
        )
        val becameOn = !wasOn
        wasOn = true
        publish(GuidanceState.On(ui), force = becameOn || zoneChanged)
    }

    /** Un corte ("Sin datos" o conexión perdida): la guía se apaga hasta que vuelvan las épocas (S018/RF-25). */
    @Synchronized
    fun onSilence(context: StakeContext? = null) {
        switchOff(GuidanceOff.NO_DATA, context)
    }

    /** Se cambia de punto de diseño o se cierra el replanteo: se empieza de cero (S018/RF-3, RF-5). */
    @Synchronized
    fun reset() {
        heading = HeadingSource()
        zone = StakeZone.FAR
        dianaRadius = null
        wasOn = false
        lastPublishedAtMs = Long.MIN_VALUE
        flow.value = GuidanceState.Off(GuidanceOff.NO_POSITION)
    }

    private fun switchOff(reason: GuidanceOff, context: StakeContext?) {
        val current = flow.value
        val alreadyOff = current is GuidanceState.Off && current.reason == reason
        if (wasOn && (reason == GuidanceOff.NO_FIX || reason == GuidanceOff.NO_DATA)) {
            alerts.solutionLost() // S010/RF-10; el mismo aviso para el FIX perdido y para el corte (S018/RF-22, RF-25)
        }
        if (!alreadyOff && wasOn) {
            val name = context?.target?.name?.let { " de $it" }.orEmpty()
            log.warning(LogCategory.POINTS, "Replanteo$name: guía apagada, ${reason.logText()}") // S014/RF-5
        }
        wasOn = false
        zone = StakeZone.FAR
        dianaRadius = null
        if (!alreadyOff) {
            flow.value = GuidanceState.Off(reason)
            lastPublishedAtMs = Long.MIN_VALUE
        }
    }

    private fun publish(state: GuidanceState, force: Boolean) {
        val time = now()
        if (force || publishIntervalMs <= 0 || time - lastPublishedAtMs >= publishIntervalMs) {
            flow.value = state
            lastPublishedAtMs = time
        }
    }

    /** La precisión horizontal de la GST, más la de la inclinación si la punta se calcula en la app (S010/RF-6, S016/RF-21). */
    private fun horizontalAccuracy(epoch: Epoch, context: StakeContext, uncompensated: Boolean): Double? {
        val gst = epoch.gst?.horizontalM ?: return null
        if (context.tiltMode != TiltMode.APP || uncompensated) return gst
        val extra = TiltEpoch.accuracy(
            epoch.tilt?.takeIf(TiltEpoch::isCompensating),
            VerticalPole.antennaHeightM(context.poleHeightM, ReceiverModel.RS3),
        )
        return gst + (extra?.horizontalM ?: 0.0)
    }

    private fun StakeAlert.logText() = when (this) {
        StakeAlert.APPROACH -> "entra en la zona de ayuda"
        StakeAlert.ARRIVED -> "dentro de la tolerancia"
        StakeAlert.LEFT -> "fuera de la tolerancia"
    }

    private fun GuidanceOff.logText() = when (this) {
        GuidanceOff.NO_POSITION -> "sin posición"
        GuidanceOff.NO_DATA -> "sin datos del receptor"
        GuidanceOff.NO_FIX -> "sin FIX"
        GuidanceOff.TILT_NOT_READY -> "la inclinación no está en estado 30"
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 200L
    }
}
