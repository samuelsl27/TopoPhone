package es.topophone.app.ui.offline

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.SourceState
import es.topophone.core.model.offline.ZoneState
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** La cola de fuentes, las pausas, los errores y los reintentos de la descarga de una zona (S037/RF-15, RF-16, RF-18, RF-19). */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDownloaderTest {

    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val ign = DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN")

    private fun planned(source: DownloadableSource, tiles: Long = 1_000) =
        PlannedSource(source, "https://estilo/${source.key}", 0.0, tiles, tiles * 1_000)

    private fun plan(vararg sources: PlannedSource) = DownloadPlan(
        zoneId = "z1",
        projectId = "p1",
        name = "Zona de prueba",
        area = OfflineArea.Radius(40.4, -3.7, 500.0),
        detail = DetailLevel.HIGH,
        box = GeoBox(40.39, -3.71, 40.41, -3.69),
        maxMapZoom = 17.0,
        sources = sources.toList(),
    )

    private fun TestScope.downloader(store: FakeOfflineRegionStore, scope: CoroutineScope = backgroundScope) =
        OfflineDownloader(store, scope, clockMs = { currentTime })

    @Test
    fun `S037 RF-15 empezar crea una region por fuente con los metadatos de la zona y solo activa la primera`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)

        downloader.start(plan(planned(pnoa), planned(ign))) shouldBe true
        runCurrent()

        store.ids.size shouldBe 2
        store.meta(store.ids[0])!!.let {
            it.zoneId shouldBe "z1"
            it.projectId shouldBe "p1"
            it.source shouldBe pnoa
            it.tiles shouldBe 1_000
            it.estimatedBytes shouldBe 1_000_000
            it.downloadedAtMs shouldBe null
        }
        store.calls shouldContain "setActive:1:true"
        store.calls shouldNotContain "setActive:2:true"
        downloader.state.value.phase shouldBe DownloadPhase.RUNNING
        downloader.state.value.currentSource shouldBe pnoa
    }

    @Test
    fun `S037 RF-15 cada fuente empieza cuando termina la anterior y al acabar la zona esta completa con su fecha`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.start(plan(planned(pnoa), planned(ign)))
        runCurrent()

        store.progress(store.ids[0], completed = 500, required = 1_001, tiles = 499, bytes = 12_000)
        runCurrent()
        downloader.state.value.sources[0].let {
            it.state shouldBe SourceState.DOWNLOADING
            it.completedTiles shouldBe 499
            it.bytes shouldBe 12_000
        }
        advanceTimeBy(5_000)
        store.finish(store.ids[0])
        runCurrent()
        store.meta(store.ids[0])!!.downloadedAtMs shouldBe currentTime
        store.calls shouldContain "setActive:2:true"
        downloader.state.value.currentSource shouldBe ign

        store.progress(store.ids[1], completed = 1_001, required = 1_001, tiles = 1_000)
        store.finish(store.ids[1])
        runCurrent()

        downloader.state.value.phase shouldBe DownloadPhase.FINISHED
        downloader.state.value.zoneState shouldBe ZoneState.COMPLETE
        downloader.state.value.fraction shouldBe 1f
        downloader.state.value.currentSource shouldBe null
    }

    @Test
    fun `S037 RF-16 pausar detiene la region y reanudar sigue con la misma sin crear otra`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.start(plan(planned(pnoa), planned(ign)))
        runCurrent()
        store.progress(store.ids[0], completed = 300, required = 1_001, tiles = 299)
        runCurrent()

        downloader.pause()

        store.calls shouldContain "setActive:1:false"
        downloader.state.value.phase shouldBe DownloadPhase.PAUSED
        downloader.state.value.sources[0].state shouldBe SourceState.PAUSED
        downloader.state.value.zoneState shouldBe ZoneState.INCOMPLETE

        downloader.resume("z1") shouldBe true
        runCurrent()

        store.calls.count { it == "setActive:1:true" } shouldBe 2
        store.calls.count { it.startsWith("create") } shouldBe 2
        downloader.state.value.phase shouldBe DownloadPhase.RUNNING
    }

    @Test
    fun `S037 RF-16 cancelar borra todas las regiones de la zona y deja la descarga libre`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.start(plan(planned(pnoa), planned(ign)))
        runCurrent()

        downloader.cancel()

        store.ids shouldBe emptyList()
        downloader.state.value shouldBe DownloadState()
        downloader.start(plan(planned(pnoa))) shouldBe true
    }

    @Test
    fun `S037 RF-18 pasar la app a segundo plano pausa la descarga y la deja incompleta`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.start(plan(planned(pnoa)))
        runCurrent()

        downloader.onAppBackground()

        downloader.state.value.phase shouldBe DownloadPhase.PAUSED
        store.calls shouldContain "setActive:1:false"
        downloader.onAppBackground() // sin descarga en marcha no hace nada
        store.calls.count { it == "setActive:1:false" } shouldBe 1
    }

    @Test
    fun `S037 RF-19 con errores y sin avance en 30 s una fuente con el 1 por ciento sin llegar se da por completa y sigue la cola`() =
        runTest {
            val store = FakeOfflineRegionStore()
            val downloader = downloader(store)
            downloader.start(plan(planned(pnoa), planned(ign)))
            runCurrent()
            store.progress(store.ids[0], completed = 991, required = 1_001, tiles = 990)
            store.fail(store.ids[0], 5)
            runCurrent()

            advanceTimeBy(29_000)
            store.calls shouldNotContain "setActive:1:false"
            advanceTimeBy(3_000)

            store.calls shouldContain "setActive:1:false"
            store.meta(store.ids[0])!!.let {
                it.failed shouldBe 10
                (it.downloadedAtMs != null) shouldBe true
            }
            downloader.state.value.sources[0].state shouldBe SourceState.COMPLETE
            store.calls shouldContain "setActive:2:true"
        }

    @Test
    fun `S037 RF-19 con mas del 2 por ciento sin llegar la fuente queda incompleta con cuantas faltan y el resto sigue`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.start(plan(planned(pnoa), planned(ign)))
        runCurrent()
        store.progress(store.ids[0], completed = 901, required = 1_001, tiles = 900)
        store.fail(store.ids[0], 40)
        runCurrent()

        advanceTimeBy(32_000)
        store.progress(store.ids[1], completed = 1_001, required = 1_001, tiles = 1_000)
        store.finish(store.ids[1])
        runCurrent()

        downloader.state.value.sources[0].let {
            it.state shouldBe SourceState.INCOMPLETE
            it.failedTiles shouldBe 100
        }
        store.meta(store.ids[0])!!.let {
            it.failed shouldBe 100
            it.downloadedAtMs shouldBe null
        }
        downloader.state.value.phase shouldBe DownloadPhase.FINISHED
        downloader.state.value.zoneState shouldBe ZoneState.INCOMPLETE
    }

    @Test
    fun `S037 RF-19 si falta un recurso que no es una tesela, como el estilo o los glifos, la fuente queda incompleta`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.start(plan(planned(ign)))
        runCurrent()
        store.progress(store.ids[0], completed = 1_000, required = 1_001, tiles = 1_000)
        store.fail(store.ids[0])
        runCurrent()

        advanceTimeBy(32_000)

        downloader.state.value.sources[0].state shouldBe SourceState.INCOMPLETE
    }

    @Test
    fun `S037 RF-19 sin saber siquiera el total y con errores la fuente queda incompleta con todas sus teselas por bajar`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.start(plan(planned(pnoa, tiles = 100)))
        runCurrent()
        store.fail(store.ids[0], 3)
        runCurrent()

        advanceTimeBy(32_000)

        downloader.state.value.sources[0].let {
            it.state shouldBe SourceState.INCOMPLETE
            it.failedTiles shouldBe 100
        }
    }

    @Test
    fun `S037 RF-19 una descarga lenta pero sin errores no se da nunca por perdida`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.start(plan(planned(pnoa)))
        runCurrent()
        store.progress(store.ids[0], completed = 10, required = 1_001, tiles = 9)
        runCurrent()

        advanceTimeBy(120_000)

        downloader.state.value.sources[0].state shouldBe SourceState.DOWNLOADING
        store.calls shouldNotContain "setActive:1:false"
    }

    @Test
    fun `S037 RF-18 tras reiniciar la app reanudar activa la primera fuente sin terminar y respeta las completas`() = runTest {
        val store = FakeOfflineRegionStore()
        val first = downloader(store)
        first.start(plan(planned(pnoa), planned(ign)))
        runCurrent()
        store.finish(store.ids[0])
        runCurrent()
        first.pause()
        val restarted = downloader(store)

        restarted.resume("z1") shouldBe true
        runCurrent()

        restarted.state.value.sources.map { it.state } shouldBe listOf(SourceState.COMPLETE, SourceState.DOWNLOADING)
        store.calls.count { it == "setActive:1:true" } shouldBe 1
        store.calls.count { it == "setActive:2:true" } shouldBe 2
    }

    @Test
    fun `S037 RF-19 reintentar una fuente incompleta es reanudar la zona`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.start(plan(planned(pnoa)))
        runCurrent()
        store.progress(store.ids[0], completed = 500, required = 1_001, tiles = 499)
        store.fail(store.ids[0], 9)
        runCurrent()
        advanceTimeBy(32_000)
        downloader.state.value.sources[0].state shouldBe SourceState.INCOMPLETE

        downloader.resume("z1") shouldBe true
        runCurrent()

        store.calls.count { it == "setActive:1:true" } shouldBe 2
        downloader.state.value.sources[0].state shouldBe SourceState.DOWNLOADING
    }

    @Test
    fun `S037 RF-15 solo hay una descarga a la vez y una zona que no existe no se reanuda`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.start(plan(planned(pnoa))) shouldBe true
        runCurrent()

        downloader.start(plan(planned(ign))) shouldBe false
        store.ids.size shouldBe 1
        downloader.resume("z1") shouldBe false // ya está en marcha
        downloader(FakeOfflineRegionStore()).resume("otra") shouldBe false
    }
}
