package es.topophone.app.ui.offline

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.SourceKind
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** La caducidad larga de lo que se descarga solo está puesta mientras hay una descarga en marcha (S037/RF-23). */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDownloaderHttpTest {

    private class RecordingMode : DownloadHttpMode {
        val changes = mutableListOf<Boolean>()

        override fun setLongCache(active: Boolean) {
            changes += active
        }
    }

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

    private fun TestScope.downloader(store: FakeOfflineRegionStore, mode: DownloadHttpMode) =
        OfflineDownloader(store, backgroundScope, clockMs = { currentTime }, http = mode)

    @Test
    fun `S037 RF-23 la caducidad larga se pone al empezar y se quita al terminar`() = runTest {
        val store = FakeOfflineRegionStore()
        val mode = RecordingMode()
        val downloader = downloader(store, mode)
        downloader.start(plan)
        runCurrent()
        mode.changes shouldBe listOf(true)

        store.finish(store.ids[0])
        runCurrent()

        mode.changes shouldBe listOf(true, false)
    }

    @Test
    fun `S037 RF-23 al pausar o cancelar tambien se quita y al reanudar o actualizar se vuelve a poner`() = runTest {
        val store = FakeOfflineRegionStore()
        val mode = RecordingMode()
        val downloader = downloader(store, mode)
        downloader.start(plan)
        runCurrent()

        downloader.pause()
        downloader.resume("z1")
        runCurrent()
        downloader.cancel()

        mode.changes shouldBe listOf(true, false, true, false)
    }

    @Test
    fun `S037 RF-26 actualizar tambien alarga la caducidad mientras dura`() = runTest {
        val store = FakeOfflineRegionStore()
        val mode = RecordingMode()
        val downloader = downloader(store, mode)
        downloader.start(plan)
        runCurrent()
        store.finish(store.ids[0])
        runCurrent()

        downloader.update("z1")
        runCurrent()
        store.finish(store.ids[0])
        runCurrent()

        mode.changes shouldBe listOf(true, false, true, false)
    }
}
