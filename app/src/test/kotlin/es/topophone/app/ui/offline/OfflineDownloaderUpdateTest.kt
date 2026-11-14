package es.topophone.app.ui.offline

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.SourceState
import es.topophone.core.model.offline.ZoneState
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Actualizar una zona y lo que se anota en el log de la app (S037/RF-26, RF-30). */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineDownloaderUpdateTest {

    private class RecordingLog : AppLog {
        val lines = mutableListOf<Pair<LogLevel, String>>()

        override fun record(level: LogLevel, category: LogCategory, message: String) {
            check(category == LogCategory.APP)
            lines += level to message
        }

        val texts: List<String> get() = lines.map { it.second }
    }

    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val ign = DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN")

    private fun planned(source: DownloadableSource, tiles: Long = 1_000) =
        PlannedSource(source, "https://estilo/${source.key}", 0.0, tiles, tiles * 1_000)

    private fun plan(vararg sources: PlannedSource) = DownloadPlan(
        zoneId = "z1",
        projectId = "p1",
        name = "Obra",
        area = OfflineArea.Radius(40.4, -3.7, 500.0),
        detail = DetailLevel.HIGH,
        box = GeoBox(40.39, -3.71, 40.41, -3.69),
        maxMapZoom = 17.0,
        sources = sources.toList(),
    )

    private fun TestScope.downloader(store: FakeOfflineRegionStore, log: AppLog = AppLog.NONE) =
        OfflineDownloader(store, backgroundScope, clockMs = { currentTime }, log = log)

    /** Una zona descargada entera con las dos fuentes, a las 0 h. */
    private suspend fun TestScope.downloadedZone(store: FakeOfflineRegionStore, log: AppLog = AppLog.NONE): OfflineDownloader {
        val downloader = downloader(store, log)
        downloader.start(plan(planned(pnoa), planned(ign)))
        runCurrent()
        store.finish(store.ids[0])
        runCurrent()
        store.finish(store.ids[1])
        runCurrent()
        downloader.state.value.phase shouldBe DownloadPhase.FINISHED
        return downloader
    }

    @Test
    fun `S037 RF-26 actualizar invalida cada region y vuelve a bajar todas las fuentes con la zona Actualizando`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloadedZone(store)
        advanceTimeBy(10_000)

        downloader.update("z1") shouldBe true
        runCurrent()

        store.calls.filter { it.startsWith("invalidate") } shouldBe listOf("invalidate:1", "invalidate:2")
        store.calls.indexOf("invalidate:2") shouldBe store.calls.indexOf("invalidate:1") + 1
        downloader.state.value.zoneState shouldBe ZoneState.UPDATING
        downloader.state.value.sources.map { it.state } shouldBe listOf(SourceState.DOWNLOADING, SourceState.PENDING)

        store.finish(store.ids[0])
        runCurrent()
        store.finish(store.ids[1])
        runCurrent()

        downloader.state.value.phase shouldBe DownloadPhase.FINISHED
        downloader.state.value.zoneState shouldBe ZoneState.COMPLETE
        store.meta(store.ids[0])!!.downloadedAtMs shouldBe 10_000
        store.meta(store.ids[1])!!.downloadedAtMs shouldBe 10_000
    }

    @Test
    fun `S037 RF-26 si la actualizacion se pausa la zona conserva su fecha anterior`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloadedZone(store)
        val before = store.meta(store.ids[0])!!.downloadedAtMs
        advanceTimeBy(10_000)
        downloader.update("z1")
        runCurrent()

        downloader.pause()

        store.meta(store.ids[0])!!.downloadedAtMs shouldBe before
        store.meta(store.ids[1])!!.downloadedAtMs shouldBe before
    }

    @Test
    fun `S037 RF-26 si una fuente no llega a actualizarse, conserva su fecha anterior`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloadedZone(store)
        val before = store.meta(store.ids[0])!!.downloadedAtMs
        advanceTimeBy(10_000)
        downloader.update("z1")
        runCurrent()
        store.progress(store.ids[0], completed = 500, required = 1_001, tiles = 499)
        store.fail(store.ids[0], 5)
        runCurrent()

        advanceTimeBy(32_000)

        downloader.state.value.sources[0].state shouldBe SourceState.INCOMPLETE
        store.meta(store.ids[0])!!.downloadedAtMs shouldBe before
    }

    @Test
    fun `S037 RF-26 no se actualiza una zona que no existe ni con otra descarga en marcha`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.update("otra") shouldBe false

        downloader.start(plan(planned(pnoa)))
        runCurrent()

        downloader.update("z1") shouldBe false
    }

    @Test
    fun `S037 RF-30 cada fuente descargada se anota con su zona, su nombre, sus teselas y su resultado`() = runTest {
        val log = RecordingLog()
        downloadedZone(FakeOfflineRegionStore(), log)

        log.texts shouldContainInOrder listOf(
            "Zona sin conexión «Obra»: Ortofoto PNOA, 1000 teselas, descargada",
            "Zona sin conexión «Obra»: Mapa IGN, 1000 teselas, descargada",
        )
        log.lines.map { it.first }.toSet() shouldBe setOf(LogLevel.INFO)
    }

    @Test
    fun `S037 RF-30 una fuente incompleta se anota como aviso con cuantas teselas faltan`() = runTest {
        val log = RecordingLog()
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store, log)
        downloader.start(plan(planned(pnoa)))
        runCurrent()
        store.progress(store.ids[0], completed = 901, required = 1_001, tiles = 900)
        store.fail(store.ids[0], 9)
        runCurrent()

        advanceTimeBy(32_000)

        log.lines shouldContain (LogLevel.WARNING to "Zona sin conexión «Obra»: Ortofoto PNOA, 1000 teselas, incompleta (faltan 100)")
    }

    @Test
    fun `S037 RF-30 la actualizacion y la cancelacion se anotan`() = runTest {
        val log = RecordingLog()
        val store = FakeOfflineRegionStore()
        val downloader = downloadedZone(store, log)
        downloader.update("z1")
        runCurrent()
        store.finish(store.ids[0])
        runCurrent()
        store.finish(store.ids[1])
        runCurrent()
        downloader.start(plan(planned(pnoa)).copy(zoneId = "z2", name = "Otra"))
        runCurrent()

        downloader.cancel()

        log.texts shouldContain "Zona sin conexión «Obra»: actualización iniciada"
        log.texts shouldContain "Zona sin conexión «Obra»: Ortofoto PNOA, 1000 teselas, actualizada"
        log.texts shouldContain "Zona sin conexión «Otra»: cancelada, se borra lo descargado"
    }

    @Test
    fun `S037 RF-30 ninguna anotacion lleva coordenadas`() = runTest {
        val log = RecordingLog()
        downloadedZone(FakeOfflineRegionStore(), log)

        log.texts.forEach { text ->
            text.contains("40.4") shouldBe false
            text.contains("-3.7") shouldBe false
            text shouldContain "Zona sin conexión"
        }
    }
}
