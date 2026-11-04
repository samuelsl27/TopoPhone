package es.topophone.app.ui.lines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.collector.AttributeFieldUi
import es.topophone.app.ui.collector.CollectorCodeSource
import es.topophone.app.ui.collector.interfaceDecimal
import es.topophone.core.model.codes.AttributeError
import es.topophone.core.model.codes.AttributeForm
import es.topophone.core.model.line.LineChainage
import es.topophone.core.model.line.LineEdit
import es.topophone.core.model.line.LineHistory
import es.topophone.core.model.line.LineHistoryItem
import es.topophone.core.model.line.LineSnapshot
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.PointSearch
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.line.LineRepository
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.LineUpdate
import es.topophone.data.line.VertexPoint
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Un vértice en la ficha: su posición, el punto y su PK desde el primer vértice (S025/RF-16). */
data class VertexRow(val index: Int, val pointId: String, val name: String, val chainageM: Double?)

/** Qué hace el selector de puntos de la ficha: añadir al final, insertar antes de un vértice o cambiar el punto de un vértice (RF-16). */
sealed interface VertexPick {
    data object Append : VertexPick

    data class Insert(val index: Int) : VertexPick

    data class Replace(val index: Int) : VertexPick
}

/** Por qué no se ha guardado una edición de la línea (S025/RF-19). */
enum class LineEditIssue { TOO_FEW_VERTICES, REPEATED_VERTEX, NAME_TAKEN, ATTRIBUTES, MISSING_POINT }

/** El formulario de los valores de los atributos de la línea (S025/RF-18). */
data class LineAttributesEdit(val fields: List<AttributeFieldUi>)

