package es.topophone.app.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.points.ObjectLines
import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.formats.shp.export.ShapefileZipWriter
import es.topophone.core.formats.shp.export.ShpCoordinates
import es.topophone.core.formats.shp.export.ShpExport
import es.topophone.core.formats.shp.export.ShpExportBuilder
import es.topophone.core.formats.shp.export.ShpExportOptions
import es.topophone.core.formats.shp.export.ShpExportSummary
import es.topophone.core.formats.shp.export.ShpNameLanguage
import es.topophone.core.formats.shp.export.ShpPrjDefinitions
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.project.Project
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.export.ShpExportPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lo que muestra la pantalla de exportar a Shapefile (S055/RF-1, RF-2, RF-12, RF-13, RF-17 a RF-23).
 *
 * @property coordinates el sistema en que se escriben las coordenadas: el del proyecto o las geográficas de su datum (RF-12, RF-13); en un
 *   proyecto en geográficas es siempre el geográfico.
 * @property canChooseCoordinates el proyecto está en UTM: en uno en geográficas no se ofrece elegir (RF-12).
 * @property epsg el código del sistema que se declara en los `.prj` con el sistema elegido; [projectEpsg] y [geographicEpsg] son los de las
 *   dos opciones, para ofrecerlas con su código.
 * @property lineCount cuántas líneas y [polygonCount] cuántos polígonos van, además de los puntos (RF-3).
 * @property summary lo que dice la última exportación (RF-22); null hasta exportar o al cambiar el sistema.
 */
data class ShpExportUiState(
    val loaded: Boolean = false,
    val projectName: String = "",
    val pointCount: Int = 0,
    val lineCount: Int = 0,
    val polygonCount: Int = 0,
    val coordinates: ShpCoordinates = ShpCoordinates.PROJECTED,
    val canChooseCoordinates: Boolean = false,
    val epsg: Int = 0,
    val projectEpsg: Int = 0,
    val geographicEpsg: Int = 0,
    val summary: ShpExportSummary? = null,
    val result: ExportResult? = null,
    /** S058/RF-15: el proyecto está en terreno y el fichero va igualmente en cuadrícula, con su SC; la pantalla lo dice. */
    val terrain: Boolean = false,
) {
    /** Hay puntos que exportar (RF-19). */
    val canExport: Boolean get() = loaded && pointCount > 0
}

/**
 * Exportación de los puntos de un proyecto, o de los seleccionados, y de sus líneas y polígonos, a un ZIP de Shapefiles (S055): el sistema
 * se recuerda para todos los proyectos (RF-23), el ZIP se escribe **entero en una carpeta de trabajo** y solo entonces se copia al destino,
 * así un fallo no deja un fichero a medias (RF-20), y el proyecto nunca se modifica (RF-21).
 */
