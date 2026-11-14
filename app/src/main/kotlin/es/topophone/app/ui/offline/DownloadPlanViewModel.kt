package es.topophone.app.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.map.WmsLayerPlan
import es.topophone.app.ui.map.WmsLayerSource
import es.topophone.core.geodesy.AreaBounds
import es.topophone.core.geodesy.DetailZoom
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.OfflineAreaKind
import es.topophone.core.model.offline.OfflineLimits
import es.topophone.core.model.offline.OfflineSettings
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.data.offline.OfflinePreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Una fuente que se puede marcar para la zona (S037/RF-8). */
data class SourceChoice(val source: DownloadableSource, val selected: Boolean)

/**
 * Lo que ve la hoja «Descargar zona» (S037/RF-1 a RF-6, RF-8 a RF-14): el área y el nivel elegidos, las fuentes, la previsión con sus bloqueos y confirmaciones
 * y el espacio y la red de ahora.
 */
data class DownloadPlanUiState(
    val loaded: Boolean = false,
    val projectName: String = "",
    val name: String = "",
    val areaKinds: List<OfflineAreaKind> = emptyList(),
    val areaKind: OfflineAreaKind? = null,
    val radiusM: Double = OfflineSettings.DEFAULT_RADIUS_M,
    val marginM: Double = OfflineSettings.DEFAULT_MARGIN_M,
    val detail: DetailLevel = DetailLevel.NORMAL,
    val sources: List<SourceChoice> = emptyList(),
    val estimate: Estimate? = null,
    val storage: StorageState? = null,
    val network: NetworkKind = NetworkKind.NONE,
    val acknowledged: Set<EstimateConfirmation> = emptySet(),
    val started: Boolean = false,
) {
    /** Lo que falta por confirmar antes de empezar (RF-10, RF-14). */
    val pendingConfirmations: List<EstimateConfirmation> get() = estimate?.confirmations.orEmpty().filterNot { it in acknowledged }

    /** Hay algo que descargar y nada lo impide (RF-9, RF-12, RF-13). */
    val canStart: Boolean get() = estimate != null && estimate.sources.isNotEmpty() && estimate.canStart
}

/**
 * El plan de la descarga de una zona (S037/RF-2 a RF-6, RF-8 a RF-14, RF-15): ofrece las áreas que el proyecto permite, marca de entrada el mapa base y las capas
 * WMS por teselas, calcula la previsión en cada cambio y, al empezar, entrega el plan al descargador y recuerda lo elegido.
 */
