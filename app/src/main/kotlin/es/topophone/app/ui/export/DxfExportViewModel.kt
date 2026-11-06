package es.topophone.app.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.points.ObjectLines
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.formats.dxf.export.DxfCoordinates
import es.topophone.core.formats.dxf.export.DxfDocumentBuilder
import es.topophone.core.formats.dxf.export.DxfExportOptions
import es.topophone.core.formats.dxf.export.DxfExportSummary
import es.topophone.core.formats.dxf.export.DxfLabel
import es.topophone.core.formats.dxf.export.DxfUnit
import es.topophone.core.formats.dxf.export.DxfWriter
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.Point
import es.topophone.core.model.project.Project
import es.topophone.data.export.DxfExportPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
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
 * Lo que muestra la pantalla de exportar a DXF (S029/RF-1, RF-2, RF-7, RF-8, RF-11 a RF-14, RF-18, RF-20).
 *
 * @property options lo elegido: las etiquetas, la altura del texto y el sistema (RF-7, RF-8, RF-11, RF-12).
 * @property heightText lo que hay escrito en el campo de la altura, que puede no ser válido (RF-8).
 * @property canChooseCoordinates el proyecto está en UTM: en uno geográfico solo hay globales y no se ofrece elegir (RF-13).
 * @property coordinates el sistema con que se escribirá, que en un proyecto geográfico es siempre el global (RF-13).
 * @property unit la unidad de las coordenadas del fichero, que el DXF R12 no puede declarar (RF-14).
 * @property summary lo que dice la última exportación (RF-20); null hasta exportar o al cambiar una opción.
 */
data class DxfExportUiState(
    val loaded: Boolean = false,
    val projectName: String = "",
    val pointCount: Int = 0,
    val lineCount: Int = 0,
    val polygonCount: Int = 0,
    val options: DxfExportOptions = DxfExportOptions(),
    val heightText: String = "",
    val heightValid: Boolean = true,
    val canChooseCoordinates: Boolean = false,
    val coordinates: DxfCoordinates = DxfCoordinates.LOCAL,
    val unit: DxfUnit = DxfUnit.METRE,
    val summary: DxfExportSummary? = null,
    val result: ExportResult? = null,
) {
    /** Hay puntos que exportar (RF-18) y la altura del texto vale (RF-8). */
    val canExport: Boolean get() = loaded && pointCount > 0 && heightValid
}

/**
 * Exportación de los puntos de un proyecto, o de los seleccionados, y de sus líneas y polígonos, a un DXF R12 (S029): las opciones se
 * recuerdan para todos los proyectos (RF-20), el fichero se escribe **entero en una carpeta de trabajo** y solo entonces se copia al destino,
 * así un fallo no deja un fichero a medias (RF-19), y el proyecto nunca se modifica (RF-21).
 */
