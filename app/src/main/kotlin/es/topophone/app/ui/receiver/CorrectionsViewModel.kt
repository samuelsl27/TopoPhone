package es.topophone.app.ui.receiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CorrectionsController
import es.topophone.data.ntrip.NtripProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Perfiles NTRIP de la pantalla Correcciones; conectar y detener van por [CorrectionsController] (S007). */
@HiltViewModel
class CorrectionsViewModel @Inject constructor(
    private val profiles: NtripProfileRepository,
    private val controller: CorrectionsController,
) : ViewModel() {

    val state: StateFlow<CorrectionsUiState> = combine(profiles.profiles(), controller.status) { list, status ->
        CorrectionsUiState(list, status.activeProfileId, status.passwordNeededFor)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CorrectionsUiState())

    fun connect(id: String) {
        viewModelScope.launch { controller.connect(id) } // S007/RF-22
    }

    fun stop() = controller.stop() // S007/RF-25

    /** Si es el perfil en uso, el controlador cierra la conexión (S007/RF-8). */
    fun delete(id: String) {
        viewModelScope.launch { profiles.delete(id) } // S007/RF-7
    }

    fun submitPassword(id: String, password: String) {
        viewModelScope.launch { controller.connect(id, password) } // S007/RF-6
    }

    fun dismissPassword() = controller.dismissPasswordPrompt()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
