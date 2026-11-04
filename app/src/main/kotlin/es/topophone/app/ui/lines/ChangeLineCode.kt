package es.topophone.app.ui.lines

import es.topophone.app.ui.collector.CollectorCodeSource
import es.topophone.app.ui.collector.CollectorCodes
import es.topophone.app.ui.points.ChangeCodeUi
import es.topophone.app.ui.points.DiscardedValue
import es.topophone.core.model.codes.AttributeCarryOver
import es.topophone.core.model.codes.ProjectCodes
import es.topophone.core.model.codes.SurveyCode
import es.topophone.data.line.LineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Cambia el código de una línea (S025/RF-20): solo ofrece los de geometría Línea y Polígono; avisa de los valores no vacíos que se
 * perderían (S024/RF-14, RF-15) y, confirmado, lo aplica y lo anota en el historial. Muestra el mismo selector y aviso que «Cambiar
 * código» de los puntos.
 */
class ChangeLineCode(private val scope: CoroutineScope, source: CollectorCodeSource, private val lines: LineRepository) {
    private val projectId = MutableStateFlow<String?>(null)
    private val picker = CollectorCodes(scope, source, projectId, geometries = ProjectCodes.LINE_GEOMETRIES)
    private val own = MutableStateFlow(ChangeCodeUi())
    private var lineId: String? = null

    val ui: StateFlow<ChangeCodeUi> = combine(own, picker.ui) { state, codes -> state.copy(picker = codes) }
        .stateIn(scope, SharingStarted.Eagerly, ChangeCodeUi())

    /** Abre el selector para la línea [line] del proyecto [project]. */
    fun start(project: String, line: String) {
        lineId = line
        projectId.value = project
        picker.setQuery("")
        own.value = ChangeCodeUi(open = true, count = 1)
    }

    fun setQuery(text: String) = picker.setQuery(text)

    /** Elige el código nuevo; con valores que se pierden pide confirmar antes de aplicar. Una línea siempre lleva código. */
    fun choose(code: SurveyCode?) {
        val id = lineId ?: return
        if (code == null) {
            own.value = ChangeCodeUi()
            return
        }
        scope.launch {
            val line = lines.line(id).first() ?: return@launch
            val lost = AttributeCarryOver.apply(line.code?.attributes.orEmpty(), code.attributes).discarded
                .map { DiscardedValue(line.name, it.name) }
            if (lost.isEmpty()) apply(code) else own.update { it.copy(target = code, warning = lost) }
        }
    }

    fun confirm() {
        val target = own.value.target ?: return
        scope.launch { apply(target) }
    }

    fun cancel() {
        own.value = ChangeCodeUi()
    }

    private suspend fun apply(code: SurveyCode) {
        val id = lineId ?: return
        lines.changeCode(id, code)
        own.value = ChangeCodeUi()
    }
}
