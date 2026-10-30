package es.topophone.core.survey

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GearUsed
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PointTilt
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.SolutionType
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

    /** Llega una época de un tipo peor que el mejor visto en la medida o no admitido: una vez hasta que vuelve el mejor (S047/RF-10). */
    data object SolutionDegraded : MeasurementUpdate

    /** Se descartan épocas por la inclinación, con la guía de la primera: una vez hasta que vuelve (S016/RF-19). */
    data class TiltLost(val guide: TiltGuide) : MeasurementUpdate

    data class Done(val result: MeasurementResult) : MeasurementUpdate

    data class Failed(val reason: MeasurementFailure) : MeasurementUpdate

    /** "Terminar" sin ninguna época usada: no se guarda nada y la medida sigue (S017/RF-7). */
    data object NoEpochsYet : MeasurementUpdate

    /** Un promediado sin épocas desde hace 2 s se pausa y conserva lo reunido: una vez por corte (S017/RF-8). */
    data object Paused : MeasurementUpdate

    /** Han vuelto las épocas y el promediado sigue solo, sin contar el tiempo del corte (S017/RF-9). */
    data object Resumed : MeasurementUpdate

    /** El corte pasa de 5 min: hay que elegir entre guardar con lo reunido, descartar o seguir esperando (S017/RF-10). */
    data object LongPause : MeasurementUpdate
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
 * @property averagedTimeS el tiempo promediado y [deviationHorizontalM] y [deviationVerticalM] las desviaciones típicas de
 *   las épocas usadas, solo en una medida promediada; las desviaciones son null con menos de 2 épocas, y la vertical,
 *   sin altura (S017/RF-12).
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
    /** Las descartadas por tipo de solución, también las reunidas que se tiraron al llegar un tipo mejor (S047/RF-9, RF-15). */
    val epochsDiscardedByType: Int = 0,
    /** Los tipos admitidos al medir (S047/RF-15). */
    val admitted: AdmittedSolutions = AdmittedSolutions.FIX_ONLY,
    val rawNmea: String,
    val measuredFromMs: Long,
    val measuredToMs: Long,
    val tilt: PointTilt = PointTilt(TiltMode.VERTICAL, verified = false, angles = null),
    val maxTiltRad: Double? = null,
    val averagedTimeS: Double? = null,
    val deviationHorizontalM: Double? = null,
    val deviationVerticalM: Double? = null,
    /** El jalón y la antena con los que se midió (S046/RF-24); null si quien mide no los da. */
    val gear: GearUsed? = null,
    /** El desplazamiento de base con que se midió y la punta sin desplazar (S020/RF-8); [ground] ya lo lleva. */
    val shift: BaseShiftVector? = null,
    val unshiftedGround: GeodeticPosition = ground,
)

/**
 * Medida de un punto a partir de las épocas del receptor, sin Android (S010/RF-8 a RF-12):
 * - instantánea: la primera época con posición;
 * - promediada: las épocas válidas hasta reunir el tiempo pedido, contando cada una como el intervalo del
 *   receptor ([epochIntervalS]). Solo valen las épocas de un tipo de [admitted]; la medida sigue con el mejor tipo visto: una época
 *   de un tipo mejor descarta lo reunido y una de un tipo peor se descarta, con un aviso una vez (S047/RF-9, RF-10);
 * - sin ninguna época en 2 s, la instantánea se cancela y la promediada se pausa, conserva lo reunido y sigue sola al
 *   volver las épocas (S017/RF-8, RF-9); a los 5 min de corte se pregunta qué hacer (RF-10). Fuera del ámbito del SC del
 *   proyecto, no hay punto.
 *
 * Con un modo que usa la inclinación ([tilt]), solo valen las épocas en estado 30 sin aviso; las demás se descartan y
 * se avisa una vez con su guía (S016/RF-19). El suelo es la punta del jalón (S016/RF-13, RF-14; plan A5).
 *
 * No guarda las épocas usadas, que en un promediado de 60 min a 10 Hz serían decenas de MB: acumula en una sola pasada
 * lo que hace falta para el resultado (S017/RF-12; plan A1).
 *
 * El tiempo es siempre el del reloj del móvil que da quien la usa (llegada de cada época y comprobaciones del
 * plazo): la hora de la sesión es de un reloj monótono que no sirve como fecha de la medida.
 */
