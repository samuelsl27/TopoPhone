package es.topophone.app.receiver

import es.topophone.core.model.point.TiltMode
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.data.receiver.TiltPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * El receptor en uso y su modo de inclinación (S016/RF-1, RF-2; plan A8).
 *
 * @property key la dirección Bluetooth del receptor, [TiltPreferences.REPLAY] con una reproducción, o null si no hay
 *   ninguno.
 * @property revision sube cada vez que se guarda el modo o se abre el asistente: el recolector cancela la medida si
 *   cambia durante ella (S016/RF-28; plan A9).
 */
data class CurrentTiltState(
    val key: String? = null,
    val settings: ReceiverTiltSettings = ReceiverTiltSettings.NONE,
    val wizardOpen: Boolean = false,
    val revision: Int = 0,
)

/** Lo comparten el apartado "Inclinación", el asistente, el recolector y las coordenadas (@Singleton en la app). */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentTilt(replaying: Flow<Boolean>, address: Flow<String?>, private val preferences: TiltPreferences, scope: CoroutineScope) {
    /** Asistente abierto y revisión juntos, para que cambien a la vez (S016/RF-28). */
    private data class Flags(val wizardOpen: Boolean = false, val revision: Int = 0)

    private val flags = MutableStateFlow(Flags())

    private val key: Flow<String?> = combine(replaying, address) { replay, receiver -> if (replay) TiltPreferences.REPLAY else receiver }
        .distinctUntilChanged()

    private val settings: Flow<Pair<String?, ReceiverTiltSettings>> = key.flatMapLatest { key ->
        if (key == null) flowOf(null to ReceiverTiltSettings.NONE) else preferences.settings(key).map { key to it }
    }

    val state: StateFlow<CurrentTiltState> = combine(settings, flags) { (key, settings), flags ->
        CurrentTiltState(key, settings, flags.wizardOpen, flags.revision)
    }.stateIn(scope, SharingStarted.Eagerly, CurrentTiltState())

    /** Elegido a mano: "sin comprobar", sin olvidar lo que encontró el asistente (S016/RF-3, RF-4). */
    suspend fun choose(mode: TiltMode) = save { it.copy(mode = mode, verified = false) }

    /** Confirmado en el asistente: "comprobado" (S016/RF-10). */
    suspend fun confirm(mode: TiltMode) = save { ReceiverTiltSettings(mode, verified = true, detected = mode) }

    fun openWizard() {
        flags.update { Flags(wizardOpen = true, revision = it.revision + 1) }
    }

    fun closeWizard() {
        flags.update { it.copy(wizardOpen = false) }
    }

    companion object {
        /** Sin receptor ni modos guardados: el valor por defecto de quien no lo recibe por Hilt (tests). */
        val NONE: CurrentTilt by lazy {
            CurrentTilt(flowOf(false), flowOf(null), NoTiltPreferences, CoroutineScope(Dispatchers.Unconfined))
        }
    }

    private object NoTiltPreferences : TiltPreferences {
        override fun settings(key: String): Flow<ReceiverTiltSettings> = flowOf(ReceiverTiltSettings.NONE)

        override suspend fun save(key: String, settings: ReceiverTiltSettings) = Unit
    }

    private suspend fun save(change: (ReceiverTiltSettings) -> ReceiverTiltSettings) {
        val key = state.value.key ?: return
        // La revisión sube antes: el modo nuevo nunca se ve con la revisión vieja (S016/RF-28).
        flags.update { it.copy(revision = it.revision + 1) }
        preferences.save(key, change(preferences.settings(key).first()))
    }
}
