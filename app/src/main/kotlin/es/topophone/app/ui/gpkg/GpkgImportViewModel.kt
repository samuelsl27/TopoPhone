package es.topophone.app.ui.gpkg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.app.ui.points.ImportSource
import es.topophone.app.ui.projects.ProjectCenterProvider
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.formats.gpkg.GpkgFileKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgImportSummary
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.format.AngleAxis
import es.topophone.core.model.format.AngleInput
import es.topophone.core.model.format.NumberInput
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.CentralPointRejection
import es.topophone.core.survey.CentralPointResolver
import es.topophone.core.survey.CentralPointResult
import es.topophone.data.gpkg.GeoPackageImporter
import es.topophone.data.gpkg.GeoPackageReader
import es.topophone.data.gpkg.GeoPackageScan
import es.topophone.data.gpkg.GpkgImportFailure
import es.topophone.data.gpkg.GpkgImportOptions
import es.topophone.data.gpkg.GpkgImportResult
import es.topophone.data.gpkg.GpkgLayerChoice
import es.topophone.data.gpkg.GpkgLayerIndex
import es.topophone.data.gpkg.GpkgOpenFailure
import es.topophone.data.gpkg.GpkgOpenResult
import es.topophone.data.gpkg.GpkgSession
import es.topophone.data.map.MapPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Por qué no se abre la pantalla de importación del GeoPackage (S048/RF-2 a RF-4). */
enum class GpkgFileProblem { EMPTY, NOT_GEOPACKAGE, DAMAGED, NO_LAYERS, TOO_BIG, NO_SPACE, UNREADABLE }

/** En qué punto está la importación de un GeoPackage. */
enum class GpkgImportStage { LOADING, PROBLEM, CONFIG, IMPORTING, RESULT }

/**
 * Una capa de la lista: lo que dice el fichero, si se importa (S048/RF-5), su sistema de coordenadas —el que declara el fichero y el que
 * eligió el usuario— (RF-8, RF-9) y dónde cae en el proyecto (RF-12).
 */
data class GpkgLayerRow(
    val info: GpkgLayerInfo,
    val marked: Boolean = true,
    /** Lo que declara el fichero frente a los sistemas de la app. */
    val crs: GpkgCrs = GpkgCrs.Undeclared,
    /** El sistema que el usuario eligió para esta capa en lugar del que declara el fichero. */
    val chosen: ProjectCrs? = null,
    /** La extensión de la capa en el sistema del proyecto (RF-12); null si no se ha calculado o no cabe en él. */
    val extent: CadExtent? = null,
    /** La capa tiene objetos y su extensión no cabe en el sistema del proyecto con el sistema elegido. */
    val outsideArea: Boolean = false,
    val distanceKm: Double? = null,
    /** La columna con la que se nombra cada objeto (RF-21); null es «Sin nombre»: se nombra por su número en la capa (RF-22). */
    val nameField: String? = null,
) {
    /** El sistema con el que se importa: el elegido, o el del fichero si es uno de la app; null mientras falte (RF-9). */
    val effective: GpkgCrs.Supported? get() = chosen?.let(GpkgCrsCatalog::supported) ?: (crs as? GpkgCrs.Supported)
}