class PointMeasurement(
    private val mode: MeasurementMode,
    averagingSeconds: Int,
    private val admitted: AdmittedSolutions,
    private val poleHeightM: Double,
    private val crs: ProjectCrs,
    private val heightSystem: HeightSystem,
    private val epochIntervalS: Double,
    startedAtMs: Long,
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
    private val profile: AntennaProfile = AntennaProfile.RS3,
    private val tilt: TiltSetup = TiltSetup.VERTICAL,
    private val gear: GearUsed? = null,
    /** El desplazamiento de base activo al empezar: la medida lo conserva hasta el final aunque se desactive (S020/RF-9). */
    private val shift: BaseShiftVector? = null,
) {
    private val requiredS = averagingSeconds.toDouble()

    /** Las estadísticas del promediado (S017/RF-3 a RF-5); la instantánea no las lleva. */
    private var stats = newStats()
    private var statsOrigin: GeodeticPoint? = null

    /** Lo acumulado de las épocas usadas (plan A1). */
    private var usedCount = 0
    private var lastFix: GgaFix? = null
    private var sumLatitude = 0.0
    private var sumLongitude = 0.0
    private var sumAltitude = 0.0
    private var altitudeCount = 0
    private var sumSeparation = 0.0
    private var separationCount = 0
    private var minSatellites = Int.MAX_VALUE
    private var worstSolution: SolutionStatus? = null
    private var firstUsedAtMs = 0L
    private var lastUsedAtMs = 0L
    private val raw = StringBuilder()
    private val includedGst = HashSet<String>()

    /** Con la inclinación (S016/RF-22): mientras todas las épocas usadas estén en estado 30. */
    private val antennaHeightM = PoleGeometry.antennaHeightM(poleHeightM, profile.offsetM)
    private var allCompensating = true
    private var lastTilt: TiltData? = null
    private var maxTilt = 0.0
    private var maxHorizontalAccuracy = 0.0
    private var maxVerticalAccuracy = 0.0
    private var poleEast = 0.0
    private var poleNorth = 0.0
    private var poleUp = 0.0

    /** La última GST de menos de 3 s que había al llegar la última época usada (S010/RF-22). */
    private var usedGst: GstAccuracy? = null
    private var discarded = 0
    private var discardedByType = 0

    /** El mejor tipo de solución visto en el promediado (S047/RF-9, RF-10). */
    private var bestType: SolutionType? = null
    private var lastEpochAtMs = startedAtMs
    private var degradedWarned = false
    private var tiltWarned = false
    private var finished = false

    /** En pausa por un corte (S017/RF-8); solo el promediado, que la instantánea se cancela. */
    var paused = false
        private set
    private var longPauseDueAtMs = 0L
    private var longPauseAsked = false

    /**
     * Una época completa que ha llegado a las [receivedAtMs] del reloj del móvil, con [latestGst], la última GST de menos
     * de 3 s, que puede haber llegado en una época anterior (S010/RF-22).
     */
    fun onEpoch(epoch: Epoch, receivedAtMs: Long, latestGst: GstAccuracy? = epoch.gst): List<MeasurementUpdate> {
        if (finished) return emptyList()
        lastEpochAtMs = receivedAtMs
        val updates = mutableListOf<MeasurementUpdate>()
        if (paused) {
            paused = false
            updates += MeasurementUpdate.Resumed // S017/RF-9
        }
        val fix = epoch.gga?.takeIf { it.hasPosition }
        val type = fix?.let { SolutionTypes.of(it.status) }
        val tiltReady = !tilt.mode.usesTilt || TiltEpoch.isClean(epoch.tilt)
        if (tiltReady) tiltWarned = false
        when {
            fix == null -> discarded++
            // S016/RF-19: antes que el FIX, porque con "GNSS malo" se pierden los dos y la guía explica más.
            !tiltReady -> {
                discarded++
                if (!tiltWarned) updates += MeasurementUpdate.TiltLost(checkNotNull(TiltEpoch.guide(epoch.tilt)))
                tiltWarned = true
            }
            mode == MeasurementMode.AVERAGED && isWorseThanBest(type) -> {
                discarded++
                discardedByType++
                if (isDegradation(type)) {
                    if (!degradedWarned) updates += MeasurementUpdate.SolutionDegraded
                    degradedWarned = true
                }
            }
            else -> {
                if (mode == MeasurementMode.AVERAGED) startBetterType(checkNotNull(type))
                use(epoch, fix, receivedAtMs, latestGst)
                degradedWarned = false
            }
        }
        val elapsedS = usedCount * epochIntervalS
        val complete = when (mode) {
            MeasurementMode.INSTANT -> usedCount > 0
            MeasurementMode.AVERAGED -> elapsedS >= requiredS - TIME_EPSILON_S
        }
        when {
            complete -> updates += finish()
            mode == MeasurementMode.AVERAGED -> updates += MeasurementUpdate.Progress(elapsedS, requiredS, usedCount, discarded)
        }
        return updates
    }

    /** La época no vale en un promediado: su tipo no está admitido o es peor que el mejor visto (S047/RF-10; plan A1). */
    private fun isWorseThanBest(type: SolutionType?): Boolean {
        val best = bestType
        return type == null || type !in admitted || (best != null && type > best)
    }

    /**
     * La época descartada es una degradación: no tiene tipo o es peor que el mejor visto, o que el mejor admitido si aún no se ha
     * reunido nada. Una época mejor que la medida pero no admitida se descarta sin aviso (S047/RF-10).
     */
    private fun isDegradation(type: SolutionType?): Boolean = type == null || type > (bestType ?: admitted.best)

    /**
     * Una época admitida entra en el promediado: si su tipo es mejor que el visto hasta ahora, lo reunido se descarta y la medida
     * sigue solo con este tipo (S047/RF-9).
     */
    private fun startBetterType(type: SolutionType) {
        val best = bestType
        if (best != null && type < best) {
            discarded += usedCount
            discardedByType += usedCount
            resetAccumulators()
        }
        bestType = type
    }

    /** Vuelve a cero lo acumulado de las épocas usadas, para seguir con un tipo mejor (S047/RF-9). */
    private fun resetAccumulators() {
        usedCount = 0
        lastFix = null
        sumLatitude = 0.0
        sumLongitude = 0.0
        sumAltitude = 0.0
        altitudeCount = 0
        sumSeparation = 0.0
        separationCount = 0
        minSatellites = Int.MAX_VALUE
        worstSolution = null
        firstUsedAtMs = 0L
        lastUsedAtMs = 0L
        raw.setLength(0)
        includedGst.clear()
        usedGst = null
        allCompensating = true
        lastTilt = null
        maxTilt = 0.0
        maxHorizontalAccuracy = 0.0
        maxVerticalAccuracy = 0.0
        poleEast = 0.0
        poleNorth = 0.0
        poleUp = 0.0
        stats = newStats()
        statsOrigin = null
    }

    private fun newStats() = if (mode == MeasurementMode.AVERAGED) AverageStats(epochIntervalS) else null

    /** Suma una época usada a lo acumulado (plan A1). */
    private fun use(epoch: Epoch, fix: GgaFix, receivedAtMs: Long, latestGst: GstAccuracy?) {
        if (usedCount == 0) firstUsedAtMs = receivedAtMs
        usedCount++
        lastUsedAtMs = receivedAtMs
        lastFix = fix
        usedGst = latestGst
        sumLatitude += fix.latitudeDeg
        sumLongitude += fix.longitudeDeg
        fix.altitudeM?.let {
            sumAltitude += it
            altitudeCount++
        }
        fix.geoidSeparationM?.let {
            sumSeparation += it
            separationCount++
        }
        minSatellites = minOf(minSatellites, fix.satellites)
        // Si dos son igual de malas, se queda la primera (como el `maxBy` de antes).
        if (worstSolution == null || SOLUTION_RANK.indexOf(fix.status) > SOLUTION_RANK.indexOf(worstSolution)) worstSolution = fix.status
        addTilt(epoch.tilt)
        stats?.let { addToStats(it, fix, epoch.tilt) }
        listOfNotNull(fix.rawSentence, epoch.gst?.rawSentence, epoch.tilt?.rawSentence).filter(String::isNotEmpty).forEach {
            if (raw.isNotEmpty()) raw.append('\n')
            raw.append(it)
        }
        epoch.gst?.rawSentence?.takeIf(String::isNotEmpty)?.let(includedGst::add)
    }

    /**
     * La posición del suelo de la época en el SC del proyecto, para la desviación típica (S017/RF-3; plan A2). En un SC
     * geográfico no hay Este ni Norte y se usa el plano tangente local de la primera época. Una época sin posición en el
     * SC (fuera de su ámbito) no entra: la medida acabará en `OUTSIDE_CRS_AREA`.
     */
    private fun addToStats(stats: AverageStats, fix: GgaFix, data: TiltData?) {
        val altitude = fix.altitudeM
        val separation = fix.geoidSeparationM
        val antennaH = if (altitude != null && separation != null) altitude + separation else null
        val antenna = GeodeticPosition(fix.latitudeDeg, fix.longitudeDeg, antennaH)
        val ground = when (tilt.mode) {
            TiltMode.APP -> TiltEpoch.poleVector(data)?.let { PoleTip.fromAntenna(antenna, it, antennaHeightM) } ?: belowAntenna(antenna)
            TiltMode.RECEIVER -> antenna
            TiltMode.VERTICAL -> belowAntenna(antenna)
        }
        val shifted = BaseShiftApplier.apply(ground, shift)
        if (crs.isUtm) {
            val position = calculator.fromGround(shifted, crs, heightSystem)
            val east = position.easting
            val north = position.northing
            if (east != null && north != null) stats.add(east, north, position.height)
        } else {
            val point = GeodeticPoint(shifted.latitudeDeg, shifted.longitudeDeg, shifted.ellipsoidalHeightM ?: 0.0)
            val origin = statsOrigin ?: point.also { statsOrigin = it }
            val enu = LocalTangentPlane.between(origin, point)
            stats.add(enu.east, enu.north, enu.up.takeIf { shifted.ellipsoidalHeightM != null })
        }
    }

    private fun belowAntenna(antenna: GeodeticPosition) =
        GeodeticPosition(antenna.latitudeDeg, antenna.longitudeDeg, antenna.ellipsoidalHeightM?.minus(antennaHeightM))

    /** Las estadísticas del promediado en curso (S017/RF-3, RF-4); null si es instantánea o aún no hay épocas. */
    fun stats(): AverageStatsSnapshot? = stats?.snapshot()

    /** Las épocas del gráfico de dispersión del promediado en curso (S017/RF-5); null si es instantánea o aún no hay épocas. */
    fun dispersion(): Dispersion? = stats?.takeIf { it.snapshot() != null }?.dispersion()

    /** S016/RF-22: los ángulos de las épocas usadas, si todas están en estado 30, en cualquier modo. */
    private fun addTilt(data: TiltData?) {
        if (!allCompensating) return
        if (!TiltEpoch.isCompensating(data)) {
            allCompensating = false
            return
        }
        val pole = checkNotNull(TiltEpoch.poleVector(data))
        val accuracy = checkNotNull(TiltEpoch.accuracy(data, antennaHeightM))
        lastTilt = data
        maxTilt = maxOf(maxTilt, checkNotNull(data?.tiltRad))
        maxHorizontalAccuracy = maxOf(maxHorizontalAccuracy, accuracy.horizontalM)
        maxVerticalAccuracy = maxOf(maxVerticalAccuracy, accuracy.verticalM)
        poleEast += pole.east
        poleNorth += pole.north
        poleUp += pole.up
    }

    /**
     * Con el reloj del móvil: sin ninguna época en 2 s, la instantánea se cancela (S010/RF-12) y la promediada se pausa
     * (S017/RF-8); con la promediada en pausa 5 min seguidos, se pregunta qué hacer (S017/RF-10). Como mucho un aviso por
     * llamada: el segundo llega en la siguiente.
     */
    fun onTick(nowMs: Long): MeasurementUpdate? {
        if (finished) return null
        val silentMs = nowMs - lastEpochAtMs
        if (mode == MeasurementMode.INSTANT) {
            if (silentMs < NO_DATA_MS) return null
            finished = true
            return MeasurementUpdate.Failed(MeasurementFailure.NO_DATA)
        }
        return when {
            !paused && silentMs >= NO_DATA_MS -> {
                paused = true
                longPauseAsked = false
                longPauseDueAtMs = lastEpochAtMs + LONG_PAUSE_MS
                MeasurementUpdate.Paused
            }
            paused && !longPauseAsked && nowMs >= longPauseDueAtMs -> {
                longPauseAsked = true
                MeasurementUpdate.LongPause
            }
            else -> null
        }
    }

    /** "Seguir esperando" a la pregunta de 5 min: se vuelve a preguntar si el corte sigue 5 min más (S017/RF-10). */
    fun keepWaiting(nowMs: Long) {
        longPauseAsked = false
        longPauseDueAtMs = nowMs + LONG_PAUSE_MS
    }

    /**
     * "Terminar" (S017/RF-6, RF-7): cierra la medida con las épocas reunidas, también en pausa; sin ninguna no termina y
     * sigue. Null si la medida ya había terminado.
     */
    fun finishNow(): MeasurementUpdate? {
        if (finished) return null
        return if (usedCount == 0) MeasurementUpdate.NoEpochsYet else finish()
    }

    /** S010/RF-11: no se guarda nada y no sale nada más. */
    fun cancel() {
        finished = true
    }

    private fun finish(): MeasurementUpdate {
        finished = true
        val last = checkNotNull(lastFix)
        val n = usedCount
        // Media aritmética de φ, λ y h: con dispersiones de centímetros es la de ECEF a menos de 1 µm.
        val latitude = sumLatitude / n
        val longitude = sumLongitude / n
        val altitude = if (altitudeCount == n) sumAltitude / n else null
        val separation = if (separationCount == n) sumSeparation / n else null
        val antennaH = altitude?.let { a -> separation?.let { a + it } }
        val antenna = GeodeticPosition(latitude, longitude, antennaH)
        val angles = angles()
        val unshiftedGround = when (tilt.mode) {
            // Media de la antena menos H·ū: la media de las puntas de cada época (plan A3.3).
            TiltMode.APP -> PoleTip.fromAntenna(antenna, checkNotNull(angles).pole, antennaHeightM)
            TiltMode.RECEIVER -> antenna // la GGA ya es la punta (S016/RF-14)
            TiltMode.VERTICAL -> belowAntenna(antenna)
        }
        val ground = BaseShiftApplier.apply(unshiftedGround, shift) // S020/RF-7: antes del SC
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
                gear = gear,
                shift = shift,
                unshiftedGround = unshiftedGround,
                ground = ground,
                position = position,
                solution = checkNotNull(worstSolution),
                satellites = minSatellites,
                hdop = last.hdop,
                correctionAgeS = last.correctionAgeS,
                horizontalM = lastGst?.horizontalM?.plus(if (addTilt) checkNotNull(angles).horizontalAccuracyM else 0.0),
                verticalM = lastGst?.verticalM?.plus(if (addTilt) checkNotNull(angles).verticalAccuracyM else 0.0),
                mode = mode,
                epochsUsed = n,
                epochsDiscarded = discarded,
                epochsDiscardedByType = discardedByType,
                admitted = admitted,
                rawNmea = rawNmea(lastGst),
                measuredFromMs = firstUsedAtMs,
                measuredToMs = lastUsedAtMs,
                tilt = PointTilt(tilt.mode, tilt.verified, angles),
                maxTiltRad = if (tilt.mode.usesTilt) angles?.maxTiltRad else null,
                averagedTimeS = if (mode == MeasurementMode.AVERAGED) n * epochIntervalS else null,
                deviationHorizontalM = stats?.snapshot()?.deviationHorizontalM,
                deviationVerticalM = stats?.snapshot()?.deviationVerticalM,
            ),
        )
    }

    /**
     * Los ángulos de las épocas usadas si todas están en estado 30, en cualquier modo (S016/RF-22): los de la última,
     * la mayor inclinación, las mayores precisiones de la inclinación y la media del vector del jalón.
     */
    private fun angles(): TiltAngles? {
        val last = lastTilt
        if (!allCompensating || last == null) return null
        val n = usedCount
        return TiltAngles(
            tiltRad = checkNotNull(last.tiltRad),
            directionRad = checkNotNull(last.tiltDirectionRad),
            headingRad = last.headingRad,
            maxTiltRad = maxTilt,
            horizontalAccuracyM = maxHorizontalAccuracy,
            verticalAccuracyM = maxVerticalAccuracy,
            pole = PoleVector(poleEast / n, poleNorth / n, poleUp / n),
            receiverPoleHeightM = if (tilt.mode == TiltMode.RECEIVER) poleHeightM else null,
        )
    }

    /**
     * Las frases de las épocas usadas (GGA, GST y ETC) y, si no venía en ellas, la GST de la que salen las precisiones
     * (S010/RF-17, RF-23; S016/RF-22).
     */
    private fun rawNmea(gst: GstAccuracy?): String {
        val extra = gst?.rawSentence?.takeIf { it.isNotEmpty() && it !in includedGst }
        return if (extra == null) raw.toString() else raw.append('\n').append(extra).toString()
    }

    private companion object {
        const val NO_DATA_MS = 2_000L
        const val LONG_PAUSE_MS = 300_000L
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
