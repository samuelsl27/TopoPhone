package es.topophone.app.ui.equipment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentEquipment
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.survey.AntennaCatalog
import es.topophone.core.survey.AntennaProfile
import es.topophone.data.equipment.EquipmentRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** El último aviso de «Datos del receptor» (S043/RF-18, RF-21). */
sealed interface EquipmentMessage {
    /** Se guardaron el modelo, la serie y el firmware. */
    data object Saved : EquipmentMessage

    /** El receptor pasó a usar el perfil de antena de [profileName] (RF-21). */
    data class ProfileUsed(val profileName: String) : EquipmentMessage

    /** Con una medida en curso no se cambia el perfil de antena (S046/RF-27). */
    data object Locked : EquipmentMessage
}

/**
 * Lo que muestra «Datos del receptor» (S043/RF-18, RF-21, RF-22, RF-23).
 *
 * @property applies hay un receptor de verdad (Bluetooth o Wi-Fi): con el móvil, el virtual o una reproducción no aplica.
 * @property saved lo guardado para ese receptor, o null.
 * @property picked el modelo del catálogo que se acaba de elegir, cuyo perfil de antena se propone (RF-21).
 * @property currentProfile el perfil de antena que tiene el receptor ahora.
 */
data class EquipmentUiState(
    val applies: Boolean = false,
    val receiverKey: String? = null,
    val saved: EquipmentProfile? = null,
    val catalog: List<AntennaProfile> = AntennaCatalog.ALL,
    val picked: AntennaProfile? = null,
    val currentProfile: AntennaProfile? = null,
    val locked: Boolean = false,
    val message: EquipmentMessage? = null,
)

/** «Datos del receptor» del menú del receptor: modelo, serie y firmware de cada receptor, y su perfil de antena (S043/RF-18, RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EquipmentViewModel @Inject constructor(
    private val repository: EquipmentRepository,
    private val tilt: CurrentTilt,
    private val gear: CurrentGear,
    private val inProgress: MeasurementInProgress,
) : ViewModel() {

    private val picked = MutableStateFlow<Pair<String?, AntennaProfile>?>(null)
    private val message = MutableStateFlow<EquipmentMessage?>(null)

    private val key: Flow<String?> = tilt.state.map { it.key }.distinctUntilChanged()

    private val saved: Flow<Pair<String?, EquipmentProfile?>> = key.flatMapLatest { receiver ->
        if (CurrentEquipment.appliesTo(receiver)) repository.profile(receiver!!).map { receiver to it } else flowOf(receiver to null)
    }

    val state: StateFlow<EquipmentUiState> = combine(
        saved,
        gear.state,
        combine(picked, message, inProgress.active) { picked, message, locked -> Triple(picked, message, locked) },
    ) { (receiver, profile), activeGear, (picked, message, locked) ->
        EquipmentUiState(
            applies = CurrentEquipment.appliesTo(receiver),
            receiverKey = receiver,
            saved = profile,
            picked = picked?.takeIf { it.first == receiver }?.second,
            currentProfile = activeGear.profile,
            locked = locked,
            message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EquipmentUiState())

    /** Guarda el modelo, la serie y el firmware del receptor en uso (RF-18); sin receptor de verdad no hace nada. */
    fun save(model: String, serial: String, firmware: String) {
        val receiver = state.value.receiverKey?.takeIf { CurrentEquipment.appliesTo(it) } ?: return
        viewModelScope.launch {
            repository.save(receiver, EquipmentProfile(model, serial, firmware))
            message.value = EquipmentMessage.Saved
        }
    }

    /** Se elige un modelo del catálogo: devuelve su nombre para el campo y propone su perfil de antena sin cambiar el actual (RF-21). */
    fun pick(ref: String): String? {
        val profile = AntennaCatalog.byRef(ref) ?: return null
        picked.value = state.value.receiverKey to profile
        message.value = null
        return profile.name
    }

    /** El usuario pulsa «Usar su perfil de antena»: solo entonces cambia el perfil del receptor (RF-21). */
    fun useProposedProfile() {
        val proposed = state.value.picked ?: return
        if (inProgress.active.value) {
            message.value = EquipmentMessage.Locked
            return
        }
        viewModelScope.launch {
            gear.setProfile(proposed.ref)
            picked.value = null
            message.value = EquipmentMessage.ProfileUsed(proposed.name)
        }
    }

    fun dismissMessage() = message.update { null }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
