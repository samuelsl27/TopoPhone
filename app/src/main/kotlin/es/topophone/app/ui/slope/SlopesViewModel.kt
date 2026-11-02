package es.topophone.app.ui.slope

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.slope.ManualEdge
import es.topophone.core.model.alignment.slope.SlopeIssue
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.data.slope.SlopeRepository
import es.topophone.data.slope.SlopeSaveResult
import es.topophone.data.slope.StoredSlope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Un talud tal como se escribe (S051/RF-1, RF-2): el rango de PK y el borde manual en la unidad del proyecto, las relaciones en metros
 * horizontales por cada metro vertical y el desplazamiento del borde manual en valor absoluto (el signo lo pone el lado).
 */
data class SlopeDraft(
    val side: SlopeSide = SlopeSide.RIGHT,
    val from: String = "",
    val to: String = "",
    val cut: String = "",
    val fill: String = "",
    val edgeCode: String = "",
    val manual: Boolean = false,
    val manualOffset: String = "",
    val manualHeight: String = "",
    val manualRelative: Boolean = false,
)

/** El campo que no es un número, para decirlo (S051/RF-3). */
enum class SlopeField { FROM, TO, CUT, FILL, OFFSET, HEIGHT }

/** Qué impide guardar el talud que se está escribiendo (S051/RF-3). */
sealed interface SlopesProblem {
    data class InvalidNumber(val field: SlopeField) : SlopesProblem

    /** La validación no lo acepta: el motivo. */
    data class Rejected(val issue: SlopeIssue) : SlopesProblem

    data object WriteError : SlopesProblem
}

/** Lo que muestra la pantalla «Taludes» de un eje (S051/RF-1 a RF-3). */
data class SlopesUiState(
    val alignment: AlignmentSummary? = null,
    val unit: LinearUnit = LinearUnit.METRE,
    val loading: Boolean = true,
    val rules: List<StoredSlope> = emptyList(),
    val draft: SlopeDraft? = null,
    val problem: SlopesProblem? = null,
    val saving: Boolean = false,
)

/**
 * Los taludes de un eje (S051/RF-1 a RF-3): la lista de los guardados y el alta de uno por lado y rango de PK, con las pendientes de
 * desmonte y de terraplén, el código del borde de explanación (vacío: el exterior de la sección) o un borde manual si no hay sección.
 * Un talud que no vale no se guarda y dice el motivo.
 */
@HiltViewModel
class SlopesViewModel @Inject constructor(
    private val slopes: SlopeRepository,
    private val alignments: AlignmentRepository,
    private val projects: ProjectRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutable = MutableStateFlow(SlopesUiState())
    val state: StateFlow<SlopesUiState> = mutable.asStateFlow()

    private var started = false

    /** Prepara la pantalla del eje [alignmentId]; solo la primera vez. */
    fun start(projectId: String, alignmentId: String) {
        if (started) return
        started = true
        viewModelScope.launch(io) {
            val unit = projects.project(projectId)?.linearUnit ?: LinearUnit.METRE
            combine(alignments.summaries(projectId), slopes.rules(alignmentId)) { axes, rules ->
                mutable.update {
                    it.copy(alignment = axes.firstOrNull { a -> a.id == alignmentId }, unit = unit, loading = false, rules = rules)
                }
            }.collect {}
        }
    }

    fun startDraft() = mutable.update { it.copy(draft = SlopeDraft(), problem = null) }

    fun cancelDraft() = mutable.update { it.copy(draft = null, problem = null) }

    fun updateDraft(change: (SlopeDraft) -> SlopeDraft) = mutable.update {
        it.draft?.let { draft -> it.copy(draft = change(draft), problem = null) } ?: it
    }

    /** Guarda el talud escrito; si no vale, dice el motivo y no guarda nada (RF-3). */
    fun save() {
        val current = mutable.value
        val draft = current.draft ?: return
        val axis = current.alignment ?: return
        val rule = build(draft, current.unit) ?: return
        mutable.update { it.copy(saving = true, problem = null) }
        viewModelScope.launch(io) {
            when (val result = slopes.add(axis.id, rule)) {
                is SlopeSaveResult.Saved -> mutable.update { it.copy(saving = false, draft = null) }
                is SlopeSaveResult.Rejected -> mutable.update { it.copy(saving = false, problem = SlopesProblem.Rejected(result.issue)) }
                SlopeSaveResult.AxisNotFound -> mutable.update { it.copy(saving = false, problem = SlopesProblem.WriteError) }
            }
        }
    }

    private fun build(draft: SlopeDraft, unit: LinearUnit): SlopeRule? {
        fun number(text: String, field: SlopeField): Double? =
            NumberInput.parse(text).also { if (it == null) mutable.update { s -> s.copy(problem = SlopesProblem.InvalidNumber(field)) } }
        val from = number(draft.from, SlopeField.FROM)?.let(unit::toMetres) ?: return null
        val to = number(draft.to, SlopeField.TO)?.let(unit::toMetres) ?: return null
        val cut = number(draft.cut, SlopeField.CUT) ?: return null
        val fill = number(draft.fill, SlopeField.FILL) ?: return null
        val manual = if (draft.manual) {
            val offset = number(draft.manualOffset, SlopeField.OFFSET)?.let(unit::toMetres) ?: return null
            val height = number(draft.manualHeight, SlopeField.HEIGHT)?.let(unit::toMetres) ?: return null
            ManualEdge(
                draft.side.sign * kotlin.math.abs(offset),
                height,
                if (draft.manualRelative) HeightKind.RELATIVE else HeightKind.ABSOLUTE,
            )
        } else {
            null
        }
        return SlopeRule(draft.side, from, to, cut, fill, draft.edgeCode.trim().takeIf { it.isNotEmpty() && manual == null }, manual)
    }

    fun delete(id: String) {
        viewModelScope.launch(io) { slopes.delete(id) }
    }
}
