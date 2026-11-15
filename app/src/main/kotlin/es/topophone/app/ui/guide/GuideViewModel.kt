package es.topophone.app.ui.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.receiver.CheckRunner
import es.topophone.app.receiver.CurrentEquipment
import es.topophone.app.receiver.CurrentTilt
import es.topophone.core.model.guide.CheckStatus
import es.topophone.core.model.guide.ConfigList
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.guide.ConfigLists
import es.topophone.core.model.guide.ReceiverCheck
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.data.config.ConfigProgressRepository
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
    /** Se puede comprobar lo que llega: con un receptor real, una reproducción o el receptor virtual, no con el móvil (RF-5, RF-22, RF-23). */
    val canCheck: Boolean = false,
    val checking: Boolean = false,
    /** Las comprobaciones de la última vez, o null si aún no se ha comprobado nada con este receptor. */
    val results: List<ReceiverCheck>? = null,
    /** La lista con la que se comprobó (null: sin lista, con el receptor virtual o una reproducción). */
    val checkedList: ConfigListId? = null,
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
class GuideViewModel @Inject constructor(
    private val progress: ConfigProgressRepository,
    tilt: CurrentTilt,
    private val runner: CheckRunner,
) : ViewModel() {

    private class Run(val key: String?, val list: ConfigListId?, val checks: List<ReceiverCheck>)

    private val lastRun = MutableStateFlow<Run?>(null)
    private val running = MutableStateFlow(false)

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

    val state: StateFlow<GuideUiState> = combine(doneByList, lastRun, running) { (receiver, done), run, running ->
        val visible = run?.takeIf { it.key == receiver }
        GuideUiState(
            applies = CurrentEquipment.appliesTo(receiver),
            receiverKey = receiver,
            done = done,
            stepStatus = stepStatus(visible),
            canCheck = canCheck(receiver),
            checking = running,
            results = visible?.checks,
            checkedList = visible?.list,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GuideUiState())

    /** Comprueba lo que llega del receptor con las comprobaciones de [list] (o todas, sin lista); solo lee (RF-5, RF-11). */
    fun check(list: ConfigListId?) {
        val receiver = state.value.receiverKey ?: return
        if (!canCheck(receiver) || running.value) return
        viewModelScope.launch {
            running.value = true
            try {
                lastRun.value = Run(receiver, list, runner.run(list))
            } finally {
                running.value = false
            }
        }
    }

    private fun canCheck(receiver: String?): Boolean = receiver != null && !ReceiverKey.isPhone(receiver)

    /** El peor resultado de las comprobaciones de cada paso de la lista comprobada; los pasos sin resultado no salen (RF-4). */
    private fun stepStatus(run: Run?): Map<String, CheckStatus> {
        val list = run?.list?.let { ConfigLists.byId(it) } ?: return emptyMap()
        return buildMap {
            for (step in list.steps) {
                val statuses = run.checks.filter { it.id in step.checks }.map { it.status }
                if (statuses.isNotEmpty()) put(step.id, statuses.maxBy { severity(it) })
            }
        }
    }

    private fun severity(status: CheckStatus): Int = when (status) {
        CheckStatus.OK -> 0
        CheckStatus.NO_DATA -> 1
        CheckStatus.WARNING -> 2
        CheckStatus.FAIL -> 3
    }

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
