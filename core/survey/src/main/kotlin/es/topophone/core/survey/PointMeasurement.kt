package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PointTilt
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.TiltAngles
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs

/** Por qué una medida no termina en punto (S010/RF-12). */
enum class MeasurementFailure { NO_DATA, OUTSIDE_CRS_AREA }

/** Lo que va saliendo de una medida (S010/RF-8 a RF-10; S016/RF-19). */
sealed interface MeasurementUpdate {
    /** Promediado: tiempo reunido con épocas válidas y épocas usadas y descartadas (S010/RF-9). */
    data class Progress(val elapsedS: Double, val requiredS: Double, val epochsUsed: Int, val epochsDiscarded: Int) : MeasurementUpdate

    /** Se ha perdido el FIX: una vez hasta que vuelve (S010/RF-10). */
    data object SolutionLost : MeasurementUpdate

    /** Se descartan épocas por la inclinación, con la guía de la primera: una vez hasta que vuelve (S016/RF-19). */
    data class TiltLost(val guide: TiltGuide) : MeasurementUpdate

    data class Done(val result: MeasurementResult) : MeasurementUpdate

    data class Failed(val reason: MeasurementFailure) : MeasurementUpdate
}

/** El modo de inclinación del receptor con el que se mide y si estaba comprobado (S016/RF-1, RF-10). */
data class TiltSetup(val mode: TiltMode, val verified: Boolean) {
    companion object {
        val VERTICAL = TiltSetup(TiltMode.VERTICAL, verified = false)
    }
}

/**
 * Resultado de una medida (S010/RF-17, RF-18): la antena tal como la da el receptor (media si es promediado), el
 * punto del suelo, su posición en el SC y la altura del proyecto, la calidad y las frases NMEA de las épocas usadas.
 * Con la inclinación (S016/RF-22), el suelo es la punta del jalón y [tilt] lleva el modo y los ángulos.
 *
 * @property maxTiltRad la mayor inclinación de las épocas usadas, para el control de precisión; null si el modo no
 *   usa la inclinación (S016/RF-20).
 */
data class MeasurementResult(
    val antenna: GeodeticPosition,
    val poleHeightM: Double,
    val ground: GeodeticPosition,
    val position: ProjectPosition,
    val solution: SolutionStatus,
    val satellites: Int?,
    val hdop: Double?,
    val correctionAgeS: Double?,
    val horizontalM: Double?,
    val verticalM: Double?,
    val mode: MeasurementMode,
    val epochsUsed: Int,
    val epochsDiscarded: Int,
    val rawNmea: String,
    val measuredFromMs: Long,
    val measuredToMs: Long,
    val tilt: PointTilt = PointTilt(TiltMode.VERTICAL, verified = false, angles = null),
    val maxTiltRad: Double? = null,
)

/**
 * Medida de un punto a partir de las épocas del receptor, sin Android (S010/RF-8 a RF-12):
 * - instantánea: la primera época con posición;
 * - promediada: las épocas válidas hasta reunir el tiempo pedido, contando cada una como el intervalo del
 *   receptor ([epochIntervalS]). Con [fixOnly], las épocas sin FIX se descartan y se avisa una vez;
 * - sin ninguna época en 2 s, se cancela; fuera del ámbito del SC del proyecto, no hay punto.
 *
 * Con un modo que usa la inclinación ([tilt]), solo valen las épocas en estado 30 sin aviso; las demás se descartan y
 * se avisa una vez con su guía (S016/RF-19). El suelo es la punta del jalón (S016/RF-13, RF-14; plan A5).
 *
 * El tiempo es siempre el del reloj del móvil que da quien la usa (llegada de cada época y comprobaciones del
 * plazo): la hora de la sesión es de un reloj monótono que no sirve como fecha de la medida.
 */