@HiltViewModel
class DxfExportViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val selection: ExportSelection,
    private val preferences: DxfExportPreferences,
    private val log: AppLog = AppLog.NONE,
    private val lines: ObjectLines = ObjectLines.NONE,
) : ViewModel() {
    /** [lines] y [allPoints]: lo que va al fichero y todos los puntos del proyecto, de donde salen los vértices (RF-2, RF-3). */
    private class Content(val project: Project, val points: List<Point>, val lines: List<ProjectLine>, val allPoints: List<Point>)

    private val content = MutableStateFlow<Content?>(null)
    private val options = MutableStateFlow(DxfExportOptions())
    private val heightText = MutableStateFlow(formatHeight(DxfExportOptions.DEFAULT_TEXT_HEIGHT_M))
    private val summary = MutableStateFlow<DxfExportSummary?>(null)
    private val result = MutableStateFlow<ExportResult?>(null)

    val state: StateFlow<DxfExportUiState> = combine(content, options, heightText, summary, result) {
            loaded,
            chosen,
            height,
            last,
            outcome,
        ->
        if (loaded == null) return@combine DxfExportUiState()
        val project = loaded.project
        val coordinates = DxfDocumentBuilder.effectiveCoordinates(project, chosen.coordinates)
        DxfExportUiState(
            loaded = true,
            projectName = project.name,
            pointCount = loaded.points.size,
            lineCount = loaded.lines.count { !it.closed },
            polygonCount = loaded.lines.count { it.closed },
            options = chosen,
            heightText = height,
            heightValid = parseHeight(height) != null,
            canChooseCoordinates = project.crs.isUtm,
            coordinates = coordinates,
            unit = DxfDocumentBuilder.unitOf(project, coordinates),
            summary = last,
            result = outcome,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DxfExportUiState())

    /** Con [selectionOnly], solo los puntos de [ExportSelection] y las líneas con todos sus vértices entre ellos (RF-2); si no, todo (RF-1). */
    fun start(projectId: String, selectionOnly: Boolean) {
        if (content.value?.project?.id == projectId) return
        viewModelScope.launch {
            val project = projects.project(projectId) ?: return@launch
            val remembered = preferences.options.first()
            options.value = remembered
            heightText.value = formatHeight(remembered.textHeightM)
            val all = points.pointsForExport(projectId)
            val ids = selection.ids
            val chosen = if (selectionOnly) all.filter { it.id in ids } else all
            val projectLines = lines.lines(projectId).first()
            val exported = if (selectionOnly) projectLines.filter { line -> line.pointIds.all { it in ids } } else projectLines
            content.value = Content(project, chosen, exported, all)
        }
    }

    /** Marca o desmarca una etiqueta (RF-7). */
    fun setLabel(label: DxfLabel, on: Boolean) {
        val labels = if (on) options.value.labels + label else options.value.labels - label
        change(options.value.copy(labels = labels))
    }

    /** El sistema de las coordenadas del fichero (RF-11, RF-12). */
    fun setCoordinates(coordinates: DxfCoordinates) = change(options.value.copy(coordinates = coordinates))

    /** Lo que se ha escrito en la altura del texto, en metros: de 0,01 a 100 (RF-8); un valor que no vale no se recuerda. */
    fun setTextHeight(text: String) {
        heightText.value = text
        summary.value = null
        val parsed = parseHeight(text) ?: return
        change(options.value.copy(textHeightM = parsed))
    }

    private fun change(chosen: DxfExportOptions) {
        options.value = chosen
        summary.value = null
        viewModelScope.launch { preferences.save(chosen) } // RF-20
    }

    /** `<proyecto>_dxf_<AAAAMMDD-HHMM>.dxf` con la hora local del móvil (RF-16). */
    fun fileName(at: Instant, zone: ZoneId): String = ExportFileName.of(state.value.projectName, "dxf", at, zone, "dxf")

    /**
     * Escribe el DXF entero en [into] (para compartirlo, RF-17); false si no hay puntos (RF-18), la altura no vale (RF-8) o no se ha podido
     * escribir, sin dejar fichero (RF-19). El proyecto no cambia (RF-21).
     */
    suspend fun writeFile(into: File): Boolean {
        val loaded = content.value ?: return false
        if (!state.value.canExport) return false
        val chosen = options.value
        return withContext(Dispatchers.IO) {
            try {
                val built = DxfDocumentBuilder.build(loaded.points, loaded.lines, loaded.allPoints, loaded.project, chosen)
                val replaced = BufferedOutputStream(into.outputStream()).use { DxfWriter.write(built.document, it) }
                summary.value = built.summary.copy(replacedChars = replaced)
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
     * Escribe el DXF en [out], el destino que eligió el usuario con el selector de ficheros (RF-17): primero entero en [workDir] y solo
     * entonces lo copia, así un fallo del disco no deja un fichero a medias en el destino (RF-19). La carpeta de trabajo queda vacía.
     */
    suspend fun writeTo(out: OutputStream, workDir: File): Boolean {
        if (!state.value.canExport) return false
        val temporary = File(workDir, "dxf-${UUID.randomUUID()}.tmp")
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

    /** Lo que pasó al guardar o compartir (RF-17, RF-19); el registro lleva el hecho y no los puntos (S014/RF-5). */
    fun report(outcome: ExportResult?) {
        result.value = outcome
        val project = state.value.projectName
        when (outcome) {
            ExportResult.SAVED -> log.info(LogCategory.APP, "DXF exportado: «$project», ${state.value.pointCount} puntos")
            ExportResult.FAILED -> log.error(LogCategory.APP, "No se pudo exportar el DXF de «$project»")
            null -> Unit
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** La altura escrita, con coma o punto decimal, si está entre el mínimo y el máximo (RF-8). */
        fun parseHeight(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= DxfExportOptions.MIN_TEXT_HEIGHT_M && it <= DxfExportOptions.MAX_TEXT_HEIGHT_M }

        fun formatHeight(valueM: Double): String = BigDecimal(valueM.toString()).stripTrailingZeros().toPlainString()
    }
}
