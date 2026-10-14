package es.topophone.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Estado global de la app que vive fuera de la interfaz: el tema guardado (S001/RF-15). */
@HiltViewModel
class MainViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {

    /** `null` hasta leer el tema guardado: así no se pinta un tema equivocado al arrancar. */
    val themeState: StateFlow<ThemeState?> = settings.themeState.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleSun() {
        viewModelScope.launch { settings.toggleSun() }
    }

    fun selectTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.selectTheme(mode) }
    }
}
