package es.topophone.core.gnss.scenario

import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltAccuracyCoefficients
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Los siete escenarios del receptor virtual (S022/RF-14, plan A2). Los números son los de partida del plan: los tests comprueban
 * los instantes de los eventos, no el resto.
 */
object Scenarios {

    private const val SIGMA_LATITUDE_M = 0.008
    private const val SIGMA_LONGITUDE_M = 0.007
    private const val SIGMA_ALTITUDE_M = 0.015
    private const val HDOP_FIX = 0.5
    private const val CORRECTION_AGE_S = 1.0
    private const val FIX_SATELLITES = 22
    private const val WALK_SATELLITES = 20
    private const val JITTER_M = 0.003
    private const val JITTER_EAST_RATE = 0.9
    private const val JITTER_NORTH_RATE = 0.7

    private const val STABLE_FIX_DURATION_S = 120
    private const val STRAIGHT_WALK_DURATION_S = 120
    private const val WALK_LEG_S = 60

    private const val LOSS_DURATION_S = 180
    private const val LOSS_FLOAT_FROM_S = 40
    private const val LOSS_SINGLE_FROM_S = 60
    private const val LOSS_FLOAT_AGAIN_FROM_S = 120
    private const val LOSS_FIX_AGAIN_FROM_S = 140

    private const val MIXED_DURATION_S = 150
    private const val MIXED_SINGLE_FROM_S = 60
    private const val MIXED_FLOAT_AGAIN_FROM_S = 100

    private const val GAP_DURATION_S = 120
    private const val GAP_FROM_S = 50
    private const val GAP_UNTIL_S = 65

    private const val BASE_DURATION_S = 150
    private const val BASE_CHANGE_AT_S = 70
    private const val BASE_FLOAT_S = 5
    private val baseA = ScenarioBase(eastM = -1000.0, northM = 500.0, upM = 12.0)
    private val baseB = ScenarioBase(eastM = 1000.0, northM = 500.0, upM = 15.0)

    private const val TILT_DURATION_S = 120
    private const val TILT_ALIGNMENT_FROM_S = 20
    private const val TILT_COMPENSATING_FROM_S = 40
    private const val TILT_FAST_MOTION_FROM_S = 90
    private const val TILT_FAST_MOTION_UNTIL_S = 95
    private const val EIGHT_PERIOD_S = 20.0
    private const val EIGHT_EAST_M = 1.2
    private const val EIGHT_NORTH_M = 0.6
    private const val DEGREES_PER_SECOND_HEADING = 3
    private const val DEGREES_PER_SECOND_DIRECTION = 15
    private const val FULL_CIRCLE_DEG = 360
    private const val TILT_BASE_DEG = 8.0
    private const val TILT_SWING_DEG = 6.0
    private const val TILT_COEFFICIENT_NORTH = 1.0
    private const val TILT_COEFFICIENT_EAST = 1.013
    private const val TILT_COEFFICIENT_UP = 0.993

    /** Una solución FIX con las precisiones del RS3, en el punto dado. */
    internal fun fix(eastM: Double, northM: Double, satellites: Int = FIX_SATELLITES): ScenarioState = ScenarioState(
        eastM = eastM,
        northM = northM,
        solution = SolutionStatus.FIX,
        satellites = satellites,
        hdop = HDOP_FIX,
        sigmaLatitudeM = SIGMA_LATITUDE_M,
        sigmaLongitudeM = SIGMA_LONGITUDE_M,
        sigmaAltitudeM = SIGMA_ALTITUDE_M,
        correctionAgeS = CORRECTION_AGE_S,
    )

    /** FLOAT: decímetros de error y menos satélites con corrección. */
    private fun float(second: Int): ScenarioState = ScenarioState(
        eastM = 0.15 * sin(0.3 * second),
        northM = 0.15 * cos(0.2 * second),
        solution = SolutionStatus.FLOAT,
        satellites = 14,
        hdop = 0.9,
        sigmaLatitudeM = 0.35,
        sigmaLongitudeM = 0.30,
        sigmaAltitudeM = 0.6,
        correctionAgeS = CORRECTION_AGE_S,
    )

    /** SINGLE: metros de error, pocos satélites y sin correcciones. */
    private fun single(second: Int): ScenarioState = ScenarioState(
        eastM = 1.8 * sin(0.2 * second),
        northM = 1.5 * cos(0.15 * second),
        solution = SolutionStatus.SINGLE,
        satellites = 7,
        hdop = 2.1,
        sigmaLatitudeM = 1.8,
        sigmaLongitudeM = 1.6,
        sigmaAltitudeM = 3.0,
        correctionAgeS = null,
    )

    /** Quieto en el origen con el temblor de [stableFix]. */
    private fun still(second: Int): ScenarioState =
        fix(JITTER_M * sin(JITTER_EAST_RATE * second), JITTER_M * cos(JITTER_NORTH_RATE * second))

    /** Quieto, en FIX, con 22 satélites y un temblor de 3 mm que no se repite en dos segundos seguidos. */
    val stableFix = Scenario(ScenarioId.STABLE_FIX, STABLE_FIX_DURATION_S) { still(it) }

