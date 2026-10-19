package es.topophone.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.log.LogSize
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.log.LogPreferences
import es.topophone.data.receiver.CorrectionsAlarm
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.data.receiver.SessionSpace
import es.topophone.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Estado global de la app que vive fuera de la interfaz: el tema guardado (S001/RF-15). */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val receiverPreferences: ReceiverPreferences,
    private val logPreferences: LogPreferences,
) : ViewModel() {

    /** `null` hasta leer el tema guardado: así no se pinta un tema equivocado al arrancar. */
    val themeState: StateFlow<ThemeState?> = settings.themeState.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleSun() {
        viewModelScope.launch { settings.toggleSun() }
    }

    fun selectTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.selectTheme(mode) }
    }

    /** Espacio para sesiones (S003/RF-26). */
    val sessionSpaceMb: StateFlow<Int> =
        receiverPreferences.sessionSpaceMb.stateIn(viewModelScope, SharingStarted.Eagerly, SessionSpace.DEFAULT_MB)

    val correctionsAlarmS: StateFlow<Int> =
        receiverPreferences.correctionsAlarmS.stateIn(viewModelScope, SharingStarted.Eagerly, CorrectionsAlarm.DEFAULT_S)

    /** S007/RF-31. */
    fun selectCorrectionsAlarm(seconds: Int) {
        viewModelScope.launch { receiverPreferences.saveCorrectionsAlarmS(seconds) }
    }

    fun selectSessionSpace(mb: Int) {
        viewModelScope.launch { receiverPreferences.saveSessionSpaceMb(mb) }
    }

    /** Tamaño del log (S014/RF-8, RF-9). */
    val logSizeMb: StateFlow<Int> = logPreferences.sizeMb.stateIn(viewModelScope, SharingStarted.Eagerly, LogSize.DEFAULT_MB)

    fun selectLogSize(mb: Int) {
        viewModelScope.launch { logPreferences.saveSizeMb(mb) }
    }
}
