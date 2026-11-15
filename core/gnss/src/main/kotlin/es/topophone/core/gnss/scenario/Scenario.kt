package es.topophone.core.gnss.scenario

import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltData

/** Los escenarios del receptor virtual (S022/RF-14). Su nombre y su descripción son textos de la app, no de este módulo. */
enum class ScenarioId {
    STABLE_FIX,
    FIX_LOSS_AND_RECOVERY,
    FLOAT_AND_SINGLE,
    DATA_GAP,
    BASE_CHANGE,
    TILT_FIGURE_EIGHT,
    STRAIGHT_WALK,
}

/** Dónde ocurre el escenario: latitud y longitud en grados y altura elipsoidal en metros (S022/RF-13, plan D5). */
data class ScenarioOrigin(val latitudeDeg: Double, val longitudeDeg: Double, val ellipsoidalHeightM: Double = DEFAULT_HEIGHT_M) {
    companion object {
        /** Altura elipsoidal cuando el origen no la trae (las coordenadas escritas a mano no la llevan). */
        const val DEFAULT_HEIGHT_M = 100.0
    }
}

/** Posición de una base respecto al origen del escenario, en metros hacia el este, el norte y arriba. */
data class ScenarioBase(val eastM: Double, val northM: Double, val upM: Double = 0.0)

/**
 * Lo que ocurre en un segundo de un escenario (S022/RF-14, RF-15, plan D4): dónde está el jalón respecto al origen, qué solución
 * tiene y qué frases se emiten. Con [emitting] falso no sale ninguna frase (corte de datos). La [tilt] y la [base] hacen que se
 * emitan también la ETC y la EBP.
 */
data class ScenarioState(
    val eastM: Double,
    val northM: Double,
    val solution: SolutionStatus,
    val satellites: Int,
    val hdop: Double,
    val sigmaLatitudeM: Double,
    val sigmaLongitudeM: Double,
    val sigmaAltitudeM: Double,
    val correctionAgeS: Double?,
    val upM: Double = 0.0,
    val emitting: Boolean = true,
    val base: ScenarioBase? = null,
    val tilt: TiltData? = null,
)

/**
 * Guion de duración fija: para cada segundo, de 0 a [durationS] − 1, un [ScenarioState]. Sin azar: el estado de un segundo
 * depende solo de él (S022/RF-14).
 */
class Scenario(val id: ScenarioId, val durationS: Int, private val state: (second: Int) -> ScenarioState) {
    init {
        require(durationS > 0) { "La duración debe ser positiva" }
    }

    fun at(second: Int): ScenarioState {
        require(second in 0 until durationS) { "Segundo $second fuera del escenario (0 a ${durationS - 1})" }
        return state(second)
    }
}
