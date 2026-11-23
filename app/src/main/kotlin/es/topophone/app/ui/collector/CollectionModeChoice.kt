package es.topophone.app.ui.collector

import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.MeasurementMode

/** Las cuatro opciones de la pastilla «Modo» (S060/RF-9). */
enum class CollectionMode { INSTANT, AVERAGED, AUTO_TIME, AUTO_DISTANCE }

/**
 * Traduce las cuatro opciones de «Modo» y el valor de «Tiempo» a las acciones que ya existen (`onMode`, `onAutoEnabled`,
 * `onAutoTrigger`, `onSeconds`, `onAutoInterval`, `onAutoDistance`), sin tocar su lógica (S060/RF-13, RF-32).
 */
object CollectionModes {
    /** El modo que se ve ahora: el automático manda sobre el modo de medida (S060/RF-9). */
    fun current(state: CollectorUiState): CollectionMode = when {
        state.autoEnabled && state.settings.autoTrigger == AutoTrigger.TIME -> CollectionMode.AUTO_TIME
        state.autoEnabled -> CollectionMode.AUTO_DISTANCE
        state.settings.mode == MeasurementMode.AVERAGED -> CollectionMode.AVERAGED
        else -> CollectionMode.INSTANT
    }

    /**
     * Elige [target]. Instantáneo y promediado apagan el automático si estaba encendido; los automáticos fijan el disparo y lo
     * encienden y no tocan el modo de medida de cada punto (S017/RF-15).
     */
    fun choose(target: CollectionMode, state: CollectorUiState, actions: CollectorActions) {
        when (target) {
            CollectionMode.INSTANT, CollectionMode.AVERAGED -> {
                if (state.autoEnabled) actions.onAutoEnabled(false)
                actions.onMode(if (target == CollectionMode.INSTANT) MeasurementMode.INSTANT else MeasurementMode.AVERAGED)
            }
            CollectionMode.AUTO_TIME, CollectionMode.AUTO_DISTANCE -> {
                actions.onAutoTrigger(if (target == CollectionMode.AUTO_TIME) AutoTrigger.TIME else AutoTrigger.DISTANCE)
                if (!state.autoEnabled) actions.onAutoEnabled(true)
            }
        }
    }

    /** El valor de la pastilla «Tiempo»: el promediado, el intervalo o la distancia; null en instantáneo (S060/RF-10). */
    fun timeText(state: CollectorUiState, distanceSymbol: String): String? = when (current(state)) {
        CollectionMode.INSTANT -> null
        CollectionMode.AVERAGED -> state.averagingSecondsText
        CollectionMode.AUTO_TIME -> state.autoIntervalText
        CollectionMode.AUTO_DISTANCE -> "${state.autoDistanceText} $distanceSymbol"
    }

    /** En instantáneo la pastilla «Tiempo» no tiene selector (S060/RF-10). */
    fun timeEditable(mode: CollectionMode): Boolean = mode != CollectionMode.INSTANT

    /** Aplica el valor escrito en el selector de «Tiempo» a la acción de su modo (S060/RF-11). */
    fun applyTime(mode: CollectionMode, text: String, actions: CollectorActions) {
        when (mode) {
            CollectionMode.INSTANT -> Unit
            CollectionMode.AVERAGED -> actions.onSeconds(text)
            CollectionMode.AUTO_TIME -> actions.onAutoInterval(text)
            CollectionMode.AUTO_DISTANCE -> actions.onAutoDistance(text)
        }
    }
}
