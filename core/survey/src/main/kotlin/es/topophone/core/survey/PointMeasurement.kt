package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs

/** Por qué una medida no termina en punto (S010/RF-12). */
enum class MeasurementFailure { NO_DATA, OUTSIDE_CRS_AREA }

/** Lo que va saliendo de una medida (S010/RF-8 a RF-10). */
sealed interface MeasurementUpdate {
    /** Promediado: tiempo reunido con épocas válidas y épocas usadas y descartadas (S010/RF-9). */
    data class Progress(val elapsedS: Double, val requiredS: Double, val epochsUsed: Int, val epochsDiscarded: Int) : MeasurementUpdate

    /** Se ha perdido el FIX: una vez hasta que vuelve (S010/RF-10). */
    data object SolutionLost : MeasurementUpdate

    data class Done(val result: MeasurementResult) : MeasurementUpdate

    data class Failed(val reason: MeasurementFailure) : MeasurementUpdate
}

/**
 * Resultado de una medida (S010/RF-17, RF-18): la antena tal como la da el receptor (media si es promediado), el
 * punto del suelo bajo el jalón vertical, su posición en el SC y la altura del proyecto, la calidad y las frases
 * NMEA de las épocas usadas.
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
)

/**
 * Medida de un punto a partir de las épocas del receptor, sin Android (S010/RF-8 a RF-12):
 * - instantánea: la primera época con posición;
 * - promediada: las épocas válidas hasta reunir el tiempo pedido, contando cada una como el intervalo del
 *   receptor ([epochIntervalS]). Con [fixOnly], las épocas sin FIX se descartan y se avisa una vez;
 * - sin ninguna época en 2 s, se cancela; fuera del ámbito del SC del proyecto, no hay punto.
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
) {
    private val requiredS = averagingSeconds.toDouble()
    private val used = mutableListOf<Epoch>()
    private val usedAtMs = mutableListOf<Long>()
    private var discarded = 0
    private var lastEpochAtMs = startedAtMs
    private var lostWarned = false
    private var finished = false

    /** Una época completa que ha llegado a las [receivedAtMs] del reloj del móvil. */
    fun onEpoch(epoch: Epoch, receivedAtMs: Long): List<MeasurementUpdate> {
        if (finished) return emptyList()
        lastEpochAtMs = receivedAtMs
        val fix = epoch.gga?.takeIf { it.hasPosition }
        val updates = mutableListOf<MeasurementUpdate>()
        when {
            fix == null -> discarded++
            mode == MeasurementMode.AVERAGED && fixOnly && fix.status != SolutionStatus.FIX -> {
                discarded++
                if (!lostWarned) updates += MeasurementUpdate.SolutionLost
                lostWarned = true
            }
            else -> {
                used += epoch
                usedAtMs += receivedAtMs
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
        val position = checkNotNull(calculator.compute(mean, poleHeightM, crs, heightSystem))
        if (PositionUnavailable.OUTSIDE_CRS_AREA in position.unavailable || PositionUnavailable.OUTSIDE_ED50_GRID in position.unavailable) {
            return MeasurementUpdate.Failed(MeasurementFailure.OUTSIDE_CRS_AREA)
        }
        val antennaHeight = mean.altitudeM?.let { altitude -> mean.geoidSeparationM?.let { altitude + it } }
        val lastGst = used.last().gst
        return MeasurementUpdate.Done(
            MeasurementResult(
                antenna = GeodeticPosition(mean.latitudeDeg, mean.longitudeDeg, antennaHeight),
                poleHeightM = poleHeightM,
                ground = GeodeticPosition(
                    mean.latitudeDeg,
                    mean.longitudeDeg,
                    antennaHeight?.minus(VerticalPole.antennaHeightM(poleHeightM, model)),
                ),
                position = position,
                solution = fixes.maxBy { SOLUTION_RANK.indexOf(it.status) }.status,
                satellites = fixes.minOf { it.satellites },
                hdop = mean.hdop,
                correctionAgeS = mean.correctionAgeS,
                horizontalM = lastGst?.horizontalM,
                verticalM = lastGst?.verticalM,
                mode = mode,
                epochsUsed = used.size,
                epochsDiscarded = discarded,
                rawNmea = used.flatMap {
                    listOfNotNull(it.gga?.rawSentence, it.gst?.rawSentence?.takeIf(String::isNotEmpty))
                }.joinToString("\n"),
                measuredFromMs = usedAtMs.first(),
                measuredToMs = usedAtMs.last(),
            ),
        )
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
