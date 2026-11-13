package es.topophone.app.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.points.ObjectLines
import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.csv.ExportFileName
import es.topophone.core.formats.kml.export.KmlExport
import es.topophone.core.formats.kml.export.KmlExportBuilder
import es.topophone.core.formats.kml.export.KmlExportOptions
import es.topophone.core.formats.kml.export.KmlExportSummary
import es.topophone.core.formats.kml.export.KmlFormat
import es.topophone.core.formats.kml.export.KmlWriter
import es.topophone.core.formats.kml.export.KmzPhoto
import es.topophone.core.formats.kml.export.KmzResult
import es.topophone.core.formats.kml.export.KmzWriter
import es.topophone.core.formats.photo.PhotoArchiveNames
import es.topophone.core.formats.shp.export.ShpExportBuilder
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.point.PointPhoto
import es.topophone.core.model.project.Project
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.export.KmlExportPreferences
import es.topophone.data.export.KmlExportSettings
import es.topophone.data.photo.PhotoRepository
import es.topophone.data.point.PointRepository
import es.topophone.data.project.ProjectRepository
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Un KMZ de más de esto se avisa antes de escribirlo (S031/RF-30): 1 GB. */
const val KMZ_WARNING_BYTES = 1L shl 30

/** Lo que dice la última exportación a KML o KMZ (S031/RF-32): el resumen del documento y las fotos que entraron y las que se omitieron. */
data class KmlExportReport(
    val summary: KmlExportSummary,
    val format: KmlFormat,
    val withPhotos: Boolean,
    val photosIncluded: Int = 0,
    val photosOmitted: Int = 0,
)

/**
 * Lo que muestra la pantalla de exportar a KML o KMZ (S031/RF-20, RF-26, RF-28, RF-30 a RF-32).
 *
 * @property photoCount y [photoBytes] las fotos de los puntos que van y lo que ocupan, para decir lo que pesará el KMZ con fotos (RF-30).
 * @property includePhotos lo marcado; solo cuenta con KMZ ([withPhotos], RF-28).
 * @property report lo que dice la última exportación (RF-32); null hasta exportar o al cambiar una opción.
 * @property noSpace no había espacio libre para escribir el fichero (RF-30).
 */
data class KmlExportUiState(
    val loaded: Boolean = false,
    val projectName: String = "",
    val pointCount: Int = 0,
    val lineCount: Int = 0,
    val polygonCount: Int = 0,
    val photoCount: Int = 0,
    val photoBytes: Long = 0,
    val format: KmlFormat = KmlFormat.KML,
    val includeHeight: Boolean = false,
    val includePhotos: Boolean = false,
    val report: KmlExportReport? = null,
    val result: ExportResult? = null,
    val noSpace: Boolean = false,
) {
    /** Hay puntos que exportar (RF-31). */
    val canExport: Boolean get() = loaded && pointCount > 0

    /** Las fotos van en el KMZ: solo con KMZ y con «Incluir fotos» marcada (RF-28). */
    val withPhotos: Boolean get() = format == KmlFormat.KMZ && includePhotos

    /**
     * Lo que ocupará el fichero como mucho: el KML sin comprimir, con un tope por marca, más el tamaño de las fotos que van (RF-30). El KML de
     * un KMZ va comprimido, así que es una cota por arriba.
     */
    val estimatedBytes: Long get() = (pointCount + lineCount + polygonCount) * MARK_BYTES + if (withPhotos) photoBytes else 0L

    /** El KMZ va a pasar de 1 GB: hay que avisar y dejar continuar o cancelar antes de escribirlo (RF-30). */
    val needsSizeConfirmation: Boolean get() = withPhotos && estimatedBytes > KMZ_WARNING_BYTES

    private companion object {
        const val MARK_BYTES = 4_096L
    }
}

/**
 * Exportación de los puntos de un proyecto, o de los seleccionados, y de sus líneas y polígonos, a un KML o un KMZ con sus fotos (S031/RF-20 a
 * RF-32): el formato y las opciones se recuerdan para todos los proyectos (RF-32), el fichero se escribe **entero en una carpeta de trabajo**
 * y solo entonces se copia al destino, así un fallo no deja un fichero a medias (RF-31), y el proyecto nunca se modifica (RF-31).
 */
