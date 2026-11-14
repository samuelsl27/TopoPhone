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

/** Renovar una zona al «Actualizar» y lo que se anota en el log de la app (S037/RF-26, RF-30). */
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
    fun `S037 RF-26 renovar invalida cada region de la zona sin activar ninguna ni empezar una descarga`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloadedZone(store)
        store.calls.clear()
        advanceTimeBy(10_000)

        downloader.renew("z1") shouldBe true
        runCurrent()

        store.calls shouldBe listOf("invalidate:1", "invalidate:2")
        downloader.state.value.phase shouldBe DownloadPhase.FINISHED
        downloader.state.value.zoneState shouldBe ZoneState.COMPLETE
    }

    @Test
    fun `S037 RF-26 renovar no cambia la fecha de descarga ni deja la zona incompleta`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloadedZone(store)
        val before = store.ids.map { store.meta(it)!!.downloadedAtMs }
        advanceTimeBy(10_000)

        downloader.renew("z1")
        runCurrent()

        store.ids.map { store.meta(it)!!.downloadedAtMs } shouldBe before
        before.all { it != null } shouldBe true
        downloader.state.value.sources.map { it.state } shouldBe listOf(SourceState.COMPLETE, SourceState.COMPLETE)
    }

    @Test
    fun `S037 RF-26 no se renueva una zona que no existe ni con otra descarga en marcha`() = runTest {
        val store = FakeOfflineRegionStore()
        val downloader = downloader(store)
        downloader.renew("otra") shouldBe false

        downloader.start(plan(planned(pnoa)))
        runCurrent()
        store.calls.clear()

        downloader.renew("z1") shouldBe false
        store.calls shouldBe emptyList()
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
    fun `S037 RF-30 renovar y cancelar se anotan`() = runTest {
        val log = RecordingLog()
        val store = FakeOfflineRegionStore()
        val downloader = downloadedZone(store, log)
        downloader.renew("z1")
        runCurrent()
        downloader.start(plan(planned(pnoa)).copy(zoneId = "z2", name = "Otra"))
        runCurrent()

        downloader.cancel()

        log.texts shouldContain "Zona sin conexión «Obra»: marcada para renovar con conexión"
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