class PointMeasurement(
    private val mode: MeasurementMode,
    averagingSeconds: Int,
    private val fixOnly: Boolean,
    private val poleHeightM: Double,
    private val crs: ProjectCrs,
    private val heightSystem: HeightSystem,
    private val epochIntervalS: Double,
    startedAtMs: Long,
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
    private val model: ReceiverModel = ReceiverModel.RS3,
    private val tilt: TiltSetup = TiltSetup.VERTICAL,
) {
    private val requiredS = averagingSeconds.toDouble()
    private val used = mutableListOf<Epoch>()
    private val usedAtMs = mutableListOf<Long>()

    /** La última GST de menos de 3 s que había al llegar la última época usada (S010/RF-22). */
    private var usedGst: GstAccuracy? = null
    private var discarded = 0
    private var lastEpochAtMs = startedAtMs
    private var lostWarned = false
    private var tiltWarned = false
    private var finished = false

    /**
     * Una época completa que ha llegado a las [receivedAtMs] del reloj del móvil, con [latestGst], la última GST de menos
     * de 3 s, que puede haber llegado en una época anterior (S010/RF-22).
     */
    fun onEpoch(epoch: Epoch, receivedAtMs: Long, latestGst: GstAccuracy? = epoch.gst): List<MeasurementUpdate> {
        if (finished) return emptyList()
        lastEpochAtMs = receivedAtMs
        val fix = epoch.gga?.takeIf { it.hasPosition }
        val tiltReady = !tilt.mode.usesTilt || TiltEpoch.isClean(epoch.tilt)
        if (tiltReady) tiltWarned = false
        val updates = mutableListOf<MeasurementUpdate>()
        when {
            fix == null -> discarded++
            // S016/RF-19: antes que el FIX, porque con "GNSS malo" se pierden los dos y la guía explica más.
            !tiltReady -> {
                discarded++
                if (!tiltWarned) updates += MeasurementUpdate.TiltLost(checkNotNull(TiltEpoch.guide(epoch.tilt)))
                tiltWarned = true
            }
            mode == MeasurementMode.AVERAGED && fixOnly && fix.status != SolutionStatus.FIX -> {
                discarded++
                if (!lostWarned) updates += MeasurementUpdate.SolutionLost
                lostWarned = true
            }
            else -> {
                used += epoch
                usedAtMs += receivedAtMs
                usedGst = latestGst
                if (fix.status == SolutionStatus.FIX) lostWarned = false
            }
        }
        val elapsedS = used.size * epochIntervalS
        val complete = when (mode) {
            MeasurementMode.INSTANT -> used.isNotEmpty()
            MeasurementMode.AVERAGED -> elapsedS >= requiredS - TIME_EPSILON_S
        }
        when {
            complete -> updates += finish()
            mode == MeasurementMode.AVERAGED -> updates += MeasurementUpdate.Progress(elapsedS, requiredS, used.size, discarded)
        }
        return updates
    }

    /** Con el reloj del móvil: sin ninguna época en 2 s, la medida se cancela (S010/RF-12). */
    fun onTick(nowMs: Long): MeasurementUpdate? {
        if (finished || nowMs - lastEpochAtMs < NO_DATA_MS) return null
        finished = true
        return MeasurementUpdate.Failed(MeasurementFailure.NO_DATA)
    }

    /** S010/RF-11: no se guarda nada y no sale nada más. */
    fun cancel() {
        finished = true
    }

    private fun finish(): MeasurementUpdate {
        finished = true
        val fixes = used.map { checkNotNull(it.gga) }
        val altitudes = fixes.mapNotNull { it.altitudeM }.takeIf { it.size == fixes.size }
        val separations = fixes.mapNotNull { it.geoidSeparationM }.takeIf { it.size == fixes.size }
        // Media aritmética de φ, λ y h: con dispersiones de centímetros es la de ECEF a menos de 1 µm.
        val mean = fixes.last().copy(
            latitudeDeg = fixes.map { it.latitudeDeg }.average(),
            longitudeDeg = fixes.map { it.longitudeDeg }.average(),
            altitudeM = altitudes?.average(),
            geoidSeparationM = separations?.average(),
        )
        val antennaH = mean.altitudeM?.let { altitude -> mean.geoidSeparationM?.let { altitude + it } }
        val antenna = GeodeticPosition(mean.latitudeDeg, mean.longitudeDeg, antennaH)
        val antennaHeight = VerticalPole.antennaHeightM(poleHeightM, model)
        val angles = angles(antennaHeight)
        val ground = when (tilt.mode) {
            // Media de la antena menos H·ū: la media de las puntas de cada época (plan A3.3).
            TiltMode.APP -> PoleTip.fromAntenna(antenna, checkNotNull(angles).pole, antennaHeight)
            TiltMode.RECEIVER -> antenna // la GGA ya es la punta (S016/RF-14)
            TiltMode.VERTICAL -> GeodeticPosition(mean.latitudeDeg, mean.longitudeDeg, antennaH?.minus(antennaHeight))
        }
        val position = calculator.fromGround(ground, crs, heightSystem)
        if (PositionUnavailable.OUTSIDE_CRS_AREA in position.unavailable || PositionUnavailable.OUTSIDE_ED50_GRID in position.unavailable) {
            return MeasurementUpdate.Failed(MeasurementFailure.OUTSIDE_CRS_AREA)
        }
        val lastGst = usedGst
        // S016/RF-21: en APP se suma la precisión de la inclinación; el receptor que compensa ya la da en la GST.
        val addTilt = tilt.mode == TiltMode.APP
        return MeasurementUpdate.Done(
            MeasurementResult(
                antenna = antenna,
                poleHeightM = poleHeightM,
                ground = ground,
                position = position,
                solution = fixes.maxBy { SOLUTION_RANK.indexOf(it.status) }.status,
                satellites = fixes.minOf { it.satellites },
                hdop = mean.hdop,
                correctionAgeS = mean.correctionAgeS,
                horizontalM = lastGst?.horizontalM?.plus(if (addTilt) checkNotNull(angles).horizontalAccuracyM else 0.0),
                verticalM = lastGst?.verticalM?.plus(if (addTilt) checkNotNull(angles).verticalAccuracyM else 0.0),
                mode = mode,
                epochsUsed = used.size,
                epochsDiscarded = discarded,
                rawNmea = rawNmea(lastGst),
                measuredFromMs = usedAtMs.first(),
                measuredToMs = usedAtMs.last(),
                tilt = PointTilt(tilt.mode, tilt.verified, angles),
                maxTiltRad = if (tilt.mode.usesTilt) angles?.maxTiltRad else null,
            ),
        )
    }

    /**
     * Los ángulos de las épocas usadas si todas están en estado 30, en cualquier modo (S016/RF-22): los de la última,
     * la mayor inclinación, las mayores precisiones de la inclinación y la media del vector del jalón.
     */
    private fun angles(antennaHeightM: Double): TiltAngles? {
        val tilts = used.map { it.tilt }
        if (!tilts.all(TiltEpoch::isCompensating)) return null
        val poles = tilts.map { checkNotNull(TiltEpoch.poleVector(it)) }
        val accuracies = tilts.map { checkNotNull(TiltEpoch.accuracy(it, antennaHeightM)) }
        val last = checkNotNull(tilts.last())
        return TiltAngles(
            tiltRad = checkNotNull(last.tiltRad),
            directionRad = checkNotNull(last.tiltDirectionRad),
            headingRad = last.headingRad,
            maxTiltRad = tilts.maxOf { checkNotNull(it?.tiltRad) },
            horizontalAccuracyM = accuracies.maxOf { it.horizontalM },
            verticalAccuracyM = accuracies.maxOf { it.verticalM },
            pole = PoleVector(poles.map { it.east }.average(), poles.map { it.north }.average(), poles.map { it.up }.average()),
            receiverPoleHeightM = if (tilt.mode == TiltMode.RECEIVER) poleHeightM else null,
        )
    }

    /**
     * Las frases de las épocas usadas (GGA, GST y ETC) y, si no venía en ellas, la GST de la que salen las precisiones
     * (S010/RF-17, RF-23; S016/RF-22).
     */
    private fun rawNmea(gst: GstAccuracy?): String {
        val sentences = used.flatMap { epoch ->
            listOfNotNull(epoch.gga?.rawSentence, epoch.gst?.rawSentence, epoch.tilt?.rawSentence).filter(String::isNotEmpty)
        }
        val extra = gst?.rawSentence?.takeIf { it.isNotEmpty() && it !in sentences }
        return (sentences + listOfNotNull(extra)).joinToString("\n")
    }

    private companion object {
        const val NO_DATA_MS = 2_000L
        const val TIME_EPSILON_S = 1e-9

        /** De mejor a peor: con el promediado, la solución del punto es la peor de las épocas usadas. */
        val SOLUTION_RANK = listOf(
            SolutionStatus.FIX,
            SolutionStatus.FLOAT,
            SolutionStatus.DGPS,
            SolutionStatus.SINGLE,
            SolutionStatus.ESTIMATED,
            SolutionStatus.NONE,
        )
    }
}
