package es.topophone.app.ui.lines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.collector.CollectorCodeSource
import es.topophone.app.ui.collector.CollectorCodeUi
import es.topophone.app.ui.collector.CollectorCodes
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.ProjectCodes
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.line.LineIssue
import es.topophone.core.model.line.LineWizard
import es.topophone.core.model.point.PointSearch
import es.topophone.core.model.point.PointSummary
import es.topophone.data.line.LineRepository
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.NewLine
import es.topophone.data.point.PointRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Por qué no se ha creado la línea del asistente (S025/RF-15). */
enum class LineWizardIssue { TOO_FEW_VERTICES, REPEATED_VERTEX, NAME_TAKEN, ATTRIBUTES, MISSING_POINT }

/** Un punto elegido, con el nombre que se enseña (S025/RF-12). */
data class WizardPoint(val id: String, val name: String)

/**
 * Lo que muestra el asistente «Línea desde puntos» (S025/RF-12 a RF-15): los puntos del proyecto (filtrados por [query]), los elegidos en
 * su orden, el código con sus atributos, si queda abierta o cerrada y el nombre propuesto.
 */
data class LineFromPointsUi(
    val loaded: Boolean = false,
    val points: List<PointSummary> = emptyList(),
    val query: String = "",
    val selected: List<WizardPoint> = emptyList(),
    val code: CollectorCodeUi = CollectorCodeUi(),
    val closed: Boolean = false,
    val name: String = "",
    val issue: LineWizardIssue? = null,
    /** La línea se creó y la pantalla se puede cerrar. */
    val finished: Boolean = false,
) {
    /** La posición (desde 1) de un punto en la elección, o null si no está elegido. */
    fun positionOf(pointId: String): Int? = selected.indexOfFirst { it.id == pointId }.takeIf { it >= 0 }?.plus(1)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LineFromPointsViewModel @Inject constructor(
    private val points: PointRepository,
    private val lines: LineRepository,
    codeSource: CollectorCodeSource,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)

    /** El código de la línea: los de geometría Línea y Polígono del proyecto (S025/RF-13). */
    private val codes = CollectorCodes(viewModelScope, codeSource, projectId, geometries = ProjectCodes.LINE_GEOMETRIES)

    private val summaries = projectId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else points.summaries(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val selected = MutableStateFlow<List<String>>(emptyList())
    private val query = MutableStateFlow("")
    private val own = MutableStateFlow(Own())

    private data class Own(
        val loaded: Boolean = false,
        val closed: Boolean = false,
        val closedTouched: Boolean = false,
        val name: String = "",
        val nameTouched: Boolean = false,
        val issue: LineWizardIssue? = null,
        val finished: Boolean = false,
    )

    val state: StateFlow<LineFromPointsUi> = combine(summaries, selected, query, own, codes.ui) { all, ids, query, own, code ->
        val names = all.associate { it.id to it.name }
        LineFromPointsUi(
            loaded = own.loaded,
            points = all.filter { PointSearch.matches(it, query) },
            query = query,
            selected = ids.mapNotNull { id -> names[id]?.let { WizardPoint(id, it) } },
            code = code,
            closed = own.closed,
            name = own.name,
            issue = own.issue,
            finished = own.finished,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LineFromPointsUi())

    /** Abre el asistente para el proyecto [id]; los puntos elegidos antes se conservan si es el mismo. */
    fun start(id: String) {
        if (projectId.value == id) return
        projectId.value = id
        viewModelScope.launch { own.value = own.value.copy(loaded = true, name = lines.suggestName(id, null)) }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    /** Tocar un punto lo añade al final del orden o lo quita (RF-12, RF-13). */
    fun toggle(pointId: String) {
        selected.value = LineWizard.toggle(selected.value, pointId)
        clearIssue()
    }

    /** Ordena los puntos elegidos por nombre (RF-12). */
    fun sortByName() {
        val names = summaries.value.associate { it.id to it.name }
        selected.value = LineWizard.sortedByName(selected.value, names)
        clearIssue()
    }

    /** Invierte el orden de los puntos elegidos (RF-13). */
    fun reverse() {
        selected.value = LineWizard.reversed(selected.value)
        clearIssue()
    }

    fun selectCode(code: SurveyCode?) {
        codes.select(code)
        own.value = own.value.let { current ->
            val closed = if (current.closedTouched) current.closed else LineWizard.defaultClosed(code?.geometry)
            current.copy(closed = closed, issue = null)
        }
        suggestName(code?.code)
    }

    fun setCodeQuery(text: String) = codes.setQuery(text)

    fun setCodeAttribute(index: Int, text: String) = codes.setAttribute(index, text)

    fun setClosed(closed: Boolean) {
        own.value = own.value.copy(closed = closed, closedTouched = true, issue = null)
    }

    fun setName(text: String) {
        own.value = own.value.copy(name = text, nameTouched = true, issue = null)
    }

    private fun suggestName(code: String?) {
        val id = projectId.value ?: return
        if (own.value.nameTouched) return
        viewModelScope.launch {
            if (!own.value.nameTouched) own.value = own.value.copy(name = lines.suggestName(id, code))
        }
    }

    private fun clearIssue() {
        own.value = own.value.copy(issue = null)
    }

    /** Crea la línea con los puntos elegidos, sin modificarlos (RF-14); si no se puede, dice el motivo (RF-15). */
    fun create() {
        val id = projectId.value ?: return
        val ids = selected.value
        val current = own.value
        LineWizard.issue(ids, current.closed)?.let {
            own.value = current.copy(issue = it.toWizard())
            return
        }
        val selectedCode = codes.ui.value.selected
        val pointCode = codes.pointCode()
        if (selectedCode != null && pointCode == null) {
            own.value = current.copy(issue = LineWizardIssue.ATTRIBUTES)
            return
        }
        val geometry = selectedCode?.geometry?.takeIf { it != CodeGeometry.POINT } ?: CodeGeometry.LINE
        viewModelScope.launch {
            // Si el usuario no ha escrito el nombre, es el propuesto para el código elegido y la numeración de ahora (RF-14).
            val name = if (current.nameTouched) current.name.trim().ifEmpty { null } else lines.suggestName(id, selectedCode?.code)
            val result = lines.createFromPoints(NewLine(id, name, pointCode, geometry, current.closed, ids))
            own.value = when (result) {
                is LineSaveResult.Saved -> own.value.copy(finished = true, issue = null)
                LineSaveResult.DuplicateName -> own.value.copy(issue = LineWizardIssue.NAME_TAKEN)
                LineSaveResult.TooFewVertices -> own.value.copy(issue = LineWizardIssue.TOO_FEW_VERTICES)
                LineSaveResult.RepeatedVertex -> own.value.copy(issue = LineWizardIssue.REPEATED_VERTEX)
                LineSaveResult.NotFound, LineSaveResult.WrongGeometry -> own.value.copy(issue = LineWizardIssue.MISSING_POINT)
            }
        }
    }

    private fun LineIssue.toWizard(): LineWizardIssue = when (this) {
        LineIssue.TOO_FEW_VERTICES -> LineWizardIssue.TOO_FEW_VERTICES
        LineIssue.REPEATED_VERTEX -> LineWizardIssue.REPEATED_VERTEX
    }
}
