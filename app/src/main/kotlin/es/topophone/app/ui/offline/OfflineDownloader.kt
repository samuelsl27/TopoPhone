package es.topophone.app.ui.offline

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.OfflineLimits
import es.topophone.core.model.offline.SourceState
import es.topophone.core.model.offline.ZoneMeta
import es.topophone.core.model.offline.ZoneState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Una fuente de la zona ya calculada: su estilo, sus teselas exactas y su tamaño estimado (de `DownloadEstimate`). */
data class PlannedSource(
    val source: DownloadableSource,
    val styleUrl: String,
    val minMapZoom: Double,
    val tiles: Long,
    val estimatedBytes: Long,
)

/** Lo que se descarga: la zona con su caja y su zoom máximo y las fuentes elegidas, en el orden en que se bajan (S037/RF-2, RF-8, RF-15). */
data class DownloadPlan(
    val zoneId: String,
    val projectId: String,
    val name: String,
    val area: OfflineArea,
    val detail: DetailLevel,
    val box: GeoBox,
    val maxMapZoom: Double,
    val sources: List<PlannedSource>,
)

enum class DownloadPhase { IDLE, RUNNING, PAUSED, FINISHED }

/** Cómo va una fuente de la zona (S037/RF-15, RF-19); [failedTiles] son las que faltan una vez dada por terminada. */
data class SourceProgress(
    val source: DownloadableSource,
    val state: SourceState,
    val expectedTiles: Long,
    val completedTiles: Long = 0,
    val bytes: Long = 0,
    val failedTiles: Long = 0,
    val fraction: Float = 0f,
)

/** La descarga de la zona que está en curso o acaba de terminar (S037/RF-15). */
data class DownloadState(
    val zoneId: String? = null,
    val name: String = "",
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val sources: List<SourceProgress> = emptyList(),
    val currentSource: DownloadableSource? = null,
) {
    /** De 0 a 1: la media de lo que lleva cada fuente. */
    val fraction: Float get() = if (sources.isEmpty()) 0f else sources.map { it.fraction }.average().toFloat()
    val completedTiles: Long get() = sources.sumOf { it.completedTiles }
    val bytes: Long get() = sources.sumOf { it.bytes }
    val zoneState: ZoneState get() = ZoneState.of(sources.map { it.state }, updating = false)
}

/**
 * La descarga de una zona sin conexión (S037/RF-15 a RF-19, plan A5): una región por fuente, de una en una, con pausa, reanudación y cancelación. MapLibre
 * reintenta sin límite lo que falla (medido en T1), así que es el descargador quien se rinde: si una región con errores no avanza en
 * [stallTimeoutMs], da por perdido lo que falta y lo compara con el 2 % de sus teselas. Se usa desde un solo hilo (el principal).
 */
