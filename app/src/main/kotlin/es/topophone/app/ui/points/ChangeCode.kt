package es.topophone.app.ui.points

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.app.ui.collector.CodePickerDialog
import es.topophone.app.ui.collector.CollectorCodeSource
import es.topophone.app.ui.collector.CollectorCodeUi
import es.topophone.app.ui.collector.CollectorCodes
import es.topophone.app.ui.projects.ConfirmDialog
import es.topophone.core.model.codes.AttributeCarryOver
import es.topophone.core.model.codes.SurveyCode
import es.topophone.data.point.CodeChangeResult
import es.topophone.data.point.PointRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Un valor no vacío que se perdería al cambiar de código (S024/RF-15). */
data class DiscardedValue(val pointName: String, val attribute: String)

/**
 * Lo que muestra «Cambiar código» (S024/RF-13, RF-15): el selector de códigos del proyecto, o la lista de valores que se perderían a la
 * espera de confirmar. [result] es lo que pasó al aplicarlo.
 */
data class ChangeCodeUi(
    val open: Boolean = false,
    val picker: CollectorCodeUi = CollectorCodeUi(),
    val count: Int = 0,
    val target: SurveyCode? = null,
    val warning: List<DiscardedValue>? = null,
    val result: CodeChangeResult? = null,
)

/** Lo que el usuario hace en «Cambiar código». */
class ChangeCodeActions(
    val onQuery: (String) -> Unit = {},
    val onChoose: (SurveyCode?) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
)

/**
 * Cambia el código de uno o varios puntos (S024/RF-13 a RF-16): elige otro código del proyecto, o ninguno; avisa de los valores no
 * vacíos que se perderían y, confirmado, lo aplica a todos o a ninguno.
 */
class ChangeCode(private val scope: CoroutineScope, source: CollectorCodeSource, private val points: PointRepository) {
    private val projectId = MutableStateFlow<String?>(null)
    private val picker = CollectorCodes(scope, source, projectId)
    private val own = MutableStateFlow(ChangeCodeUi())
    private var ids: List<String> = emptyList()

    val ui: StateFlow<ChangeCodeUi> = combine(own, picker.ui) { state, codes -> state.copy(picker = codes) }
        .stateIn(scope, SharingStarted.Eagerly, ChangeCodeUi())

    /** Abre el selector para los puntos [pointIds] del proyecto [project] (RF-13). */
    fun start(project: String, pointIds: List<String>) {
        if (pointIds.isEmpty()) return
        ids = pointIds
        projectId.value = project
        picker.setQuery("")
        own.value = ChangeCodeUi(open = true, count = pointIds.size)
    }

    fun setQuery(text: String) = picker.setQuery(text)

    /** Elige el código nuevo, o ninguno; con valores que se pierden pide confirmar antes de aplicar (RF-15). */
    fun choose(code: SurveyCode?) {
        val project = projectId.value ?: return
        scope.launch {
            val chosen = points.points(project).first().filter { it.id in ids }
            val lost = chosen.flatMap { point ->
                AttributeCarryOver.apply(point.code?.attributes.orEmpty(), code?.attributes.orEmpty()).discarded
                    .map { DiscardedValue(point.name, it.name) }
            }
            if (lost.isEmpty()) apply(code) else own.update { it.copy(target = code, warning = lost) }
        }
    }

    fun confirm() {
        val target = own.value.target
        scope.launch { apply(target) }
    }

    fun cancel() {
        own.value = ChangeCodeUi(result = own.value.result)
    }

    fun dismissResult() = own.update { it.copy(result = null) }

    private suspend fun apply(code: SurveyCode?) {
        val result = points.changeCode(ids, code)
        own.value = ChangeCodeUi(result = result)
    }
}

/** El selector y el aviso de «Cambiar código» (S024/RF-13, RF-15). */
@Composable
fun ChangeCodeDialogs(state: ChangeCodeUi, actions: ChangeCodeActions, allowNone: Boolean = true) {
    val warning = state.warning
    if (state.open && warning == null) {
        CodePickerDialog(
            code = state.picker,
            onQuery = actions.onQuery,
            onSelect = actions.onChoose,
            onNew = null,
            onEdit = null,
            onDismiss = actions.onCancel,
            allowNone = allowNone,
        )
    }
    if (warning != null) {
        val lost = warning.map { stringResource(R.string.change_code_lost_item, it.pointName, it.attribute) }.joinToString("; ")
        val name = state.target?.code ?: stringResource(R.string.collector_code_none)
        ConfirmDialog(
            message = stringResource(R.string.change_code_lost_message, name, lost),
            confirm = stringResource(R.string.change_code_apply),
            onConfirm = actions.onConfirm,
            onDismiss = actions.onCancel,
        )
    }
}
