package es.topophone.app.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.data.photo.PhotoExportEstimate
import es.topophone.data.photo.PhotoExportOutcome
import es.topophone.data.photo.PhotoExportPoint
import es.topophone.data.photo.PhotoExporter
import es.topophone.data.photo.PhotoRepository
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

/** Lo que dice la última exportación de fotos (S056/RF-13): fotos y puntos escritos, fotos omitidas y lo que pesa el ZIP. */
data class PhotoExportReport(val photos: Int, val points: Int, val omitted: Int, val zipBytes: Long)

/**
 * Lo que muestra la pantalla de exportar las fotos (S056/RF-3, RF-8, RF-10 a RF-13).
 *
 * @property photoCount las fotos y [pointCount] los puntos con fotos que se exportarán; [photoBytes] lo que ocupan sus ficheros (RF-3).
 * @property estimatedBytes lo que ocupará como mucho el ZIP; si pasa de 1 GB ([needsSizeConfirmation]) hay que avisar antes (RF-10).
 * @property report lo que dice la última exportación (RF-13); null hasta exportar o al pedir otro guardado.
 * @property noSpace no había sitio para el ZIP (RF-11).
 */
data class PhotoExportUiState(
    val loaded: Boolean = false,
    val projectName: String = "",
    val photoCount: Int = 0,
    val pointCount: Int = 0,
    val photoBytes: Long = 0,
    val estimatedBytes: Long = 0,
    val needsSizeConfirmation: Boolean = false,
    val report: PhotoExportReport? = null,
    val result: ExportResult? = null,
    val noSpace: Boolean = false,
) {
    /** Hay fotos que exportar; sin ellas no se guarda ni se comparte (RF-8). */
    val canExport: Boolean get() = loaded && photoCount > 0
}

/**
 * Exportación de las fotos de los puntos de un proyecto, o de los seleccionados, a un ZIP con su índice (S056/RF-1 a RF-14): el ZIP se
 * escribe **entero en una carpeta de trabajo** y solo entonces se copia al destino, así un fallo no deja un fichero a medias (RF-11). El
 * proyecto y las fotos nunca se modifican (RF-14) y el registro no lleva nombres de puntos ni rutas (RNF-T12).
 */
@HiltViewModel
class PhotoExportViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val selection: ExportSelection,
    private val photos: PhotoRepository,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private class Content(val projectName: String, val points: List<PhotoExportPoint>, val estimate: PhotoExportEstimate)

    private var startedWith: String? = null
    private val content = MutableStateFlow<Content?>(null)
    private val report = MutableStateFlow<PhotoExportReport?>(null)
    private val result = MutableStateFlow<ExportResult?>(null)
    private val noSpace = MutableStateFlow(false)

    /** Quien escribe el ZIP; cambia en los tests para dar el espacio libre. */
    internal var exporter: PhotoExporter = PhotoExporter(photos)

    val state: StateFlow<PhotoExportUiState> = combine(content, report, result, noSpace) { loaded, last, outcome, full ->
        if (loaded == null) return@combine PhotoExportUiState()
        PhotoExportUiState(
            loaded = true,
            projectName = loaded.projectName,
            photoCount = loaded.estimate.photos,
            pointCount = loaded.estimate.points,
            photoBytes = loaded.estimate.photoBytes,
            estimatedBytes = loaded.estimate.zipBytes,
            needsSizeConfirmation = loaded.estimate.exceedsWarning,
            report = last,
            result = outcome,
            noSpace = full,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PhotoExportUiState())

    /** Con [selectionOnly], solo las fotos de los puntos de [ExportSelection] (RF-2); si no, las de todos los puntos activos (RF-1). */
    fun start(projectId: String, selectionOnly: Boolean) {
        if (startedWith == "$projectId/$selectionOnly") return
        startedWith = "$projectId/$selectionOnly"
        viewModelScope.launch {
            val project = projects.project(projectId) ?: return@launch
            val all = points.pointsForExport(projectId) // activos, ocultos incluidos y nunca la papelera (RF-1)
            val ids = selection.ids
            val chosen = if (selectionOnly) all.filter { it.id in ids } else all
            val byPoint = if (chosen.isEmpty()) emptyMap() else photos.photosForExport(chosen.map { it.id })
            val exported = chosen.mapNotNull { point -> byPoint[point.id]?.let { PhotoExportPoint(point.id, point.name, it) } }
            content.value = Content(project.name, exported, exporter.estimate(exported))
        }
    }

    /** `<proyecto>_fotos_<AAAAMMDD-HHMM>.zip` con la hora local del móvil (RF-12). */
    fun fileName(at: Instant, zone: ZoneId): String = ExportFileName.of(state.value.projectName, "fotos", at, zone, "zip")

    /**
     * Escribe el ZIP entero en [into] (para compartirlo, RF-12); false si no hay fotos, no hay sitio (RF-11), ninguna se pudo leer (RF-9) o
     * no se pudo escribir (RF-11), sin dejar fichero.
     */
    suspend fun writeFile(into: File): Boolean {
        val loaded = content.value ?: return false
        if (!state.value.canExport) return false
        noSpace.value = false
        return when (val outcome = exporter.writeFile(loaded.points, into)) {
            is PhotoExportOutcome.Written -> {
                val written = outcome.result
                report.value = PhotoExportReport(written.photosWritten, written.pointsWritten, written.photosSkipped, written.zipBytes)
                true
            }
            PhotoExportOutcome.NoSpace -> {
                noSpace.value = true // RF-11
                false
            }
            is PhotoExportOutcome.NothingReadable -> {
                report.value = PhotoExportReport(0, 0, outcome.skipped, 0) // RF-9: se cuentan aunque no haya ZIP
                false
            }
            PhotoExportOutcome.Failed -> false
        }
    }

    /**
     * Escribe el ZIP en [out], el destino que eligió el usuario con el selector de ficheros (RF-12): primero entero en [workDir] y solo
     * entonces lo copia, así un fallo del disco no deja un fichero a medias (RF-11). La carpeta de trabajo queda vacía.
     */
    suspend fun writeTo(out: OutputStream, workDir: File): Boolean {
        if (!state.value.canExport) return false
        val temporary = File(workDir, "fotos-${UUID.randomUUID()}.tmp")
        return withContext(Dispatchers.IO) {
            try {
                workDir.mkdirs()
                if (!writeFile(temporary)) return@withContext false
                temporary.inputStream().use { it.copyTo(out) }
                out.flush()
                true
            } catch (_: IOException) {
                report.value = null
                false
            } catch (_: SecurityException) {
                report.value = null
                false
            } finally {
                temporary.delete()
            }
        }
    }

    /**
     * Lo que pasó al guardar o compartir (RF-12); el registro lleva el hecho y no los puntos ni las rutas (S014/RF-5). `null` es que se
     * pide otro guardado: se borra también el resumen del anterior, así que si el usuario cancela el selector no queda como si fuera el nuevo.
     */
    fun report(outcome: ExportResult?) {
        result.value = outcome
        if (outcome == null) {
            noSpace.value = false
            report.value = null
        }
        val written = report.value
        when (outcome) {
            ExportResult.SAVED -> log.info(
                LogCategory.APP,
                "Fotos exportadas: ${written?.photos ?: 0} fotos de ${written?.points ?: 0} puntos",
            )
            ExportResult.FAILED -> log.error(LogCategory.APP, "No se pudieron exportar las fotos")
            null -> Unit
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