/** Lo que muestra la pantalla de importar un GeoPackage (S048). */
data class GpkgImportUiState(
    val stage: GpkgImportStage = GpkgImportStage.LOADING,
    val fileName: String = "",
    val problem: GpkgFileProblem? = null,
    val projectName: String = "",
    val projectCrs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
    val layers: List<GpkgLayerRow> = emptyList(),
    /** Las tablas sin geometría o de teselas que no se importan (RF-6). */
    val leftOutTables: List<String> = emptyList(),
    /** Se están calculando las extensiones de las capas (RF-12). */
    val scanning: Boolean = false,
    /** Alguna capa marcada queda a más de 100 km del centro del proyecto y hay que confirmarlo (RF-12). */
    val farWarning: Boolean = false,
    val farConfirmed: Boolean = false,
    /** La zona: «Todo el contenido» o un radio alrededor del centro del proyecto (RF-13). */
    val areaAll: Boolean = false,
    val radiusM: Double = ImportArea.DEFAULT_RADIUS_M,
    /** El proyecto tiene centro (punto central, media de sus puntos o última vista del mapa) (RF-14). */
    val hasCenter: Boolean = true,
    /** Cuántos objetos entrarían en la zona (RF-16). */
    val zone: GpkgZoneUi = GpkgZoneUi(),
    /** El punto central fijado del proyecto, con el que se fija desde esta pantalla (S006/RF-33). */
    val centralPoint: CentralPoint? = null,
    val centralError: CentralPointRejection? = null,
    val linearUnit: LinearUnit = LinearUnit.METRE,
    val angularUnit: AngularUnit = AngularUnit.DMS,
    val mapSettings: MapSettings = MapSettings(),
    /** ¿Usar las cotas de la capa? Sí por defecto (RF-19). */
    val useElevations: Boolean = true,
    /** Los objetos leídos y los que hay en total durante la importación (RF-29). */
    val importDone: Long = 0,
    val importTotal: Long = 0,
    /** Por qué la última importación no guardó nada (RF-30); null si no ha fallado. */
    val failure: GpkgImportFailure? = null,
    /** El usuario canceló la última importación: no se guardó nada (RF-30). */
    val cancelled: Boolean = false,
    val result: GpkgImportOutcome? = null,
) {
    val markedLayers: List<GpkgLayerRow> get() = layers.filter { it.marked }

    /** Con un radio hace falta el centro del proyecto; sin él hay que fijarlo o elegir «Todo el contenido» (RF-14). */
    val needsCenter: Boolean get() = !areaAll && !hasCenter

    /** Hay capas marcadas, todas con sistema, la zona está clara y bajo el límite, y los avisos están confirmados. */
    val canImport: Boolean
        get() = stage == GpkgImportStage.CONFIG &&
            !scanning &&
            markedLayers.isNotEmpty() &&
            markedLayers.all { it.effective != null } &&
            !needsCenter &&
            !zone.counting &&
            !zone.exceeded &&
            (!farWarning || farConfirmed)
}

/** Lo que dejó una importación: el dibujo nuevo y su resumen (S048/RF-25). */
data class GpkgImportOutcome(val drawingId: String, val summary: GpkgImportSummary)

/** El recuento de los objetos de la zona: en marcha, cuántos son y si pasan del límite de 500.000 (RF-16). */
data class GpkgZoneUi(val counting: Boolean = false, val objects: Int = 0, val exceeded: Boolean = false)

/**
 * La importación de un GeoPackage a un proyecto (S048): reconoce el fichero por su contenido, lo abre de solo lectura y lista sus
 * capas con geometría para marcar cuáles se importan (RF-1 a RF-7); propone el sistema de coordenadas de cada una y deja elegir otro
 * (RF-8 a RF-10), y calcula su extensión y su distancia al centro del proyecto, con el aviso de más de 100 km (RF-12). La sesión del
 * fichero vive lo que la pantalla y se cierra, borrando su copia temporal, al salir (RNF-T5).
 */
