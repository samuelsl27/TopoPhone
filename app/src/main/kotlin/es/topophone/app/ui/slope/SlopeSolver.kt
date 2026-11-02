package es.topophone.app.ui.slope

import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.Superelevation
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.SectionResolver
import es.topophone.core.survey.SlopeCatch
import es.topophone.core.survey.SlopeCatchResult
import es.topophone.core.survey.SlopeEdge
import es.topophone.core.survey.SlopeEdgeResult
import es.topophone.core.survey.TerrainAt
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.section.SectionRepository
import es.topophone.data.slope.SlopeRepository
import es.topophone.data.surface.SurfaceGuides
import kotlinx.coroutines.flow.first

/**
 * Todo lo que hace falta para calcular el punto de talud de un PK y un lado de un eje (S051/RF-5 a RF-8): los taludes, las secciones y el
 * terreno. Lo comparten la pantalla del punto teórico y el replanteo. Calcular hasta 2.000 consultas del terreno: fuera del hilo principal.
 */
class SlopeSolver(
    val geometry: AlignmentGeometry,
    val profile: VerticalProfileEval?,
    private val rules: List<SlopeRule>,
    private val resolver: SectionResolver?,
    private val terrain: TerrainAt,
) {
    fun outcome(chainageM: Double, side: SlopeSide): SlopePointOutcome {
        val edge = when (val e = SlopeEdge.of(rules, side, chainageM, geometry, profile, resolver)) {
            is SlopeEdgeResult.Edge -> e
            else -> return SlopePointOutcome.Unavailable(e)
        }
        return when (val result = SlopeCatch.of(edge, terrain, geometry)) {
            is SlopeCatchResult.Found -> SlopePointOutcome.Found(edge, result, terrain.heightAt(chainageM, result.offsetM)?.source)
            is SlopeCatchResult.AtEdge -> SlopePointOutcome.AtEdge(edge)
            SlopeCatchResult.NoIntersection -> SlopePointOutcome.NoIntersection(edge)
            SlopeCatchResult.NoTerrain -> SlopePointOutcome.NoTerrain(edge)
        }
    }

    /** La cota del terreno en un PK y un desplazamiento, o null si no se conoce. */
    fun terrainHeight(chainageM: Double, offsetM: Double): Double? = terrain.heightAt(chainageM, offsetM)?.heightM

    companion object {
        suspend fun load(
            projectId: String,
            alignment: Alignment,
            slopes: SlopeRepository,
            sections: SectionRepository,
            surfaces: SurfaceGuides,
        ): SlopeSolver {
            val geometry = AlignmentGeometry(alignment)
            val ground = alignment.profile?.let { VerticalProfileEval(it) }
            val stored = sections.sections(alignment.id).first().map { it.section }
            val templates = sections.templates(alignment.id).first().map { it.template }
            val hasSections = templates.isNotEmpty() || stored.any { it.kind == SectionKind.DESIGN }
            val resolver =
                if (hasSections) SectionResolver(templates, Superelevation(sections.superelevation(alignment.id).first()), stored) else null
            val rules = slopes.rules(alignment.id).first().map { it.rule }
            val guide = surfaces.guide(projectId).first()
            val terrain = TerrainAt(stored, geometry, ground, guide?.let { g -> SurfaceHeightSource { e, n -> g.heightAt(e, n) } })
            return SlopeSolver(geometry, ground, rules, resolver, terrain)
        }
    }
}
