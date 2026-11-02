package es.topophone.app.ui.slope

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.core.model.alignment.ChainageInput
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.Superelevation
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.SectionResolver
import es.topophone.core.survey.SlopeCatch
import es.topophone.core.survey.SlopeCatchResult
import es.topophone.core.survey.SlopeEdge
import es.topophone.core.survey.SlopeEdgeResult
import es.topophone.core.survey.TerrainAt
import es.topophone.core.survey.TerrainSource
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.data.section.SectionRepository
import es.topophone.data.slope.SlopeRepository
import es.topophone.data.surface.SurfaceGuides
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lo que sale de buscar el punto de talud teórico en un PK y un lado (S051/RF-5 a RF-8). */
sealed interface SlopePointOutcome {
    /** El punto de talud: cabeza o pie, con el terreno del que sale. */
    data class Found(val edge: SlopeEdgeResult.Edge, val point: SlopeCatchResult.Found, val source: TerrainSource?) : SlopePointOutcome

    /** El terreno está a la cota del borde: el punto de talud es el borde con altura cero (RF-8). */
    data class AtEdge(val edge: SlopeEdgeResult.Edge) : SlopePointOutcome

    /** El terreno llega a 100 m sin cortar al talud: «Sin intersección con el terreno» (RF-7). */
    data class NoIntersection(val edge: SlopeEdgeResult.Edge) : SlopePointOutcome

    /** Sin terreno conocido: solo se puede replantear en vivo (RF-6). */
    data class NoTerrain(val edge: SlopeEdgeResult.Edge) : SlopePointOutcome

    /** No hay borde: no hay talud, ni sección ni cota, o el código no existe (RF-8). */
    data class Unavailable(val reason: SlopeEdgeResult) : SlopePointOutcome
}

/** Lo que muestra la pantalla del punto de talud teórico (S051/RF-5 a RF-8). */
data class SlopePointUiState(
    val loaded: Boolean = false,
    val alignmentName: String = "",
    val unit: LinearUnit = LinearUnit.METRE,
    val startChainageM: Double = 0.0,
    val endChainageM: Double = 0.0,
    val chainageText: String = "",
    val side: SlopeSide = SlopeSide.RIGHT,
    val chainageInvalid: Boolean = false,
    /** El PK escrito cae fuera del eje. */
    val outside: Boolean = false,
    val outcome: SlopePointOutcome? = null,
)

/**
 * El punto de talud teórico de un PK y un lado de un eje (S051/RF-5 a RF-8): el borde de explanación, el tipo (cabeza o pie), el punto de
 * talud y la altura, con el terreno de los perfiles de S050 o la superficie activa. Sin terreno no se calcula y se dice que solo hay
 * seguimiento en vivo. El cálculo (hasta 2.000 consultas del terreno) va fuera del hilo principal.
 */
@HiltViewModel
class SlopePointViewModel @Inject constructor(
    private val slopes: SlopeRepository,
    private val sections: SectionRepository,
    private val alignments: AlignmentRepository,
    private val projects: ProjectRepository,
    private val surfaces: SurfaceGuides,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val mutable = MutableStateFlow(SlopePointUiState())
    val state: StateFlow<SlopePointUiState> = mutable.asStateFlow()

    private var started = false
    private var geometry: AlignmentGeometry? = null
    private var profile: VerticalProfileEval? = null
    private var resolver: SectionResolver? = null
    private var terrain: TerrainAt? = null
    private var rules: List<es.topophone.core.model.alignment.slope.SlopeRule> = emptyList()
    private var job: Job? = null

    /** Prepara el cálculo sobre el eje [alignmentId] del proyecto [projectId]; solo la primera vez. */
    fun start(projectId: String, alignmentId: String) {
        if (started) return
        started = true
        viewModelScope.launch(io) {
            val project = projects.project(projectId) ?: return@launch
            val alignment = alignments.alignment(alignmentId) ?: return@launch
            val axis = AlignmentGeometry(alignment)
            val ground = alignment.profile?.let { VerticalProfileEval(it) }
            val stored = sections.sections(alignmentId).first().map { it.section }
            val templates = sections.templates(alignmentId).first().map { it.template }
            val hasSections = templates.isNotEmpty() || stored.any { it.kind == SectionKind.DESIGN }
            geometry = axis
            profile = ground
            resolver =
                if (hasSections) SectionResolver(templates, Superelevation(sections.superelevation(alignmentId).first()), stored) else null
            rules = slopes.rules(alignmentId).first().map { it.rule }
            val guide = surfaces.guide(projectId).first()
            terrain = TerrainAt(stored, axis, ground, guide?.let { g -> SurfaceHeightSource { e, n -> g.heightAt(e, n) } })
            mutable.update {
                it.copy(
                    loaded = true,
                    alignmentName = alignment.name,
                    unit = project.linearUnit,
                    startChainageM = alignment.startChainage,
                    endChainageM = alignment.endChainage,
                )
            }
        }
    }

    fun setChainage(text: String) = recalculate(mutable.value.copy(chainageText = text))

    fun setSide(side: SlopeSide) = recalculate(mutable.value.copy(side = side))

    private fun recalculate(next: SlopePointUiState) {
        val chainage = ChainageInput.parse(next.chainageText, next.unit)
        mutable.value = next.copy(chainageInvalid = next.chainageText.isNotBlank() && chainage == null, outside = false, outcome = null)
        job?.cancel()
        val axis = geometry ?: return
        if (chainage == null) return
        if (!axis.contains(chainage)) {
            mutable.update { it.copy(outside = true) }
            return
        }
        job = viewModelScope.launch(io) {
            val outcome = compute(axis, chainage, next.side)
            mutable.update { if (it.chainageText == next.chainageText && it.side == next.side) it.copy(outcome = outcome) else it }
        }
    }

    private fun compute(axis: AlignmentGeometry, chainage: Double, side: SlopeSide): SlopePointOutcome {
        val edge = when (val e = SlopeEdge.of(rules, side, chainage, axis, profile, resolver)) {
            is SlopeEdgeResult.Edge -> e
            else -> return SlopePointOutcome.Unavailable(e)
        }
        return when (val result = SlopeCatch.of(edge, terrain, axis)) {
            is SlopeCatchResult.Found -> SlopePointOutcome.Found(edge, result, terrain?.heightAt(chainage, result.offsetM)?.source)
            is SlopeCatchResult.AtEdge -> SlopePointOutcome.AtEdge(edge)
            SlopeCatchResult.NoIntersection -> SlopePointOutcome.NoIntersection(edge)
            SlopeCatchResult.NoTerrain -> SlopePointOutcome.NoTerrain(edge)
        }
    }
}
