package es.topophone.app.ui.receiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentTilt
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.TiltMode
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** El apartado "Inclinación" del menú del receptor (S016/RF-1, RF-3, RF-4). */
@HiltViewModel
class TiltViewModel @Inject constructor(private val tilt: CurrentTilt, private val log: AppLog) : ViewModel() {

    val state: StateFlow<TiltUiState> = tilt.state.map { TiltUiState(it.settings) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TiltUiState(tilt.state.value.settings))

    /** Elegido a mano: queda sin comprobar (S016/RF-3). */
    fun choose(mode: TiltMode) {
        viewModelScope.launch {
            tilt.choose(mode)
            log.info(LogCategory.RECEIVER, "Modo de inclinación: ${mode.logName()} (sin comprobar)")
            if (mode == TiltMode.VERTICAL && tilt.state.value.settings.detected == TiltMode.RECEIVER) {
                log.warning(LogCategory.RECEIVER, "Jalón vertical con un receptor que compensa") // S016/RF-4, S014/RF-5
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** El modo en el log, en español y sin depender del idioma de la interfaz (S014). */
internal fun TiltMode.logName(): String = when (this) {
    TiltMode.APP -> "calculada por la app"
    TiltMode.RECEIVER -> "compensada por el receptor"
    TiltMode.VERTICAL -> "jalón vertical"
}