@HiltViewModel
class GpkgImportViewModel @Inject constructor(
    private val reader: GeoPackageReader,
    private val importer: GeoPackageImporter,
    private val projects: ProjectRepository,
    private val centers: ProjectCenterProvider,
    private val receiver: ReceiverSession,
    private val mapPreferences: MapPreferences,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val log: AppLog = AppLog.NONE,
) : ViewModel() {
    private val mutable = MutableStateFlow(GpkgImportUiState())
    val state: StateFlow<GpkgImportUiState> = mutable.asStateFlow()

    private var started = false
    private var session: GpkgSession? = null
    private var project: Project? = null
    private var center: CentralPoint? = null
    private var scan: GeoPackageScan? = null
    private val indexes = ConcurrentHashMap<String, GpkgLayerIndex>()
    private val resolver = CentralPointResolver()
    private var countJob: Job? = null

    /** La espera antes de recontar al cambiar el radio, para no recontar en cada paso del control (RNF); los tests la quitan. */
    internal var debounceMs: Long = DEBOUNCE_MS

    /** El límite de objetos de la zona (RF-16); los tests lo bajan. */
    internal var maxObjects: Int = ImportArea.MAX_OBJECTS

    /** Prepara la importación de [source] en el proyecto [projectId]; solo la primera vez (RF-1). */
    fun start(projectId: String, source: ImportSource) {
        if (started) return
        started = true
        mutable.value = GpkgImportUiState(fileName = source.name)
        viewModelScope.launch { mapPreferences.settings.collect { settings -> mutable.update { it.copy(mapSettings = settings) } } }
        viewModelScope.launch(io) { load(projectId, source) }
    }

    private fun problem(problem: GpkgFileProblem) {
        mutable.update { it.copy(stage = GpkgImportStage.PROBLEM, problem = problem, scanning = false) }
    }

    private suspend fun load(projectId: String, source: ImportSource) {
        val project = projects.project(projectId) ?: return problem(GpkgFileProblem.UNREADABLE)
        this.project = project
        // Con el tamaño desconocido se cuenta el máximo para comprobar el espacio libre; el lector corta lo que pase del límite.
        val size = source.sizeBytes ?: GpkgFileKind.MAX_BYTES
        val result = try {
            reader.open(size) { source.open() ?: throw IOException("No se puede abrir el fichero") }
        } catch (_: IOException) {
            return problem(GpkgFileProblem.UNREADABLE)
        } catch (_: SecurityException) {
            return problem(GpkgFileProblem.UNREADABLE)
        }
        when (result) {
            is GpkgOpenResult.Failed -> {
                log.info(LogCategory.APP, "GeoPackage no abierto: ${result.reason.name.lowercase()}")
                problem(result.reason.toProblem())
            }
            is GpkgOpenResult.Opened -> {
                val opened = result.session
                session = opened
                scan = GeoPackageScan(opened, maxObjects = maxObjects)
                center = centers.center(project) // S006/RF-37: un único cálculo
                mutable.update {
                    GpkgImportUiState(
                        stage = GpkgImportStage.CONFIG,
                        fileName = source.name,
                        projectName = project.name,
                        projectCrs = project.crs,
                        layers = opened.layers.map { GpkgLayerRow(it, crs = opened.crs(it), nameField = suggestNameField(it.columns)) },
                        leftOutTables = opened.leftOutTables,
                        scanning = true,
                        // El recuento está pendiente desde el principio: no hay un instante en que se pueda importar sin contar.
                        zone = GpkgZoneUi(counting = true),
                        hasCenter = center != null,
                        centralPoint = project.centralPoint,
                        linearUnit = project.linearUnit,
                        angularUnit = project.angularUnit,
                        mapSettings = it.mapSettings,
                    )
                }
                index(opened)
            }
        }
    }

    /** Las envolventes de cada capa, una vez; con ellas la extensión y la distancia se recalculan al cambiar el sistema (RF-12). */
    private suspend fun index(opened: GpkgSession) {
        val scan = scan ?: return
        for (layer in opened.layers) indexes[layer.table] = scan.index(layer)
        recompute()
        mutable.update { it.copy(scanning = false) }
        recount()
    }

    private fun GpkgOpenFailure.toProblem() = when (this) {
        GpkgOpenFailure.EMPTY -> GpkgFileProblem.EMPTY
        GpkgOpenFailure.NOT_GEOPACKAGE -> GpkgFileProblem.NOT_GEOPACKAGE
        GpkgOpenFailure.DAMAGED -> GpkgFileProblem.DAMAGED
        GpkgOpenFailure.NO_LAYERS -> GpkgFileProblem.NO_LAYERS
        GpkgOpenFailure.TOO_LARGE -> GpkgFileProblem.TOO_BIG
        GpkgOpenFailure.NO_SPACE -> GpkgFileProblem.NO_SPACE
        GpkgOpenFailure.READ_ERROR -> GpkgFileProblem.UNREADABLE
    }

    /** Marca o desmarca la capa [table] (RF-5). */
    fun setMarked(table: String, marked: Boolean) {
        mutable.update { state ->
            state.copy(layers = state.layers.map { if (it.info.table == table) it.copy(marked = marked) else it }, farConfirmed = false)
        }
        recompute()
        recount()
    }

    /** El usuario elige el sistema de coordenadas de la capa [table]; con null vuelve al que declara el fichero (RF-8, RF-9). */
    fun setCrs(table: String, crs: ProjectCrs?) {
        mutable.update { state ->
            state.copy(layers = state.layers.map { if (it.info.table == table) it.copy(chosen = crs) else it }, farConfirmed = false)
        }
        recompute()
        recount()
    }

    fun confirmFar() = mutable.update { it.copy(farConfirmed = true) }

    /** El campo de nombre de la capa [table]; null es «Sin nombre» (RF-21). */
    fun setNameField(table: String, field: String?) {
        mutable.update { state -> state.copy(layers = state.layers.map { if (it.info.table == table) it.copy(nameField = field) else it }) }
    }

    /** ¿Usar las cotas de la capa? (RF-19). */
    fun setUseElevations(use: Boolean) = mutable.update { it.copy(useElevations = use) }

    private var importJob: Job? = null

    /**
     * Importa las capas marcadas con las opciones de ahora, en segundo plano y con progreso (RF-29). Si falla o se cancela no queda
     * nada guardado (RF-30) y se vuelve a la configuración; si acaba, queda el resumen (RF-25).
     */
    fun import() {
        val current = mutable.value
        if (!current.canImport) return
        val project = project ?: return
        val opened = session ?: return
        val area = currentArea() ?: return
        val choices = current.markedLayers.map { row ->
            val effective = checkNotNull(row.effective)
            GpkgLayerChoice(row.info, effective.source, row.nameField, wgs84 = effective.wgs84 && row.chosen == null)
        }
        mutable.update { it.copy(stage = GpkgImportStage.IMPORTING, importDone = 0, importTotal = 0, failure = null, cancelled = false) }
        importJob = viewModelScope.launch(io) {
            try {
                val result = importer.import(
                    projectId = project.id,
                    crs = project.crs,
                    fileName = current.fileName,
                    options = GpkgImportOptions(choices, current.useElevations, area),
                    session = opened,
                    onProgress = { done, total -> mutable.update { it.copy(importDone = done, importTotal = total) } },
                )
                when (result) {
                    is GpkgImportResult.Imported -> {
                        log.info(LogCategory.APP, importedMessage(result.summary))
                        closeSession() // el fichero ya está leído: la copia temporal no hace falta más (RNF-T5)
                        mutable.update {
                            it.copy(stage = GpkgImportStage.RESULT, result = GpkgImportOutcome(result.drawingId, result.summary))
                        }
                    }
                    is GpkgImportResult.Failed -> {
                        log.error(LogCategory.APP, "Importación de GeoPackage fallida: ${result.reason.name.lowercase()}")
                        mutable.update { it.copy(stage = GpkgImportStage.CONFIG, failure = result.reason) }
                    }
                }
            } catch (cancelled: CancellationException) {
                log.info(LogCategory.APP, "Importación de GeoPackage cancelada: no se ha guardado nada")
                mutable.update { it.copy(stage = GpkgImportStage.CONFIG, cancelled = true) }
                throw cancelled
            }
        }
    }

    /** Cancela la importación en curso (RF-29, RF-30). */
    fun cancel() {
        importJob?.cancel()
    }

    /** El log no lleva nombres de capas ni de objetos ni coordenadas, solo los recuentos (RF-28). */
    private fun importedMessage(summary: GpkgImportSummary) =
        "GeoPackage importado: ${summary.importedTotal} objetos, ${summary.ignoredTotal} ignorados, ${summary.layers.size} capas"

    /** Escribe el resumen de la importación como texto (RF-25); false si no se pudo escribir. */
    suspend fun writeSummary(out: OutputStream, labels: GpkgSummaryLabels): Boolean = withContext(io) {
        val summary = mutable.value.result?.summary ?: return@withContext false
        try {
            out.bufferedWriter(Charsets.UTF_8).use { it.write(GpkgSummaryText.build(summary, labels)) }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    /** «Todo el contenido» o un radio alrededor del centro del proyecto (RF-13). */
    fun setAllContent(all: Boolean) {
        mutable.update { it.copy(areaAll = all) }
        recount()
    }

    /** El radio de la zona, entre 100 m y 100 km (RF-13). */
    fun setRadius(radiusM: Double) {
        mutable.update { it.copy(radiusM = radiusM.coerceIn(ImportArea.MIN_RADIUS_M, ImportArea.MAX_RADIUS_M)) }
        recount()
    }

    /** La zona de ahora: todo, o el círculo alrededor del centro del proyecto; null si pide el centro y el proyecto no lo tiene (RF-14). */
    private fun currentArea(): ImportArea? {
        val state = mutable.value
        if (state.areaAll) return ImportArea.All
        val center = center ?: return null
        return ImportArea.Circle(center, state.radiusM)
    }

    /**
     * Cuenta los objetos que entrarían en la zona con las capas marcadas y sus sistemas, en segundo plano y cancelando el cálculo
     * anterior tras una espera corta (RF-13, RF-16).
     */
    private fun recount() {
        countJob?.cancel()
        val project = project ?: return
        val scan = scan ?: return
        if (mutable.value.stage != GpkgImportStage.CONFIG || mutable.value.scanning) return
        mutable.update { it.copy(zone = it.zone.copy(counting = true)) }
        countJob = viewModelScope.launch(io) {
            if (debounceMs > 0) delay(debounceMs)
            val area = currentArea()
            val rows = mutable.value.markedLayers
            var objects = 0
            var exceeded = false
            if (area != null) {
                for (row in rows) {
                    val source = row.effective?.source ?: continue
                    val index = indexes[row.info.table] ?: continue
                    val count = scan.count(index, source, project.crs, area)
                    objects += count.inZone
                    if (count.exceeded || objects > maxObjects) {
                        exceeded = true
                        break
                    }
                }
            }
            mutable.update { it.copy(zone = GpkgZoneUi(counting = false, objects = objects, exceeded = exceeded)) }
        }
    }

    // ---- El punto central del proyecto, fijado desde esta pantalla (S006/RF-33, S048/RF-14) ----

    /** Fija el punto central con lo escrito en el SC del proyecto; si no vale, no cambia nada y dice por qué. */
    fun fixCentralTyped(first: String, second: String) {
        val project = project ?: return
        val projected = project.crs.isUtm
        val a = if (projected) {
            NumberInput.parse(
                first,
            )?.let(project.linearUnit::toMetres)
        } else {
            AngleInput.parse(first, project.angularUnit, AngleAxis.LATITUDE)
        }
        val b = if (projected) {
            NumberInput.parse(
                second,
            )?.let(project.linearUnit::toMetres)
        } else {
            AngleInput.parse(second, project.angularUnit, AngleAxis.LONGITUDE)
        }
        val result = if (a == null || b == null) {
            CentralPointResult.Rejected(CentralPointRejection.INVALID_NUMBER)
        } else {
            resolver.fromTyped(if (projected) EntryForm.PROJECT_CRS else EntryForm.GEOGRAPHIC, a, b, project.crs)
        }
        applyCentral(result)
    }

    /** Fija el punto central con la posición actual del receptor o de una reproducción; sin ella, dice por qué. */
    fun fixCentralFromReceiver() {
        val project = project ?: return
        val snapshot = receiver.state.value
        val fix = snapshot.gga?.takeIf { snapshot.connection is ConnectionState.Receiving }
        applyCentral(resolver.fromFix(fix, project.crs))
    }

    /** Un toque del selector del mapa: el punto, si cae en el ámbito del SC del proyecto, o el motivo. */
    fun resolveCentralTap(latitudeDeg: Double, longitudeDeg: Double): CentralPointResult =
        resolver.fromTap(latitudeDeg, longitudeDeg, project?.crs ?: ProjectCrs.ETRS89_UTM30)

    /** El punto aceptado en el selector del mapa. */
    fun setCentralPoint(point: CentralPoint) = applyCentral(CentralPointResult.Fixed(point))

    /** Quita el punto central: el centro del proyecto vuelve a calcularse sin él (S006/RF-35). */
    fun clearCentralPoint() = applyCentral(null)

    /** Donde empieza el mapa del selector: el punto central si lo hay; si no, la posición del receptor; si no, España entera. */
    fun pickerStart(): MapViewport? {
        mutable.value.centralPoint?.let { return MapViewport(it.latitudeDeg, it.longitudeDeg, PICKER_ZOOM) }
        val snapshot = receiver.state.value
        val fix = snapshot.gga?.takeIf { snapshot.connection is ConnectionState.Receiving && it.hasPosition } ?: return null
        return MapViewport(fix.latitudeDeg, fix.longitudeDeg, PICKER_ZOOM)
    }

    private fun applyCentral(result: CentralPointResult?) {
        if (result is CentralPointResult.Rejected) {
            mutable.update { it.copy(centralError = result.reason) }
            return
        }
        val point = (result as? CentralPointResult.Fixed)?.point
        val current = project ?: return
        viewModelScope.launch(io) {
            val saved = projects.update(current.id, current.toDraft().copy(centralPoint = point))
            if (saved !is SaveResult.Saved) return@launch
            val updated = projects.project(current.id) ?: return@launch
            project = updated
            center = centers.center(updated)
            mutable.update { it.copy(centralPoint = point, centralError = null, hasCenter = center != null) }
            recompute()
            recount()
        }
    }

    /** La extensión de cada capa marcada en el sistema del proyecto y su distancia al centro, con los sistemas de ahora (RF-12). */
    private fun recompute() {
        val project = project ?: return
        val scan = scan ?: return
        mutable.update { state ->
            val layers = state.layers.map { row ->
                val index = indexes[row.info.table]
                val source = row.effective?.source
                if (!row.marked || index == null || source == null || index.size == 0) {
                    return@map row.copy(extent = null, outsideArea = false, distanceKm = null)
                }
                val extent = scan.extent(index, source, project.crs)
                val km = extent?.let { e -> center?.let { c -> scan.distanceToCenterM(e, project.crs, c) } }?.div(METRES_PER_KM)
                row.copy(extent = extent, outsideArea = extent == null, distanceKm = km)
            }
            state.copy(layers = layers, farWarning = layers.any { it.marked && CadPlacement.isFar(it.distanceKm) })
        }
    }

    override fun onCleared() {
        importJob?.cancel()
        closeSession()
    }

    private companion object {
        const val METRES_PER_KM = 1000.0
        const val DEBOUNCE_MS = 150L
        const val PICKER_ZOOM = 16.0
    }
}

private val NAME_FIELDS = listOf("name", "nombre", "id", "codigo", "código")

/**
 * El campo de nombre propuesto de una capa: el que se llame `name`, `nombre`, `id` o `codigo` (con o sin tilde), sin distinguir
 * mayúsculas y por ese orden; null si no hay ninguno (S048/RF-21).
 */
internal fun suggestNameField(columns: List<GpkgColumn>): String? {
    for (wanted in NAME_FIELDS) columns.firstOrNull { it.name.equals(wanted, ignoreCase = true) }?.let { return it.name }
    return null
}
