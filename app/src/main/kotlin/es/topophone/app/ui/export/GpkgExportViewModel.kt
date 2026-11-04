package es.topophone.app.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.formats.csv.AttributeColumns
import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.formats.gpkg.GpkgSrsDefinitions
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.project.Project
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.gpkg.GeoPackageWriter
import es.topophone.data.gpkg.GpkgExportCrs
import es.topophone.data.gpkg.GpkgExportException
import es.topophone.data.gpkg.GpkgExportSummary
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lo que muestra la pantalla de exportar a GeoPackage (S053/RF-1, RF-2, RF-6 a RF-8, RF-11).
 *
 * @property crs el sistema en que se escribe la capa: el del proyecto o las geográficas de su datum (RF-6, RF-7).
 * @property canChooseCrs el proyecto está en UTM: en un proyecto en geográficas las dos opciones son la misma y no se ofrece elegir.
 * @property srsId el código EPSG que se declara en el fichero con el sistema elegido (RF-6).
 * @property summary lo que dice la última exportación: los puntos, los que van sin geometría y si el sistema lleva su definición completa
 *   (RF-8); null hasta exportar.
 */
data class GpkgExportUiState(
    val loaded: Boolean = false,
    val projectName: String = "",
    val pointCount: Int = 0,
    val crs: GpkgExportCrs = GpkgExportCrs.PROJECT,
    val canChooseCrs: Boolean = false,
    val srsId: Int = 0,
    /** Los códigos de las dos opciones de sistema, para ofrecerlas con su código (RF-6, RF-7). */
    val projectSrsId: Int = 0,
    val geographicSrsId: Int = 0,
    val summary: GpkgExportSummary? = null,
    val result: ExportResult? = null,
) {
    /** Hay puntos que exportar (RF-11). */
    val canExport: Boolean get() = loaded && pointCount > 0
}

/**
 * Exportación de los puntos de un proyecto, o de los seleccionados, a un GeoPackage (S053): el sistema se elige y no se recuerda, el fichero
 * se escribe **entero en una carpeta de trabajo** y solo entonces se copia al destino, así un fallo no deja un fichero a medias (RF-12), y los
 * puntos nunca se modifican (RF-13).
 */
@HiltViewModel
class GpkgExportViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val selection: ExportSelection,
    private val log: AppLog = AppLog.NONE,
    private val calculator: Lazy<ProjectPositionCalculator> = Lazy { ProjectPositionCalculator() },
) : ViewModel() {
    private data class Content(val project: Project, val points: List<Point>)

    private val writer = GeoPackageWriter()
    private val content = MutableStateFlow<Content?>(null)
    private val crs = MutableStateFlow(GpkgExportCrs.PROJECT)
    private val summary = MutableStateFlow<GpkgExportSummary?>(null)
    private val result = MutableStateFlow<ExportResult?>(null)

    val state: StateFlow<GpkgExportUiState> = combine(content, crs, summary, result) { loaded, chosen, last, outcome ->
        if (loaded == null) return@combine GpkgExportUiState()
        val project = loaded.project
        val geographic = chosen == GpkgExportCrs.GEOGRAPHIC || !project.crs.isUtm
        GpkgExportUiState(
            loaded = true,
            projectName = project.name,
            pointCount = loaded.points.size,
            crs = if (project.crs.isUtm) chosen else GpkgExportCrs.GEOGRAPHIC,
            canChooseCrs = project.crs.isUtm,
            srsId = (if (geographic) GpkgSrsDefinitions.geographicOf(project.crs) else GpkgSrsDefinitions.of(project.crs)).srsId,
            projectSrsId = if (project.crs.isUtm) {
                GpkgSrsDefinitions.of(
                    project.crs,
                ).srsId
            } else {
                GpkgSrsDefinitions.geographicOf(project.crs).srsId
            },
            geographicSrsId = GpkgSrsDefinitions.geographicOf(project.crs).srsId,
            summary = last,
            result = outcome,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GpkgExportUiState())

    /** Con [selectionOnly], solo los puntos de [ExportSelection] (RF-2); si no, todos los activos, ocultos incluidos (RF-1). */
    fun start(projectId: String, selectionOnly: Boolean) {
        if (content.value?.project?.id == projectId) return
        viewModelScope.launch {
            val project = projects.project(projectId) ?: return@launch
            val all = points.pointsForExport(projectId)
            val chosen = if (selectionOnly) selection.ids.let { ids -> all.filter { it.id in ids } } else all
            content.value = Content(project, chosen)
        }
    }

    /** El sistema de la capa: el del proyecto o las geográficas de su datum (RF-7). */
    fun setCrs(chosen: GpkgExportCrs) {
        crs.value = chosen
        summary.value = null
    }

    /** `<proyecto>_geopackage_<AAAAMMDD-HHMM>.gpkg` con la hora local del móvil (RF-9). */
    fun fileName(at: Instant, zone: ZoneId): String = ExportFileName.of(state.value.projectName, "geopackage", at, zone, "gpkg")

    /**
     * Escribe el GeoPackage entero en [into] (para compartirlo, RF-10); false si no hay puntos (RF-11) o no se ha podido escribir, sin dejar
     * fichero (RF-12). El proyecto no cambia (RF-13).
     */
    suspend fun writeFile(into: File): Boolean {
        val loaded = content.value ?: return false
        if (!state.value.canExport) return false
        return withContext(Dispatchers.IO) {
            try {
                val written =
                    writer.write(
                        loaded.points,
                        loaded.project,
                        crs.value,
                        into,
                        baseProjection(loaded.project),
                        attributes = AttributeColumns.of(loaded.points), // S024/RF-21
                    ) ?: return@withContext false
                summary.value = written
                true
            } catch (_: GpkgExportException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }

    /**
     * Escribe el GeoPackage en [out], el destino que eligió el usuario con el selector de ficheros (RF-10): primero entero en [workDir] y solo
     * entonces lo copia, así un fallo del disco no deja un fichero a medias en el destino (RF-12). La carpeta de trabajo queda vacía.
     */
    suspend fun writeTo(out: OutputStream, workDir: File): Boolean {
        if (!state.value.canExport) return false
        val temporary = File(workDir, "gpkg-${UUID.randomUUID()}.tmp")
        return withContext(Dispatchers.IO) {
            try {
                workDir.mkdirs()
                if (!writeFile(temporary)) return@withContext false
                temporary.inputStream().use { it.copyTo(out) }
                out.flush()
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            } finally {
                temporary.delete()
            }
        }
    }

    /** Lo que pasó al guardar o compartir (RF-10, RF-12); el registro lleva el hecho y no los puntos (S014/RF-5). */
    fun report(outcome: ExportResult?) {
        result.value = outcome
        val project = state.value.projectName
        when (outcome) {
            ExportResult.SAVED -> log.info(
                LogCategory.APP,
                "GeoPackage exportado: «$project», ${state.value.pointCount} puntos, EPSG:${state.value.srsId}",
            )
            ExportResult.FAILED -> log.error(LogCategory.APP, "No se pudo exportar el GeoPackage de «$project»")
            null -> Unit
        }
    }

    /** La base de cada punto en el SC actual del proyecto (S015/RF-22); casi todos los puntos comparten base: se calcula una vez por posición. */
    private fun baseProjection(project: Project): (PointBase) -> BaseProjection? {
        val known = HashMap<GeodeticPosition, BaseProjection>()
        return { base ->
            known.getOrPut(base.position) {
                val position = calculator.get().fromGround(base.position, project.crs, project.heightSystem)
                BaseProjection(position.easting, position.northing, position.height)
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
