package es.topophone.app.ui.stakeout

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.Project
import es.topophone.core.survey.DianaScale
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.HeadingSource
import es.topophone.core.survey.LevelAlert
import es.topophone.core.survey.LevelScale
import es.topophone.core.survey.LevelState
import es.topophone.core.survey.LineLocator
import es.topophone.core.survey.LineObjective
import es.topophone.core.survey.LineObjectives
import es.topophone.core.survey.LineRange
import es.topophone.core.survey.LiveTip
import es.topophone.core.survey.PlanePoint
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.ReceiverModel
import es.topophone.core.survey.StakeAlert
import es.topophone.core.survey.StakeLine
import es.topophone.core.survey.StakeZone
import es.topophone.core.survey.StakeoutGeometry
import es.topophone.core.survey.StakeoutLevel
import es.topophone.core.survey.StakeoutZones
import es.topophone.core.survey.TiltEpoch
import es.topophone.core.survey.VerticalPole
import es.topophone.receiver.platform.Alerts
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * El punto de diseño que se replantea (S018/RF-4). [source] dice si es un punto del proyecto o un elemento de un dibujo (S041/RF-28);
 * en una línea, [coordinates] es el primer vértice y solo da el nombre y el origen: el objetivo de cada época sale de [LineGuide].
 */
data class StakeTarget(
    val id: String,
    val name: String,
    val description: String,
    val coordinates: PointCoordinates,
    val source: StakeSource = StakeSource.ProjectPoint,
)

/**
 * El objetivo que se mueve con la punta cuando se replantea una línea (S041/A4, RF-10, RF-11, RF-17): la línea con su localizador,
 * el desplazamiento lateral y el tramo. En "Por segmentos" y "Intervalos" no hay [LineGuide]: el objetivo es un punto fijo.
 */
class LineGuide(
    val line: StakeLine,
    val locator: LineLocator,
    val mode: LineStakeoutMode,
    val offsetM: Double = 0.0,
    val range: LineRange = LineRange.whole(line),
)

/**
 * Todo lo que la guía necesita saber de fuera con cada época: los ajustes, el punto, el control de precisión y el proyecto.
 * [designElevationM] es la cota de diseño que escribió el usuario (S019/RF-2); sin ella se usa la altura del punto de diseño.
 */
class StakeContext(
    val settings: StakeoutSettings,
    val target: StakeTarget,
    val fixOnly: Boolean,
    val tiltMode: TiltMode?,
    val poleHeightM: Double,
    val project: Project,
    val calculator: ProjectPositionCalculator,
    val designElevationM: Double? = null,
    val line: LineGuide? = null,
) {
    /** La cota de diseño que se replantea: la escrita, o la altura del punto de diseño (S019/RF-1, RF-2). */
    val elevationM: Double? get() = designElevationM ?: target.coordinates.height
}

/** Por qué no hay nivel vertical aunque la guía esté encendida (S019/RF-5). */
enum class LevelUnavailable { NO_DESIGN_ELEVATION, TIP_WITHOUT_HEIGHT }

/**
 * El nivel vertical con una época (S019/RF-6 a RF-9): la diferencia de cota [dzM] (punta menos cota de diseño; positiva en
 * desmonte), su [state], el radio visible [radiusM] del tubo y la tolerancia vertical [toleranceM].
 */