@HiltViewModel
class KmlExportViewModel @Inject constructor(
    private val points: PointRepository,
    private val projects: ProjectRepository,
    private val selection: ExportSelection,
    private val preferences: KmlExportPreferences,
    private val photos: PhotoRepository,
    private val log: AppLog = AppLog.NONE,
    private val lines: ObjectLines = ObjectLines.NONE,
    private val calculator: Lazy<ProjectPositionCalculator> = Lazy { ProjectPositionCalculator() },
) : ViewModel() {
    /**
     * [lines] y [allPoints]: lo que va al fichero y todos los puntos del proyecto, de donde salen los vértices (RF-20, RF-22); [photos] las
     * fotos de los puntos que van, por punto (RF-28).
     */
    private class Content(
        val project: Project,
        val points: List<Point>,
        val lines: List<ProjectLine>,
        val allPoints: List<Point>,
        val photos: Map<String, List<PointPhoto>>,
        val lineCount: Int,
        val polygonCount: Int,
    )

    /** Lo que se escribe: el documento, las fotos que lleva (nombre y origen) y el formato con el que se hizo. */
    private class Prepared(val export: KmlExport, val photoSources: List<KmzPhoto>, val state: KmlExportUiState)

    private val builder = KmlExportBuilder()
    private val content = MutableStateFlow<Content?>(null)
    private val settings = MutableStateFlow(KmlExportSettings())
    private val report = MutableStateFlow<KmlExportReport?>(null)
    private val result = MutableStateFlow<ExportResult?>(null)
    private val noSpace = MutableStateFlow(false)

    /** El espacio libre en la carpeta de [File]; cambia en los tests. */
    internal var freeSpace: (File) -> Long = { it.usableSpace }

    val state: StateFlow<KmlExportUiState> = combine(content, settings, report, result, noSpace) { loaded, chosen, last, outcome, full ->
        if (loaded == null) return@combine KmlExportUiState()
        val all = loaded.photos.values.flatten()
        KmlExportUiState(
            loaded = true,
            projectName = loaded.project.name,
            pointCount = loaded.points.size,
            lineCount = loaded.lineCount,
            polygonCount = loaded.polygonCount,
            photoCount = all.size,
            photoBytes = all.sumOf { it.sizeBytes },
            format = chosen.format,
            includeHeight = chosen.includeHeight,
            includePhotos = chosen.includePhotos,
            report = last,
            result = outcome,
            noSpace = full,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), KmlExportUiState())

    /** Con [selectionOnly], solo los puntos de [ExportSelection] y las líneas con todos sus vértices entre ellos (RF-20); si no, todo. */
    fun start(projectId: String, selectionOnly: Boolean) {
        if (content.value?.project?.id == projectId) return
        viewModelScope.launch {
            val project = projects.project(projectId) ?: return@launch
            // RF-32: lo recordado, salvo que el usuario ya haya cambiado algo mientras se cargaba el proyecto
            settings.compareAndSet(KmlExportSettings(), preferences.settings.first())
            val all = points.pointsForExport(projectId)
            val ids = selection.ids
            val chosen = if (selectionOnly) all.filter { it.id in ids } else all
            val projectLines = lines.lines(projectId).first()
            val exported = if (selectionOnly) ShpExportBuilder.linesOf(projectLines, ids) else projectLines
            val (lineCount, polygonCount) = withContext(Dispatchers.Default) { builder.countLines(chosen, exported, all) }
            val chosenPhotos = if (chosen.isEmpty()) emptyMap() else photos.photosForExport(chosen.map { it.id })
            content.value = Content(project, chosen, exported, all, chosenPhotos, lineCount, polygonCount)
        }
    }

    /** KML o KMZ; se recuerda para todos los proyectos (RF-32). Lo que dijo la exportación anterior ya no vale y se borra. */
    fun setFormat(chosen: KmlFormat) = change { it.copy(format = chosen) }

    /** «Incluir altura» (RF-26); se recuerda para todos los proyectos (RF-32). */
    fun setIncludeHeight(include: Boolean) = change { it.copy(includeHeight = include) }

    /** «Incluir fotos» (RF-28); solo cuenta con KMZ y se recuerda para todos los proyectos (RF-32). */
    fun setIncludePhotos(include: Boolean) = change { it.copy(includePhotos = include) }

    private fun change(edit: (KmlExportSettings) -> KmlExportSettings) {
        val next = edit(settings.value)
        settings.value = next
        report.value = null
        result.value = null
        noSpace.value = false
        viewModelScope.launch { preferences.save(next) }
    }

    /** `<proyecto>_kml_<AAAAMMDD-HHMM>.kml` o `.kmz` con la hora local del móvil (RF-31). */
    fun fileName(at: Instant, zone: ZoneId): String {
        val extension = if (state.value.format == KmlFormat.KMZ) "kmz" else "kml"
        return ExportFileName.of(state.value.projectName, "kml", at, zone, extension)
    }

    /**
     * Escribe el fichero entero en [into] (para compartirlo, RF-31); false si no hay puntos, no hay espacio (RF-30) o no se ha podido
     * escribir, sin dejar fichero (RF-31). El proyecto no cambia.
     */
    suspend fun writeFile(into: File): Boolean {
        val loaded = content.value ?: return false
        val current = state.value
        if (!current.canExport) return false
        noSpace.value = false
        if (freeSpace(into.absoluteFile.parentFile ?: into) < current.estimatedBytes + current.estimatedBytes / SPACE_MARGIN_DIVISOR) {
            noSpace.value = true // RF-30
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val prepared = prepare(loaded, current)
                val photosResult = BufferedOutputStream(into.outputStream(), BUFFER_BYTES).use { write(prepared, it) }
                report.value = KmlExportReport(
                    summary = prepared.export.summary,
                    format = current.format,
                    withPhotos = current.withPhotos,
                    photosIncluded = photosResult.photosWritten,
                    photosOmitted = photosResult.photosSkipped,
                )
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
     * Escribe el fichero en [out], el destino que eligió el usuario con el selector de ficheros (RF-31): primero entero en [workDir] y solo
     * entonces lo copia, así un fallo del disco no deja un fichero a medias en el destino. La carpeta de trabajo queda vacía.
     */
    suspend fun writeTo(out: OutputStream, workDir: File): Boolean {
        if (!state.value.canExport) return false
        val temporary = File(workDir, "kml-${UUID.randomUUID()}.tmp")
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
     * Lo que pasó al guardar o compartir (RF-31); el registro lleva el hecho y no los puntos (S014/RF-5). `null` es que se pide otro guardado:
     * se borra también el resumen del anterior, así que si el usuario cancela el selector no queda como si fuera el de esa petición.
     */
    fun report(outcome: ExportResult?) {
        result.value = outcome
        if (outcome == null) {
            noSpace.value = false
            report.value = null
        }
        val current = state.value
        val kind = current.format.name
        when (outcome) {
            ExportResult.SAVED -> log.info(
                LogCategory.APP,
                "$kind exportado: «${current.projectName}», ${current.pointCount} puntos" +
                    if (current.withPhotos) ", con ${report.value?.photosIncluded ?: 0} fotos" else "",
            )
            ExportResult.FAILED -> log.error(LogCategory.APP, "No se pudo exportar el $kind de «${current.projectName}»")
            null -> Unit
        }
    }

    /** El documento y las fotos que lleva (los nombres de las fotos los da [PhotoArchiveNames.Namer] solo a las marcas que se escriben). */
    private fun prepare(loaded: Content, current: KmlExportUiState): Prepared {
        val sources = ArrayList<KmzPhoto>()
        val namer = PhotoArchiveNames.Namer()
        val photoNames: (Point) -> List<String> = if (current.withPhotos) {
            { point ->
                val own = loaded.photos[point.id].orEmpty()
                val names = namer.next(point.name, own.size)
                own.forEachIndexed { i, photo -> sources += KmzPhoto(names[i]) { open(photo) } }
                names
            }
        } else {
            { emptyList() }
        }
        val options = KmlExportOptions(current.format, current.includeHeight, current.withPhotos)
        val export = builder.build(
            points = loaded.points,
            project = loaded.project,
            options = options,
            baseProjection = baseProjection(loaded.project),
            lines = loaded.lines,
            allPoints = loaded.allPoints,
            photoNames = photoNames,
        )
        return Prepared(export, sources, current)
    }

    private fun open(photo: PointPhoto) = (photos.file(photo.id) ?: throw FileNotFoundException(photo.id)).inputStream()

    /** Escribe el KML o el KMZ en [out] (cerrándolo) y dice cuántas fotos entraron; sin KMZ no hay fotos. */
    private fun write(prepared: Prepared, out: OutputStream): KmzResult {
        if (prepared.state.format == KmlFormat.KMZ) return KmzWriter.write(prepared.export.document, prepared.photoSources, out)
        out.writer(Charsets.UTF_8).buffered(BUFFER_BYTES).use { KmlWriter.write(prepared.export.document, it) }
        return KmzResult(0, 0)
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
        const val BUFFER_BYTES = 64 * 1024

        /** Hace falta el tamaño previsto más un 5 % (1/20) de espacio libre (RF-30). */
        const val SPACE_MARGIN_DIVISOR = 20L
    }
}
