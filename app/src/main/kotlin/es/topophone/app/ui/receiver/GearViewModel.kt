package es.topophone.app.ui.receiver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.model.point.PoleKind
import es.topophone.core.model.project.ProjectNames
import es.topophone.core.survey.ActiveGear
import es.topophone.core.survey.AntennaProfile
import es.topophone.core.survey.GearProblemField
import es.topophone.core.survey.SavedPole
import es.topophone.data.gear.GearRepository
import es.topophone.data.gear.GearResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Sobre qué se hizo la operación que da un aviso. */
enum class GearTarget { PROFILE, POLE }

/** El último aviso de la pantalla "Antena y jalón" (S046/RF-4, RF-6, RF-10 a RF-12, RF-19, RF-27). */
sealed interface GearMessage {
    /** Lo escrito no vale (RF-4, RF-10). */
    data class Invalid(val target: GearTarget, val field: GearProblemField) : GearMessage

    /** El perfil lo usan estos receptores y no se borra (RF-6). */
    data class ProfileInUse(val receivers: List<String>) : GearMessage

    /** Es el último jalón y no se borra (RF-11). */
    data object LastPole : GearMessage

    /** Es el jalón activo de estos receptores: hay que elegir antes otro (RF-12). */
    data class PoleActive(val receivers: List<String>) : GearMessage

    /** Con una medida en curso no se cambia el equipo (RF-27). */
    data object Locked : GearMessage

    /** El jalón activo es inclinado y su altura vertical cambia con el perfil nuevo (RF-19). */
    data class ProfileChanged(val slantPoleName: String) : GearMessage
}

/** Lo que muestra la pantalla "Antena y jalón" (S046/RF-1 a RF-19). */
data class GearUiState(
    val profiles: List<AntennaProfile> = emptyList(),
    val poles: List<SavedPole> = emptyList(),
    val gear: ActiveGear = CurrentGear.DEFAULT,
    /** Con una medida o el modo automático en curso no se cambia de jalón ni de perfil (RF-27). */
    val locked: Boolean = false,
    val message: GearMessage? = null,
)

/** Lo escrito de un perfil de antena nuevo a partir de uno existente (S046/RF-5). */
data class ProfileDraft(val name: String, val offsetText: String, val radiusText: String)

/** La pantalla "Antena y jalón" del menú del receptor: perfiles de antena, jalones guardados y los activos (S046). */
@HiltViewModel
class GearViewModel @Inject constructor(
    private val repository: GearRepository,
    private val current: CurrentGear,
    private val inProgress: MeasurementInProgress,
) : ViewModel() {

    private val message = MutableStateFlow<GearMessage?>(null)

    val state: StateFlow<GearUiState> = combine(
        repository.profiles(),
        repository.poles(),
        current.state,
        combine(inProgress.active, message) { locked, message -> locked to message },
    ) { profiles, poles, gear, (locked, message) -> GearUiState(profiles, poles, gear, locked, message) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GearUiState(gear = current.state.value))

    fun dismissMessage() = message.update { null }

    /** El perfil de antena del receptor (S046/RF-7, RF-8); avisa si cambia la altura de un jalón inclinado (RF-19). */
    fun selectProfile(ref: String) {
        if (blocked()) return
        viewModelScope.launch {
            val slant = current.state.value.pole?.takeIf { it.kind == PoleKind.SLANT }
            current.setProfile(ref)
            message.value = slant?.let { GearMessage.ProfileChanged(it.name) }
        }
    }

    /** El jalón activo del receptor (S046/RF-13, RF-14). */
    fun selectPole(id: String) {
        if (blocked()) return
        viewModelScope.launch {
            current.setActivePole(id)
            message.value = null
        }
    }

    /** Los datos de un perfil como punto de partida de uno propio, con "<nombre> (copia)" (S046/RF-5). */
    fun profileDraft(ref: String, copyLabel: String): ProfileDraft? {
        val profile = state.value.profiles.firstOrNull { it.ref == ref } ?: return null
        val name = ProjectNames.copyName(profile.name, state.value.profiles.map { it.name }, copyLabel)
        return ProfileDraft(name, plain(profile.offsetM), profile.radiusM?.let(::plain).orEmpty())
    }

    /** Alta (`id` null) o edición de un perfil propio; `onDone(true)` si se guardó (S046/RF-3 a RF-5). */
    fun saveProfile(id: String?, name: String, offsetText: String, radiusText: String, onDone: (Boolean) -> Unit = {}) {
        if (id != null && "own:$id" == current.state.value.profile.ref && blocked()) return onDone(false) // S046/RF-27
        viewModelScope.launch {
            val saved = report(GearTarget.PROFILE, repository.saveProfile(id, name, offsetText, radiusText))
            onDone(saved)
        }
    }

    fun deleteProfile(id: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onDone(report(GearTarget.PROFILE, repository.deleteProfile(id))) }
    }

    /** Alta (`id` null) o edición de un jalón; `onDone(true)` si se guardó (S046/RF-9, RF-10, RF-16). */
    fun savePole(id: String?, name: String, kind: PoleKind, heightText: String, onDone: (Boolean) -> Unit = {}) {
        if (id != null && id == current.state.value.pole?.id && blocked()) return onDone(false) // S046/RF-27
        viewModelScope.launch {
            val saved = report(GearTarget.POLE, repository.savePole(id, name, kind, heightText))
            onDone(saved)
        }
    }

    fun deletePole(id: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onDone(report(GearTarget.POLE, repository.deletePole(id))) }
    }

    private fun blocked(): Boolean {
        if (!inProgress.active.value) return false
        message.value = GearMessage.Locked
        return true
    }

    /** Guarda el aviso de [result] y dice si la operación se hizo (S046/RF-4, RF-6, RF-11, RF-12). */
    private fun report(target: GearTarget, result: GearResult): Boolean {
        message.value = when (result) {
            is GearResult.Invalid -> GearMessage.Invalid(target, result.field)
            is GearResult.InUse -> GearMessage.ProfileInUse(result.receivers)
            GearResult.LastPole -> GearMessage.LastPole
            is GearResult.ActiveElsewhere -> GearMessage.PoleActive(result.receivers)
            else -> null
        }
        return result is GearResult.Saved || result == GearResult.Deleted
    }

    private fun plain(value: Double): String = value.toBigDecimal().toPlainString()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