data class LevelUi(val dzM: Double, val state: LevelState, val radiusM: Double, val toleranceM: Double)

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
    /** El nivel vertical, o null si falta la cota o la altura de la punta, con el motivo en [levelUnavailable] (S019/RF-5). */
    val level: LevelUi? = null,
    val levelUnavailable: LevelUnavailable? = null,
    /** El objetivo de una línea con la punta donde está: el PK, el lado, el error lateral y lo que falta (S041/RF-10 a RF-14). */
    val line: LineObjective? = null,
    /** El objetivo de la época, para guardar el replanteo y dibujarlo en el mapa (S041/RF-5, RF-28). */
    val objective: PlanePoint? = null,
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
    private var lineSegment: Int? = null // el tramo de la época anterior, para empezar por él (S041/RNF-T13)

    // S019: el nivel vertical; null es "sin estado previo" (al empezar, al volver la guía o al cambiar la cota).
    private var levelState: LevelState? = null
    private var levelRadius: Double? = null
    private var levelElevation: Double? = null

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
        val tipEast = tipCoordinates.easting
        val tipNorth = tipCoordinates.northing
        val guide = context.line
        // S041: con una línea el objetivo se mueve con la punta y se calcula con el plano del SC del proyecto.
        if (guide != null && (tipEast == null || tipNorth == null)) {
            switchOff(GuidanceOff.NO_POSITION, context)
            return
        }
        val lineObjective = if (guide != null && tipEast != null && tipNorth != null) {
            val location = guide.locator.locate(tipEast, tipNorth, lineSegment)
            lineSegment = location.segment
            LineObjectives.of(guide.line, location, guide.offsetM, guide.range)
        } else {
            null
        }
        // S019/RF-1, RF-2: la cota de diseño es la escrita o la del punto; la diferencia de cota sale contra ella.
        val elevation = context.designElevationM ?: lineObjective?.heightM ?: context.target.coordinates.height
        val design = if (lineObjective != null) {
            context.target.coordinates.copy(
                ground = tip.ground,
                easting = lineObjective.point.easting,
                northing = lineObjective.point.northing,
                height = elevation,
            )
        } else {
            context.target.coordinates.copy(height = elevation)
        }

        // El rumbo de marcha sigue a la punta respecto a un punto que no se mueve: el de diseño, o con una línea, su primer vértice.
        val toward = StakeoutGeometry.guidance(design, tipCoordinates, headingRad = null)
        val timeMs = epoch.utcMillisOfDay.toLong()
        if (guide != null && tipEast != null && tipNorth != null) {
            heading.onTip(timeMs, tipEast - guide.line.easting(0), tipNorth - guide.line.northing(0))
        } else {
            heading.onTip(timeMs, -toward.dEastM, -toward.dNorthM)
        }
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
        // S019/RF-2: otra cota, el nivel empieza de cero; con una línea la cota cambia con el PK, y solo cuenta la que escribe el usuario.
        val levelKey = if (lineObjective != null) context.designElevationM else elevation
        if (levelKey != levelElevation) levelState = null
        levelElevation = levelKey
        val levelBefore = levelState
        val level = levelOf(guidance.dHeightM, context)
        val levelChanged = levelBefore != levelState
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
            level = level,
            levelUnavailable = if (level !=
                null
            ) {
                null
            } else if (elevation == null) {
                LevelUnavailable.NO_DESIGN_ELEVATION
            } else {
                LevelUnavailable.TIP_WITHOUT_HEIGHT
            },
            line = lineObjective,
            objective = lineObjective?.point,
        )
        val becameOn = !wasOn
        wasOn = true
        publish(GuidanceState.On(ui), force = becameOn || zoneChanged || levelChanged)
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
        resetLevel()
        wasOn = false
        lineSegment = null
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
        resetLevel() // S019/RF-11: el nivel se apaga con la guía y vuelve a empezar de cero
        if (!alreadyOff) {
            flow.value = GuidanceState.Off(reason)
            lastPublishedAtMs = Long.MIN_VALUE
        }
    }

    private fun resetLevel() {
        levelState = null
        levelRadius = null
        levelElevation = null
    }

    /**
     * El nivel vertical de la época (S019/A1): su estado con el margen de 5 mm, el aviso si entra o sale de la cota, y la escala.
     * Sin diferencia de cota (falta la cota de diseño o la altura de la punta) no hay nivel y empieza de cero cuando vuelva.
     */
    private fun levelOf(dzM: Double?, context: StakeContext): LevelUi? {
        if (dzM == null) {
            levelState = null
            levelRadius = null
            return null
        }
        val tolerance = context.settings.validToleranceVM
        val step = StakeoutLevel.next(levelState, dzM, tolerance)
        levelState = step.state
        step.alert?.let { alert ->
            when (alert) {
                LevelAlert.ARRIVED -> alerts.stakeoutLevelArrived()
                LevelAlert.LEFT -> alerts.stakeoutLevelLeft()
            }
            log.info(LogCategory.POINTS, "Replanteo de ${context.target.name}: ${alert.logText()}")
        }
        val radius = LevelScale.radius(levelRadius, abs(dzM), tolerance).also { levelRadius = it }
        return LevelUi(dzM, step.state, radius, tolerance)
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

    private fun LevelAlert.logText() = when (this) {
        LevelAlert.ARRIVED -> "en cota"
        LevelAlert.LEFT -> "fuera de cota"
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