class OfflineDownloader(
    private val store: OfflineRegionStore,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long,
    private val stallTimeoutMs: Long = OfflineLimits.STALL_TIMEOUT_S * 1_000L,
    private val tickMs: Long = TICK_MS,
) {
    private class Item(val id: RegionId, var meta: ZoneMeta)

    private sealed interface Event {
        data class Progress(val value: RegionProgress) : Event

        data object Tick : Event
    }

    private sealed interface Outcome {
        data class Complete(val failed: Long) : Outcome

        data class Incomplete(val failed: Long) : Outcome
    }

    private val mutableState = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = mutableState.asStateFlow()

    private var job: Job? = null
    private var items: List<Item> = emptyList()
    private var current: Item? = null

    /** Crea las regiones de la zona y empieza a bajarlas; false si ya hay una descarga en marcha. */
    suspend fun start(plan: DownloadPlan): Boolean {
        if (mutableState.value.phase == DownloadPhase.RUNNING) return false
        val created = plan.sources.map { planned ->
            val meta = ZoneMeta(
                zoneId = plan.zoneId,
                projectId = plan.projectId,
                name = plan.name,
                source = planned.source,
                area = plan.area,
                detail = plan.detail,
                tiles = planned.tiles,
                estimatedBytes = planned.estimatedBytes,
                downloadedAtMs = null,
                failed = 0,
            )
            val id = store.create(RegionDefinition(planned.styleUrl, plan.box, planned.minMapZoom, plan.maxMapZoom), meta)
            Item(id, meta)
        }
        begin(plan.zoneId, plan.name, created.map { it to SourceState.PENDING })
        return true
    }

    /**
     * Sigue con una zona que está en la base sin conexión, también tras reiniciar la app: salta las fuentes ya completas y vuelve a empezar las demás, las
     * incompletas incluidas (es el «Reintentar» de RF-19). False si ya hay una descarga en marcha o la zona no tiene regiones.
     */
    suspend fun resume(zoneId: String): Boolean {
        if (mutableState.value.phase == DownloadPhase.RUNNING) return false
        val regions = store.list().filter { it.meta?.zoneId == zoneId }.sortedBy { it.id.value }
        if (regions.isEmpty()) return false
        val resumed = regions.map { region ->
            val meta = checkNotNull(region.meta)
            Item(region.id, meta) to if (meta.downloadedAtMs != null) SourceState.COMPLETE else SourceState.PENDING
        }
        begin(zoneId, resumed.first().first.meta.name, resumed)
        return true
    }

    /** Detiene la descarga sin perder nada: lo ya guardado se conserva y la zona queda incompleta (RF-16, RF-18). */
    suspend fun pause() {
        if (mutableState.value.phase != DownloadPhase.RUNNING) return
        job?.cancelAndJoin()
        job = null
        current?.let { store.setActive(it.id, false) }
        current = null
        mutableState.update { state ->
            state.copy(
                phase = DownloadPhase.PAUSED,
                currentSource = null,
                sources = state.sources.map { if (it.state == SourceState.DOWNLOADING) it.copy(state = SourceState.PAUSED) else it },
            )
        }
    }

    /** La app sale de primer plano o se bloquea la pantalla: la descarga solo avanza con la app abierta (RF-18). */
    suspend fun onAppBackground() = pause()

    /** Detiene la descarga y borra la zona entera, con lo que ya tenía (RF-16). */
    suspend fun cancel() {
        job?.cancelAndJoin()
        job = null
        items.forEach { store.delete(it.id) }
        items = emptyList()
        current = null
        mutableState.value = DownloadState()
    }

    private fun begin(zoneId: String, name: String, initial: List<Pair<Item, SourceState>>) {
        items = initial.map { it.first }
        mutableState.value = DownloadState(
            zoneId = zoneId,
            name = name,
            phase = DownloadPhase.RUNNING,
            sources = initial.map { (item, state) ->
                SourceProgress(item.meta.source, state, item.meta.tiles, fraction = if (state == SourceState.COMPLETE) 1f else 0f)
            },
        )
        job = scope.launch { runAll() }
    }

    private suspend fun runAll() {
        for ((index, item) in items.withIndex()) {
            if (mutableState.value.sources[index].state == SourceState.COMPLETE) continue
            current = item
            val outcome = runSource(index, item)
            settle(index, item, outcome)
        }
        current = null
        mutableState.update { it.copy(phase = DownloadPhase.FINISHED, currentSource = null) }
    }

    private suspend fun runSource(index: Int, item: Item): Outcome {
        change(index) { it.copy(state = SourceState.DOWNLOADING) }
        mutableState.update { it.copy(currentSource = item.meta.source) }
        store.setActive(item.id, true)
        var latest = RegionProgress.EMPTY
        var lastCompleted = -1L
        var lastChangeMs = clockMs()
        val events: Flow<Event> = merge(store.observe(item.id).map { Event.Progress(it) }, ticks())
        return events.transformWhile<Event, Outcome> { event ->
            when (event) {
                is Event.Progress -> {
                    latest = event.value
                    change(index) { it.copy(completedTiles = latest.completedTiles, bytes = latest.bytes, fraction = latest.fraction) }
                    if (latest.completedResources > lastCompleted) {
                        lastCompleted = latest.completedResources
                        lastChangeMs = clockMs()
                    }
                    if (latest.complete) emit(Outcome.Complete(0))
                    !latest.complete
                }
                Event.Tick -> {
                    val stalled = latest.failures > 0 && clockMs() - lastChangeMs >= stallTimeoutMs
                    if (stalled) {
                        store.setActive(item.id, false)
                        emit(lostOutcome(item, latest))
                    }
                    !stalled
                }
            }
        }.firstOrNull() ?: Outcome.Incomplete(item.meta.tiles)
    }

    /** Lo que falta de una región con errores que ya no avanza: teselas que no llegaron y recursos de otra clase (estilo, símbolos, glifos). */
    private fun lostOutcome(item: Item, progress: RegionProgress): Outcome {
        val expected = item.meta.tiles
        if (!progress.precise) return Outcome.Incomplete(expected)
        val missingTiles = (expected - progress.completedTiles).coerceAtLeast(0)
        val missingOther = (progress.requiredResources - progress.completedResources - missingTiles).coerceAtLeast(0)
        val failed = missingTiles + missingOther
        return if (missingOther > 0 ||
            OfflineLimits.exceedsFailureThreshold(missingTiles, expected)
        ) {
            Outcome.Incomplete(failed)
        } else {
            Outcome.Complete(failed)
        }
    }

    private suspend fun settle(index: Int, item: Item, outcome: Outcome) {
        when (outcome) {
            is Outcome.Complete -> {
                item.meta = item.meta.copy(downloadedAtMs = clockMs(), failed = outcome.failed)
                change(index) { it.copy(state = SourceState.COMPLETE, failedTiles = outcome.failed, fraction = 1f) }
            }
            is Outcome.Incomplete -> {
                item.meta = item.meta.copy(failed = outcome.failed)
                change(index) { it.copy(state = SourceState.INCOMPLETE, failedTiles = outcome.failed) }
            }
        }
        store.updateMeta(item.id, item.meta)
    }

    private fun change(index: Int, transform: (SourceProgress) -> SourceProgress) {
        mutableState.update { state ->
            state.copy(sources = state.sources.mapIndexed { i, source -> if (i == index) transform(source) else source })
        }
    }

    private fun ticks(): Flow<Event> = flow {
        while (true) {
            delay(tickMs)
            emit(Event.Tick)
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
