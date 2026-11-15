package es.topophone.core.gnss.scenario

import es.topophone.core.gnss.nmea.SolutionStatus
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

    /** Quieto, en FIX, con 22 satélites y un temblor de 3 mm que no se repite en dos segundos seguidos. */
    val stableFix = Scenario(ScenarioId.STABLE_FIX, STABLE_FIX_DURATION_S) { second ->
        fix(JITTER_M * sin(JITTER_EAST_RATE * second), JITTER_M * cos(JITTER_NORTH_RATE * second))
    }

    /** A 1 m/s hacia el este los primeros 60 s y hacia el norte los últimos 60 s, en FIX. */
    val straightWalk = Scenario(ScenarioId.STRAIGHT_WALK, STRAIGHT_WALK_DURATION_S) { second ->
        if (second < WALK_LEG_S) {
            fix(second.toDouble(), 0.0, WALK_SATELLITES)
        } else {
            fix(WALK_LEG_S.toDouble(), (second - WALK_LEG_S).toDouble(), WALK_SATELLITES)
        }
    }

    /** Los escenarios que ya existen, en el orden en que los ofrece la app. */
    val all: List<Scenario> get() = listOf(stableFix, straightWalk)

    fun byId(id: ScenarioId): Scenario? = all.firstOrNull { it.id == id }
}