/** Lo que muestra la ficha de una línea (S025/RF-16 a RF-22). */
data class LineDetailsUi(
    val loaded: Boolean = false,
    val line: ProjectLine? = null,
    val unit: LinearUnit = LinearUnit.METRE,
    val vertices: List<VertexRow> = emptyList(),
    val history: List<LineHistoryItem> = emptyList(),
    /** Los puntos del proyecto entre los que se elige, filtrados por [pickQuery], mientras el selector está abierto. */
    val pick: VertexPick? = null,
    val pickQuery: String = "",
    val candidates: List<WizardPoint> = emptyList(),
    val renaming: String? = null,
    val attributes: LineAttributesEdit? = null,
    val issue: LineEditIssue? = null,
    /** La línea se ha movido a la papelera y la ficha se puede cerrar (RF-24). */
    val deleted: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LineDetailsViewModel @Inject constructor(
    private val lines: LineRepository,
    private val points: PointRepository,
    projects: ProjectRepository,
    codeSource: CollectorCodeSource,
) : ViewModel() {
    private val ids = MutableStateFlow<Pair<String, String>?>(null)
    private val own = MutableStateFlow(Own())

    private data class Own(
        val pick: VertexPick? = null,
        val pickQuery: String = "",
        val renaming: String? = null,
        val attributes: List<String>? = null,
        val issue: LineEditIssue? = null,
        val deleted: Boolean = false,
    )

    /** Cambiar el código de esta línea (S025/RF-20). */
    val changeCode = ChangeLineCode(viewModelScope, codeSource, lines)

    private data class Source(
        val line: ProjectLine?,
        val vertexPoints: List<VertexPoint>,
        val unit: LinearUnit,
        val history: List<es.topophone.core.model.line.LineHistoryEntry>,
    )

    private val source = ids.flatMapLatest { pair ->
        if (pair == null) {
            flowOf(Source(null, emptyList(), LinearUnit.METRE, emptyList()))
        } else {
            combine(lines.line(pair.second), lines.vertexPoints(pair.second), projects.observe(pair.first), lines.history(pair.second)) {
                    line,
                    vertexPoints,
                    project,
                    history,
                ->
                Source(line, vertexPoints, project?.linearUnit ?: LinearUnit.METRE, history)
            }
        }
    }

    /** Los puntos del proyecto entre los que se elige, solo mientras el selector está abierto: no se cargan al abrir la ficha. */
    private val candidates = combine(ids, own.map { it.pick != null }.distinctUntilChanged()) { pair, picking -> pair to picking }
        .flatMapLatest { (pair, picking) ->
            if (pair == null ||
                !picking
            ) {
                flowOf(emptyList())
            } else {
                points.summaries(pair.first).map { all -> all.map { WizardPoint(it.id, it.name) } }
            }
        }

    val state: StateFlow<LineDetailsUi> = combine(
        source.map {
            it.toUi()
        }.flowOn(Dispatchers.Default),
        own,
        candidates,
    ) { base, own, pickable ->
        base.copy(
            pick = own.pick,
            pickQuery = own.pickQuery,
            candidates = pickable.filter { c ->
                own.pickQuery.isBlank() ||
                    PointSearch.normalize(c.name).contains(PointSearch.normalize(own.pickQuery.trim()))
            },
            renaming = own.renaming,
            attributes = own.attributes?.let { texts -> attributeForm(base.line, texts) },
            issue = own.issue,
            deleted = own.deleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LineDetailsUi())

    private fun Source.toUi(): LineDetailsUi {
        val line = line ?: return LineDetailsUi(loaded = true)
        val chainage = LineChainage.of(vertexPoints.map { (it.easting ?: 0.0) to (it.northing ?: 0.0) })
        val vertices = vertexPoints.mapIndexed { i, p -> VertexRow(i, p.pointId, p.name, chainage[i]) }
        val names = HashMap<String, String>(vertexPoints.size * 2)
        vertexPoints.forEach { names[it.pointId] = it.name }
        return LineDetailsUi(
            loaded = true,
            line = line,
            unit = unit,
            vertices = vertices,
            history = LineHistory.items(history, LineSnapshot.of(line, names)),
        )
    }

    fun start(projectId: String, lineId: String) {
        ids.value = projectId to lineId
        own.value = Own()
    }

    private fun current(): ProjectLine? = state.value.line

    private fun apply(update: LineUpdate) {
        val (_, lineId) = ids.value ?: return
        viewModelScope.launch {
            own.value = own.value.copy(issue = null)
            val result = lines.edit(lineId, update)
            if (result is LineSaveResult.Saved) {
                own.value = own.value.copy(pick = null, pickQuery = "", renaming = null, attributes = null, issue = null)
            } else {
                own.value = own.value.copy(issue = result.toIssue())
            }
        }
    }

    private fun LineSaveResult.toIssue(): LineEditIssue? = when (this) {
        is LineSaveResult.Saved -> null
        LineSaveResult.DuplicateName -> LineEditIssue.NAME_TAKEN
        LineSaveResult.TooFewVertices -> LineEditIssue.TOO_FEW_VERTICES
        LineSaveResult.RepeatedVertex -> LineEditIssue.REPEATED_VERTEX
        LineSaveResult.NotFound, LineSaveResult.WrongGeometry -> LineEditIssue.MISSING_POINT
    }

    // --- Vértices (RF-16 a RF-19) ----------------------------------------------------------------------------------------------------

    private fun edit(change: (List<String>) -> List<String>) {
        // Lo que se edita es la lista que se está viendo, para que cada edición parta de lo que el usuario tiene delante.
        val shown = state.value.vertices.map { it.pointId }
        if (current() == null) return
        apply(LineUpdate(pointIds = change(shown)))
    }

    /** La posición en la línea del vértice número [row] de la ficha (los vértices de puntos en la papelera no se enseñan). */
    private fun lineIndex(row: Int): Int? = state.value.vertices.getOrNull(row)?.index

    fun removeVertex(row: Int) {
        val i = lineIndex(row) ?: return
        edit { LineEdit.remove(it, i) }
    }

    fun moveVertex(row: Int, toRow: Int) {
        val from = lineIndex(row) ?: return
        val to = lineIndex(toRow) ?: return
        edit { LineEdit.move(it, from, to) }
    }

    fun reverse() = edit { LineEdit.reverse(it) }

    fun openPicker(pick: VertexPick) {
        own.value = own.value.copy(pick = pick, pickQuery = "", issue = null)
    }

    fun setPickQuery(text: String) {
        own.value = own.value.copy(pickQuery = text)
    }

    fun closePicker() {
        own.value = own.value.copy(pick = null, pickQuery = "")
    }

    /** El punto elegido en el selector: se añade, se inserta o sustituye el de un vértice (RF-16). */
    fun pickPoint(pointId: String) {
        val pick = own.value.pick ?: return
        when (pick) {
            VertexPick.Append -> edit { LineEdit.append(it, pointId) }
            is VertexPick.Insert -> {
                val i = lineIndex(pick.index) ?: current()?.vertices?.size ?: return
                edit { LineEdit.insert(it, i, pointId) }
            }
            is VertexPick.Replace -> {
                val i = lineIndex(pick.index) ?: return
                edit { LineEdit.replace(it, i, pointId) }
            }
        }
    }

    // --- Nombre, cierre y atributos (RF-10, RF-18) ----------------------------------------------------------------------------------

    fun startRename() {
        own.value = own.value.copy(renaming = current()?.name, issue = null)
    }

    fun setRename(text: String) {
        own.value = own.value.copy(renaming = text)
    }

    fun cancelRename() {
        own.value = own.value.copy(renaming = null, issue = null)
    }

    fun confirmRename() {
        val name = own.value.renaming?.trim().orEmpty()
        if (name.isEmpty()) return
        apply(LineUpdate(name = name))
    }

    /** «Cerrar en polígono» o «Abrir», según el estado de la línea (RF-10, RF-11). */
    fun toggleClosed() {
        val (_, lineId) = ids.value ?: return
        val line = current() ?: return
        viewModelScope.launch {
            val result = if (line.closed) lines.open(lineId) else lines.close(lineId)
            own.value = own.value.copy(issue = result.toIssue())
        }
    }

    fun startAttributes() {
        val line = current() ?: return
        val attributes = line.code?.attributes ?: return
        if (attributes.isEmpty()) return
        own.value = own.value.copy(attributes = AttributeForm.texts(attributes, interfaceDecimal()), issue = null)
    }

    fun setAttribute(index: Int, text: String) {
        val texts = own.value.attributes?.toMutableList() ?: return
        if (index !in texts.indices) return
        texts[index] = text
        own.value = own.value.copy(attributes = texts)
    }

    fun cancelAttributes() {
        own.value = own.value.copy(attributes = null, issue = null)
    }

    fun saveAttributes() {
        val line = current() ?: return
        val values = line.code?.attributes ?: return
        val texts = own.value.attributes ?: return
        val result = AttributeForm.validateValues(values, texts, interfaceDecimal())
        if (!result.isValid) {
            own.value = own.value.copy(issue = LineEditIssue.ATTRIBUTES)
            return
        }
        apply(LineUpdate(attributes = result.values))
    }

    /** «Borrar línea»: va a la papelera del proyecto sin tocar sus puntos; la confirmación la pide la pantalla (RF-24). */
    fun delete() {
        val (_, lineId) = ids.value ?: return
        viewModelScope.launch {
            lines.moveToTrash(lineId)
            own.value = own.value.copy(deleted = true)
        }
    }

    fun openChangeCode() {
        val (projectId, lineId) = ids.value ?: return
        changeCode.start(projectId, lineId)
    }

    private fun attributeForm(line: ProjectLine?, texts: List<String>): LineAttributesEdit? {
        val values = line?.code?.attributes ?: return null
        val result = AttributeForm.validateValues(values, texts, interfaceDecimal())
        return LineAttributesEdit(
            values.mapIndexed { i, a ->
                AttributeFieldUi(a.name, a.type, emptyList(), texts.getOrElse(i) { "" }, result.errors.getOrNull(i) as AttributeError?)
            },
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