@HiltViewModel
class ShpExportViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val selection: ExportSelection,
    private val preferences: ShpExportPreferences,
    private val log: AppLog = AppLog.NONE,
    private val lines: ObjectLines = ObjectLines.NONE,
    private val calculator: Lazy<ProjectPositionCalculator> = Lazy { ProjectPositionCalculator() },
) : ViewModel() {
    /** [lines] y [allPoints]: lo que va al ZIP y todos los puntos del proyecto, de donde salen los vértices (RF-2, RF-3). */
    private class Content(val project: Project, val points: List<Point>, val lines: List<ProjectLine>, val allPoints: List<Point>)

    private val builder = ShpExportBuilder()
    private val content = MutableStateFlow<Content?>(null)
    private val coordinates = MutableStateFlow(ShpCoordinates.PROJECTED)
    private val summary = MutableStateFlow<ShpExportSummary?>(null)
    private val result = MutableStateFlow<ExportResult?>(null)

    val state: StateFlow<ShpExportUiState> = combine(content, coordinates, summary, result) { loaded, chosen, last, outcome ->
        if (loaded == null) return@combine ShpExportUiState()
        val project = loaded.project
        val effective = if (project.crs.isUtm) chosen else ShpCoordinates.GEOGRAPHIC
        val options = ShpExportOptions(effective)
        val (lineCount, polygonCount) = builder.countLines(loaded.points, project, options, loaded.lines, loaded.allPoints)
        val geographicEpsg = ShpPrjDefinitions.geographicEpsg(project.crs)
        val projectEpsg = if (project.crs.isUtm) project.crs.epsg else geographicEpsg
        ShpExportUiState(
            loaded = true,
            projectName = project.name,
            pointCount = loaded.points.size,
            lineCount = lineCount,
            polygonCount = polygonCount,
            coordinates = effective,
            canChooseCoordinates = project.crs.isUtm,
            epsg = if (effective == ShpCoordinates.GEOGRAPHIC) geographicEpsg else projectEpsg,
            projectEpsg = projectEpsg,
            geographicEpsg = geographicEpsg,
            summary = last,
            result = outcome,
            terrain = project.terrain != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShpExportUiState())

    /** Con [selectionOnly], solo los puntos de [ExportSelection] y las líneas con todos sus vértices entre ellos (RF-2); si no, todo (RF-1). */
    fun start(projectId: String, selectionOnly: Boolean) {
        if (content.value?.project?.id == projectId) return
        viewModelScope.launch {
            val project = projects.project(projectId) ?: return@launch
            coordinates.value = preferences.coordinates.first() // RF-23
            val all = points.pointsForExport(projectId)
            val ids = selection.ids
            val chosen = if (selectionOnly) all.filter { it.id in ids } else all
            val projectLines = lines.lines(projectId).first()
            val exported = if (selectionOnly) ShpExportBuilder.linesOf(projectLines, ids) else projectLines
            content.value = Content(project, chosen, exported, all)
        }
    }

    /**
     * El sistema de las coordenadas (RF-12, RF-13); se recuerda para todos los proyectos (RF-23). Lo que dijo la exportación anterior, el
     * resumen y el «Fichero guardado.», ya no vale para el sistema nuevo y se borra.
     */
    fun setCoordinates(chosen: ShpCoordinates) {
        coordinates.value = chosen
        summary.value = null
        result.value = null
        viewModelScope.launch { preferences.save(chosen) }
    }

    /** `<proyecto>_shapefile_<AAAAMMDD-HHMM>.zip` con la hora local del móvil (RF-17). */
    fun fileName(at: Instant, zone: ZoneId): String = ExportFileName.of(state.value.projectName, "shapefile", at, zone, "zip")

    /**
     * Escribe el ZIP entero en [into] (para compartirlo, RF-18); false si no hay puntos (RF-19) o no se ha podido escribir, sin dejar fichero
     * (RF-20). El proyecto no cambia (RF-21).
     */
    suspend fun writeFile(into: File): Boolean {
        val loaded = content.value ?: return false
        if (!state.value.canExport) return false
        val options = ShpExportOptions(state.value.coordinates)
        val language = namesLanguage()
        return withContext(Dispatchers.IO) {
            try {
                val export = build(loaded, options, language)
                val written = BufferedOutputStream(into.outputStream()).use {
                    ShapefileZipWriter.write(export, loaded.project.name, it, LocalDate.now())
                }
                summary.value = written
                true
            } catch (_: IOException) {
                into.delete()
                false
            } catch (_: SecurityException) {
                into.delete()
                false
            }
        }
    }

    /**
     * Escribe el ZIP en [out], el destino que eligió el usuario con el selector de ficheros (RF-18): primero entero en [workDir] y solo
     * entonces lo copia, así un fallo del disco no deja un fichero a medias en el destino (RF-20). La carpeta de trabajo queda vacía.
     */
    suspend fun writeTo(out: OutputStream, workDir: File): Boolean {
        if (!state.value.canExport) return false
        val temporary = File(workDir, "shp-${UUID.randomUUID()}.tmp")
        return withContext(Dispatchers.IO) {
            try {
                workDir.mkdirs()
                if (!writeFile(temporary)) return@withContext false
                temporary.inputStream().use { it.copyTo(out) }
                out.flush()
                true
            } catch (_: IOException) {
                summary.value = null
                false
            } catch (_: SecurityException) {
                summary.value = null
                false
            } finally {
                temporary.delete()
            }
        }
    }

    /** Lo que pasó al guardar o compartir (RF-18, RF-20); el registro lleva el hecho y no los puntos (S014/RF-5). */
    fun report(outcome: ExportResult?) {
        result.value = outcome
        val project = state.value.projectName
        when (outcome) {
            ExportResult.SAVED -> log.info(
                LogCategory.APP,
                "Shapefile exportado: «$project», ${state.value.pointCount} puntos, EPSG:${state.value.epsg}",
            )
            ExportResult.FAILED -> log.error(LogCategory.APP, "No se pudo exportar el Shapefile de «$project»")
            null -> Unit
        }
    }

    private fun build(loaded: Content, options: ShpExportOptions, language: ShpNameLanguage): ShpExport = builder.build(
        points = loaded.points,
        project = loaded.project,
        options = options,
        baseProjection = baseProjection(loaded.project),
        language = language,
        lines = loaded.lines,
        allPoints = loaded.allPoints,
    )

    /** Los nombres de las columnas van en el idioma de la interfaz en el momento de exportar: inglés si la app está en inglés, y si no español (RF-8). */
    private fun namesLanguage(): ShpNameLanguage =
        if (Locale.getDefault().language == Locale.ENGLISH.language) ShpNameLanguage.EN else ShpNameLanguage.ES

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
