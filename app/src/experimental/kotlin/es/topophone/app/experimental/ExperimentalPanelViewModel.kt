package es.topophone.app.experimental

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.data.equipment.EquipmentRepository
import es.topophone.receiver.experimental.ReachPart
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que enseña el panel del módulo (S044/RF-6, RF-11, RF-12, RF-15). */
data class PanelUiState(
    val source: ReachSource = ReachSource.Inactive,
    val info: ReachInfoState = ReachInfoState.Idle,
    /** El perfil de equipo propuesto, pendiente de confirmar; `null` si no hay propuesta abierta (RF-12). */
    val profileProposal: EquipmentProfile? = null,
    /** La última IP escrita no era una IP privada (RF-23). */
    val addressRejected: Boolean = false,
    val profileSaved: Boolean = false,
)

/**
 * El panel del módulo: junta la sesión (dirección y lectura) con lo que solo vive en la pantalla (la propuesta de perfil, el rechazo de
 * una IP y el aviso de guardado). El único dato que guarda es el perfil de equipo que el usuario confirma (S044/RF-12, RF-25).
 */
@HiltViewModel
class ExperimentalPanelViewModel @Inject constructor(
    private val session: ExperimentalSession,
    private val equipment: EquipmentRepository,
) : ViewModel() {

    private data class Local(val proposal: EquipmentProfile? = null, val addressRejected: Boolean = false, val saved: Boolean = false)

    private val local = MutableStateFlow(Local())

    val state: StateFlow<PanelUiState> = combine(session.source, session.info, local) { source, info, local ->
        PanelUiState(source, info, local.proposal, local.addressRejected, local.saved)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PanelUiState())

    fun onRefresh() = session.refresh()

    fun onSaveAddress(text: String) {
        viewModelScope.launch {
            val saved = session.saveAddress(text)
            local.update { it.copy(addressRejected = !saved) }
        }
    }

    /** Propone el modelo, la serie y el firmware leídos; no guarda nada hasta [onConfirmProfile] (S044/RF-12). */
    fun onFillProfile() {
        val identity = ((session.info.value as? ReachInfoState.Loaded)?.reading?.identity as? ReachPart.Ok)?.value ?: return
        local.update { it.copy(proposal = EquipmentProfile(identity.model, identity.serial, identity.firmware), saved = false) }
    }

    fun onConfirmProfile() {
        val proposal = local.value.proposal ?: return
        val key = (session.source.value as? ReachSource.Ready)?.key ?: return
        viewModelScope.launch {
            equipment.save(key, proposal)
            local.update { it.copy(proposal = null, saved = true) }
        }
    }

    fun onCancelProfile() = local.update { it.copy(proposal = null) }

    fun onDismissSaved() = local.update { it.copy(saved = false) }
}