@HiltViewModel
class DownloadPlanViewModel @Inject constructor(
    private val inputs: ZoneInputs,
    private val preferences: OfflinePreferences,
    private val store: OfflineRegionStore,
    private val storage: StorageInfo,
    private val network: NetworkInfo,
    private val downloader: OfflineDownloader,
    private val clock: ZoneClock,
) : ViewModel() {

    private class WmsChoice(val source: DownloadableSource, val template: String, val format: String, val ownServiceName: String?)

    private class Loaded(
        val projectId: String,
        val center: CentralPoint?,
        val points: List<CentralPoint>,
        val visible: GeoBox?,
        val wms: List<WmsChoice>,
        val usedBytes: Long,
    )

    private val mutableState = MutableStateFlow(DownloadPlanUiState())
    val state: StateFlow<DownloadPlanUiState> = mutableState.asStateFlow()

    private var loaded: Loaded? = null

    /** Abre el plan del proyecto [projectId]; [visible] es lo que se ve ahora en el mapa, o null si no se sabe (RF-2). */
    fun open(projectId: String, projectName: String, visible: GeoBox?) {
        viewModelScope.launch {
            val settings = preferences.settings.first()
            val center = inputs.center(projectId)
            val points = inputs.points(projectId)
            val basemap = inputs.basemap()
            val wms = inputs.wmsLayers(projectId)
                .sortedBy { it.layer.position }
                .filter { it.service.requestMode == WmsRequestMode.TILES && it.info?.mercatorCode != null }
                .mapNotNull { view ->
                    val plan = WmsLayerPlan.of(listOf(view.copy(layer = view.layer.copy(visible = true))))
                    val template = (plan.layers.firstOrNull()?.source as? WmsLayerSource.Tiles)?.template ?: return@mapNotNull null
                    WmsChoice(
                        source = DownloadableSource(SourceKind.WMS, view.title, view.service.id, view.layer.layerName),
                        template = template,
                        format = WmsLayerPlan.format(view.service, view.layer.layerName),
                        ownServiceName = if (view.service.presetKey == null) view.service.name else null,
                    )
                }
            preferencesLimit = settings.storageLimitBytes
            val used = store.list().filter { it.meta != null }.sumOf { it.progress.bytes }
            loaded = Loaded(projectId, center, points, visible, wms, used)
            val kinds = buildList {
                if (center != null) add(OfflineAreaKind.RADIUS)
                if (visible != null) add(OfflineAreaKind.VIEWPORT)
                if (center != null && points.isNotEmpty()) add(OfflineAreaKind.PROJECT)
            }
            val base = listOf(
                DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN"),
                DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA"),
                DownloadableSource(SourceKind.OPENFREEMAP, "Mapa OpenStreetMap"),
            )
            val chosenBase = when (basemap) {
                Basemap.IGN_VECTOR -> SourceKind.IGN_VECTOR
                Basemap.PNOA_ORTHOPHOTO -> SourceKind.PNOA
                Basemap.OSM_VECTOR -> SourceKind.OPENFREEMAP
                Basemap.NONE -> null
            }
            mutableState.value = DownloadPlanUiState(
                loaded = true,
                projectName = projectName,
                name = "$projectName – ${date()}",
                areaKinds = kinds,
                areaKind = settings.areaKind.takeIf { it in kinds } ?: kinds.firstOrNull(),
                radiusM = settings.radiusM,
                marginM = settings.marginM,
                detail = settings.detail,
                sources = base.map { SourceChoice(it, it.kind == chosenBase) } + wms.map { SourceChoice(it.source, true) },
            )
            recompute()
        }
    }

    fun selectAreaKind(kind: OfflineAreaKind) {
        if (kind !in mutableState.value.areaKinds) return
        mutableState.update { it.copy(areaKind = kind) }
        recompute()
    }

    fun setRadius(metres: Double) {
        mutableState.update { it.copy(radiusM = metres.coerceIn(OfflineLimits.MIN_RADIUS_M, OfflineLimits.MAX_RADIUS_M)) }
        recompute()
    }

    fun setMargin(metres: Double) {
        mutableState.update { it.copy(marginM = metres.coerceIn(OfflineLimits.MIN_MARGIN_M, OfflineLimits.MAX_MARGIN_M)) }
        recompute()
    }

    fun setDetail(level: DetailLevel) {
        mutableState.update { it.copy(detail = level) }
        recompute()
    }

    fun toggleSource(key: String) {
        mutableState.update { state ->
            state.copy(sources = state.sources.map { if (it.source.key == key) it.copy(selected = !it.selected) else it })
        }
        recompute()
    }

    fun setName(name: String) {
        mutableState.update { it.copy(name = name) }
    }

    /** El usuario confirma una de las confirmaciones pendientes (RF-10, RF-14). */
    fun confirm(confirmation: EstimateConfirmation) {
        mutableState.update { it.copy(acknowledged = it.acknowledged + confirmation) }
    }

    /** Empieza la descarga si nada lo impide y no queda nada por confirmar; lo elegido se recuerda para la próxima vez (RF-8). */
    fun start() {
        viewModelScope.launch {
            recompute() // el espacio y la red pueden haber cambiado desde que se abrió la hoja
            val current = mutableState.value
            val data = loaded ?: return@launch
            val estimate = current.estimate ?: return@launch
            if (!current.canStart || current.pendingConfirmations.isNotEmpty()) return@launch
            val area = areaOf(data, current) ?: return@launch
            val box = AreaBounds.of(area)
            val maxZoom = DetailZoom.maxMapZoom(current.detail, (box.south + box.north) / 2)
            val plan = DownloadPlan(
                zoneId = clock.newId(),
                projectId = data.projectId,
                name = current.name.ifBlank { "${current.projectName} – ${date()}" },
                area = area,
                detail = current.detail,
                box = box,
                maxMapZoom = maxZoom,
                sources = estimate.sources.map { planned ->
                    val template = data.wms.firstOrNull { it.source.key == planned.source.key }?.template
                    PlannedSource(
                        source = planned.source,
                        styleUrl = RegionStyles.styleUrl(planned.source, template),
                        minMapZoom = DetailZoom.minMapZoom(planned.source, maxZoom),
                        tiles = planned.tiles,
                        estimatedBytes = planned.estimatedBytes,
                    )
                },
            )
            if (downloader.start(plan)) {
                preferences.save(
                    preferences.settings.first().copy(
                        detail = current.detail,
                        areaKind = checkNotNull(current.areaKind),
                        radiusM = current.radiusM,
                        marginM = current.marginM,
                    ),
                )
                mutableState.update { it.copy(started = true) }
            }
        }
    }

    private fun recompute() {
        val data = loaded ?: return
        val storageNow = StorageState(storage.freeBytes(), data.usedBytes, preferencesLimit)
        val networkNow = network.kind()
        mutableState.update { state ->
            val area = areaOf(data, state)
            val estimate = area?.let {
                val box = AreaBounds.of(it)
                val selected = state.sources.filter { choice -> choice.selected }.map { choice -> choice.source }
                DownloadEstimate.of(
                    box,
                    DetailZoom.maxMapZoom(state.detail, (box.south + box.north) / 2),
                    selected,
                    data.wms.mapNotNull { choice -> choice.ownServiceName?.let { name -> choice.source.serviceId!! to name } }.toMap(),
                    data.wms.associate { choice -> choice.source.key to choice.format },
                    storageNow,
                    networkNow,
                )
            }
            state.copy(estimate = estimate, storage = storageNow, network = networkNow)
        }
    }

    /** El límite de almacenamiento que se aplicó al abrir; se guarda aparte de lo elegido en la hoja. */
    private var preferencesLimit: Long = OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES

    private fun areaOf(data: Loaded, state: DownloadPlanUiState): OfflineArea? = runCatching {
        when (state.areaKind) {
            OfflineAreaKind.RADIUS -> data.center?.let { OfflineArea.Radius(it.latitudeDeg, it.longitudeDeg, state.radiusM) }
            OfflineAreaKind.VIEWPORT -> data.visible?.let { OfflineArea.Viewport(it.south, it.west, it.north, it.east) }
            OfflineAreaKind.PROJECT -> data.points.takeIf { it.isNotEmpty() }?.let { points ->
                OfflineArea.ProjectExtent(
                    points.minOf { it.latitudeDeg },
                    points.minOf { it.longitudeDeg },
                    points.maxOf { it.latitudeDeg },
                    points.maxOf { it.longitudeDeg },
                    state.marginM,
                )
            }
            null -> null
        }
    }.getOrNull()

    /** La fecha de hoy en la zona horaria del móvil, como `14/11/2026`; sin `LocalDate.ofInstant`, que pide la API 34. */
    private fun date(): String = Instant.ofEpochMilli(clock.nowMs()).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT))
}
