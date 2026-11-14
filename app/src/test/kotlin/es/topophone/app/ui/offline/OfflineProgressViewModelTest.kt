package es.topophone.app.ui.offline

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.SourceKind
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Pausar, reanudar y cancelar la descarga de la zona desde su pantalla (S037/RF-15, RF-16). */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineProgressViewModelTest {

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val plan = DownloadPlan(
        zoneId = "z1",
        projectId = "p1",
        name = "Zona",
        area = OfflineArea.Radius(40.4, -3.7, 500.0),
        detail = DetailLevel.HIGH,
        box = GeoBox(40.39, -3.71, 40.41, -3.69),
        maxMapZoom = 17.0,
        sources = listOf(PlannedSource(pnoa, "https://estilo/pnoa", 0.0, 1_000, 1_000_000)),
    )

    @Test
    fun `S037 RF-15 la pantalla ve el estado de la descarga que lleva el descargador`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = OfflineDownloader(store, backgroundScope, clockMs = { 0L })
        val vm = OfflineProgressViewModel(downloader)

        downloader.start(plan)
        runCurrent()
        store.progress(store.ids[0], completed = 500, required = 1_001, tiles = 499, bytes = 2_000)
        runCurrent()

        vm.state.value.phase shouldBe DownloadPhase.RUNNING
        vm.state.value.completedTiles shouldBe 499
        vm.state.value.bytes shouldBe 2_000
    }

    @Test
    fun `S037 RF-16 pausar y reanudar llegan al descargador y reanudar sigue con la zona que se estaba bajando`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = OfflineDownloader(store, backgroundScope, clockMs = { 0L })
        val vm = OfflineProgressViewModel(downloader)
        downloader.start(plan)
        runCurrent()

        vm.pause()
        runCurrent()
        vm.state.value.phase shouldBe DownloadPhase.PAUSED

        vm.resume()
        runCurrent()
        vm.state.value.phase shouldBe DownloadPhase.RUNNING
        store.calls.count { it == "setActive:1:true" } shouldBe 2
    }

    @Test
    fun `S037 RF-16 cancelar borra la zona y la pantalla queda sin descarga`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = OfflineDownloader(store, backgroundScope, clockMs = { 0L })
        val vm = OfflineProgressViewModel(downloader)
        downloader.start(plan)
        runCurrent()

        vm.cancel()
        runCurrent()

        store.ids shouldBe emptyList()
        vm.state.value.phase shouldBe DownloadPhase.IDLE
    }

    @Test
    fun `S037 RF-16 reanudar sin ninguna zona no hace nada`() = runTest {
        val store = FakeOfflineRegionStore()
        val vm = OfflineProgressViewModel(OfflineDownloader(store, backgroundScope, clockMs = { 0L }))

        vm.resume()
        runCurrent()

        vm.state.value.phase shouldBe DownloadPhase.IDLE
    }
}
