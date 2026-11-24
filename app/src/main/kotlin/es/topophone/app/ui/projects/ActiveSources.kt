package es.topophone.app.ui.projects

import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.baseshift.BaseShiftRepository
import es.topophone.data.localization.LocalizationRepository
import es.topophone.data.surface.SurfaceRepository
import es.topophone.data.wms.ProjectLayersRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** De dónde sale lo que está activo en un proyecto, al día cuando cambia (S059/RF-18, RF-22). El terreno lo pone el proyecto mismo. */
interface ActiveSources {
    fun inputs(projectId: String): Flow<ActiveInputs>

    companion object {
        val NONE: ActiveSources = object : ActiveSources {
            override fun inputs(projectId: String): Flow<ActiveInputs> = flowOf(ActiveInputs())
        }
    }
}

/** Lo activo, leído de los repositorios de localización, base shift, superficies, ejes y capas (S059/RF-18, RF-22). */
class RepositoryActiveSources(
    private val localization: LocalizationRepository,
    private val baseShift: BaseShiftRepository,
    private val surfaces: SurfaceRepository,
    private val alignments: AlignmentRepository,
    private val layers: ProjectLayersRepository,
) : ActiveSources {
    override fun inputs(projectId: String): Flow<ActiveInputs> {
        val surfaceName = combine(surfaces.activeSurfaceId(projectId), surfaces.surfaces(projectId)) { id, all ->
            all.firstOrNull { it.id == id }?.name
        }
        val alignmentName = combine(alignments.activeAlignmentId(projectId), alignments.summaries(projectId)) { id, all ->
            all.firstOrNull { it.id == id }?.name
        }
        val first =
            combine(localization.active(projectId), baseShift.active(projectId), surfaceName, alignmentName) { loc, shift, surface, eje ->
                ActiveInputs(
                    localizationActive = loc != null,
                    localizationRmsM = loc?.rmsHorizontal,
                    baseShiftName = shift?.knownName,
                    surfaceName = surface,
                    alignmentName = eje,
                )
            }
        return combine(first, layers.layers(projectId)) { base, all ->
            base.copy(layersVisible = all.count { it.layer.visible }, layersTotal = all.size)
        }
    }
}
