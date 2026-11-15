package es.topophone.app.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CurrentEquipment
import es.topophone.app.receiver.CurrentTilt
import es.topophone.core.model.guide.CheckStatus
import es.topophone.core.model.guide.ConfigList
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.guide.ConfigLists
import es.topophone.data.config.ConfigProgressRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Cuántos pasos de una lista están hechos y cuántos comprobados por la app (S043/RF-4). */
data class GuideCounts(val done: Int, val total: Int, val checked: Int, val checkable: Int)

/**
 * Lo que muestra la Guía (S043/RF-1 a RF-4).
 *
 * @property applies hay un receptor de verdad (Bluetooth o Wi-Fi): con el móvil, el virtual o una reproducción las listas no aplican.
 * @property done los pasos que el usuario tiene hechos en este receptor, por lista.
 * @property stepStatus el resultado de la última comprobación de cada paso comprobable; vacío hasta que se compruebe (RF-5).
 */
data class GuideUiState(
    val applies: Boolean = false,
    val receiverKey: String? = null,
    val lists: List<ConfigList> = ConfigLists.all,
    val done: Map<ConfigListId, Set<String>> = emptyMap(),
    val stepStatus: Map<String, CheckStatus> = emptyMap(),
) {
    /** Los pasos hechos y comprobados de [list]: un paso está comprobado si su última comprobación dio «Correcto». */
    fun counts(list: ConfigList): GuideCounts {
        val checkable = list.steps.filter { it.checkable }
        return GuideCounts(
            done = list.steps.count { it.id in done[list.id].orEmpty() },
            total = list.steps.size,
            checked = checkable.count { stepStatus[it.id] == CheckStatus.OK },
            checkable = checkable.size,
        )
    }
}

/** La Guía del menú del receptor: las cuatro listas de configuración y su progreso en cada receptor (S043/RF-1 a RF-4). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(private val progress: ConfigProgressRepository, tilt: CurrentTilt) : ViewModel() {

    private val key: Flow<String?> = tilt.state.map { it.key }.distinctUntilChanged()

    private val doneByList: Flow<Pair<String?, Map<ConfigListId, Set<String>>>> = key.flatMapLatest { receiver ->
        if (!CurrentEquipment.appliesTo(receiver)) {
            flowOf(receiver to emptyMap())
        } else {
            combine(ConfigLists.all.map { list -> progress.done(receiver!!, list.id).map { list.id to it } }) { pairs ->
                receiver to pairs.toMap()
            }
        }
    }

    val state: StateFlow<GuideUiState> = doneByList.map { (receiver, done) ->
        GuideUiState(applies = CurrentEquipment.appliesTo(receiver), receiverKey = receiver, done = done)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GuideUiState())

    /** Marca o desmarca un paso para el receptor en uso (RF-3); sin receptor de verdad no hace nada. */
    fun setDone(list: ConfigListId, stepId: String, done: Boolean) {
        val receiver = realKey() ?: return
        viewModelScope.launch { progress.setDone(receiver, list, stepId, done) }
    }

    /** Desmarca todos los pasos de una lista para el receptor en uso, sin tocar las demás (RF-3). */
    fun reset(list: ConfigListId) {
        val receiver = realKey() ?: return
        viewModelScope.launch { progress.reset(receiver, list) }
    }

    private fun realKey(): String? = state.value.receiverKey?.takeIf { CurrentEquipment.appliesTo(it) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
