package es.topophone.core.survey

import kotlin.math.abs

/**
 * El estado del nivel vertical del replanteo (S019/RF-6, RF-8): desmonte si la punta está por encima de la cota de diseño
 * (hay que excavar), terraplén si está por debajo (hay que rellenar) y en cota si la diferencia cabe en la tolerancia vertical.
 */
enum class LevelState { CUT, FILL, ON_GRADE }

/** El aviso que da un cambio del nivel (S019/RF-9): ha entrado en cota o ha salido de ella. */
enum class LevelAlert { ARRIVED, LEFT }

/** El estado del nivel después de una época y el aviso que hay que dar, si lo hay. */
data class LevelStep(val state: LevelState, val alert: LevelAlert?)

/**
 * El nivel vertical y sus avisos como una función pura (S019/A1), con el mismo criterio que [StakeoutZones]: con el ruido del
 * GNSS, un borde sin margen repetiría el aviso a cada época, así que se sale de la cota con 5 mm más de la tolerancia vertical
 * (RF-9). No sabe nada de la distancia horizontal: la llegada de S018 se calcula aparte (RF-12).
 */
object StakeoutLevel {
    /** S019/RF-9: lo que se pasa del borde antes de dar por hecha la salida de la cota. */
    const val MARGIN_M = 0.005

    /** El estado de la diferencia de cota [dzM] (punta menos cota de diseño) con la tolerancia vertical [toleranceM], sin historial. */
    fun classify(dzM: Double, toleranceM: Double): LevelState = when {
        abs(dzM) <= toleranceM -> LevelState.ON_GRADE
        dzM > 0.0 -> LevelState.CUT
        else -> LevelState.FILL
    }

    /**
     * El estado tras una época con la diferencia de cota [dzM], estando antes en [current] (null: sin estado previo, al
     * empezar o al volver la guía). Entrar en cota avisa [LevelAlert.ARRIVED]; salir, [LevelAlert.LEFT], y entre la
     * tolerancia y 5 mm más no cambia nada.
     */
    fun next(current: LevelState?, dzM: Double, toleranceM: Double): LevelStep {
        val state = classify(dzM, toleranceM)
        return when {
            current == LevelState.ON_GRADE -> when {
                abs(dzM) <= toleranceM + MARGIN_M -> LevelStep(LevelState.ON_GRADE, null)
                else -> LevelStep(state, LevelAlert.LEFT)
            }
            state == LevelState.ON_GRADE -> LevelStep(LevelState.ON_GRADE, LevelAlert.ARRIVED)
            else -> LevelStep(state, null)
        }
    }
}