    /** FIX hasta los 40 s, FLOAT hasta los 60 s, SINGLE hasta los 120 s, FLOAT hasta los 140 s y FIX de nuevo. */
    val fixLossAndRecovery = Scenario(ScenarioId.FIX_LOSS_AND_RECOVERY, LOSS_DURATION_S) { second ->
        when {
            second < LOSS_FLOAT_FROM_S -> still(second)
            second < LOSS_SINGLE_FROM_S -> float(second)
            second < LOSS_FLOAT_AGAIN_FROM_S -> single(second)
            second < LOSS_FIX_AGAIN_FROM_S -> float(second)
            else -> still(second)
        }
    }

    /** FLOAT 60 s, SINGLE 40 s y FLOAT otros 50 s: nunca llega a FIX. */
    val floatAndSingle = Scenario(ScenarioId.FLOAT_AND_SINGLE, MIXED_DURATION_S) { second ->
        when {
            second < MIXED_SINGLE_FROM_S -> float(second)
            second < MIXED_FLOAT_AGAIN_FROM_S -> single(second)
            else -> float(second)
        }
    }

    /** FIX con 15 s (de los 50 a los 65 s) sin ninguna frase; después sigue como si nada. */
    val dataGap = Scenario(ScenarioId.DATA_GAP, GAP_DURATION_S) { second ->
        still(second).copy(emitting = second < GAP_FROM_S || second >= GAP_UNTIL_S)
    }

    /** FIX con la base A hasta los 70 s; 5 s de FLOAT al cambiar y la base B, a 2 km de la A, desde ahí. */
    val baseChange = Scenario(ScenarioId.BASE_CHANGE, BASE_DURATION_S) { second ->
        val before = second < BASE_CHANGE_AT_S
        val changing = second >= BASE_CHANGE_AT_S && second < BASE_CHANGE_AT_S + BASE_FLOAT_S
        val state = if (changing) float(second) else still(second)
        state.copy(base = if (before) baseA else baseB)
    }

    /** Un ocho de 1,2 m por 0,6 m cada 20 s. */
    private fun eight(second: Int): Pair<Double, Double> {
        val angle = 2 * PI * second / EIGHT_PERIOD_S
        return EIGHT_EAST_M * sin(angle) to EIGHT_NORTH_M * sin(2 * angle)
    }

    private fun tiltAt(second: Int): TiltData {
        val state = when {
            second < TILT_ALIGNMENT_FROM_S -> TiltState.SETUP
            second < TILT_COMPENSATING_FROM_S -> TiltState.ALIGNMENT
            else -> TiltState.COMPENSATING
        }
        val notification = if (second in TILT_FAST_MOTION_FROM_S until TILT_FAST_MOTION_UNTIL_S) {
            TiltNotification.FAST_MOTION
        } else {
            TiltNotification.NONE
        }
        val compensating = state == TiltState.COMPENSATING
        val heading = Math.toRadians((second * DEGREES_PER_SECOND_HEADING % FULL_CIRCLE_DEG).toDouble())
        val direction = Math.toRadians((second * DEGREES_PER_SECOND_DIRECTION % FULL_CIRCLE_DEG).toDouble())
        val tilt = Math.toRadians(TILT_BASE_DEG + TILT_SWING_DEG * sin(0.5 * second))
        return TiltData(
            utcMillisOfDay = null,
            state = state,
            notification = notification,
            headingRad = if (state == TiltState.SETUP) null else heading,
            tiltDirectionRad = if (compensating) direction else null,
            tiltRad = if (compensating) tilt else null,
            accuracyCoefficients = if (compensating) {
                TiltAccuracyCoefficients(TILT_COEFFICIENT_NORTH, TILT_COEFFICIENT_EAST, TILT_COEFFICIENT_UP)
            } else {
                null
            },
        )
    }

    /**
     * FIX moviéndose en ocho; la ETC pasa de `SETUP` a `ALIGNMENT` a los 20 s y a `COMPENSATING` a los 40 s, con un aviso de
     * movimiento rápido de los 90 a los 95 s.
     */
    val tiltFigureEight = Scenario(ScenarioId.TILT_FIGURE_EIGHT, TILT_DURATION_S) { second ->
        val (east, north) = eight(second)
        fix(east, north).copy(tilt = tiltAt(second))
    }

    /** A 1 m/s hacia el este los primeros 60 s y hacia el norte los últimos 60 s, en FIX. */
    val straightWalk = Scenario(ScenarioId.STRAIGHT_WALK, STRAIGHT_WALK_DURATION_S) { second ->
        if (second < WALK_LEG_S) {
            fix(second.toDouble(), 0.0, WALK_SATELLITES)
        } else {
            fix(WALK_LEG_S.toDouble(), (second - WALK_LEG_S).toDouble(), WALK_SATELLITES)
        }
    }

    /** Los escenarios, en el orden en que los ofrece la app (S022/RF-14). */
    val all: List<Scenario> = listOf(stableFix, fixLossAndRecovery, floatAndSingle, dataGap, baseChange, tiltFigureEight, straightWalk)

    fun byId(id: ScenarioId): Scenario? = all.firstOrNull { it.id == id }
}
