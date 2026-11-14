package es.topophone.app.ui.offline

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.ZoneMeta
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Las zonas de un proyecto que se borra definitivamente se borran con él; en la papelera se conservan (S037/RF-28). */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineZoneJanitorTest {

    private class RecordingLog : AppLog {
        val texts = mutableListOf<String>()

        override fun record(level: LogLevel, category: LogCategory, message: String) {
            texts += message
        }
    }

    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val ign = DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN")
    private val definition = RegionDefinition("https://estilo", GeoBox(40.39, -3.71, 40.41, -3.69), 7.0, 17.0)

    private fun meta(zone: String, project: String, source: DownloadableSource, name: String) =
        ZoneMeta(zone, project, name, source, OfflineArea.Radius(40.4, -3.7, 500.0), DetailLevel.HIGH, 1_000, 1_000_000, 1L, 0)

    private suspend fun FakeOfflineRegionStore.zone(zone: String, project: String, name: String, vararg sources: DownloadableSource) =
        sources.map { create(definition, meta(zone, project, it, name)) }

    @Test
    fun `S037 RF-28 al abrir la app se borran las zonas de los proyectos que ya no existen y se conservan las demas`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "vivo", "De un proyecto vivo", pnoa, ign)
        val gone = store.zone("z2", "borrado", "De uno borrado", pnoa)
        val foreign = store.addForeign(definition)
        val log = RecordingLog()
        val ids = MutableStateFlow(setOf("vivo"))

        OfflineZoneJanitor(store, { ids }, log).start(backgroundScope)
        runCurrent()

        store.ids.size shouldBe 3
        gone.forEach { id -> (id in store.ids) shouldBe false }
        (foreign in store.ids) shouldBe true
        log.texts shouldContain "Zona sin conexión «De uno borrado»: borrada"
    }

    @Test
    fun `S037 RF-28 un proyecto en la papelera conserva sus zonas y al borrarlo definitivamente se borran`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "Zona", pnoa)
        val ids = MutableStateFlow(setOf("p1"))
        OfflineZoneJanitor(store, { ids }, AppLog.NONE).start(backgroundScope)
        runCurrent()

        // A la papelera: el proyecto sigue existiendo para este fin.
        ids.value = setOf("p1")
        runCurrent()
        store.ids.size shouldBe 1

        // Borrado definitivamente: ya no está en ninguna lista.
        ids.value = emptySet()
        runCurrent()

        store.ids.size shouldBe 0
    }

    @Test
    fun `S037 RF-28 una zona con varias fuentes se borra entera y se anota una sola vez`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "Con dos fuentes", pnoa, ign)
        val log = RecordingLog()

        OfflineZoneJanitor(store, { MutableStateFlow(emptySet()) }, log).start(backgroundScope)
        runCurrent()

        store.ids.size shouldBe 0
        log.texts.count { it.contains("Con dos fuentes") } shouldBe 1
    }

    @Test
    fun `S037 RF-28 sin nada que borrar no se anota nada`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "Zona", pnoa)
        val log = RecordingLog()

        OfflineZoneJanitor(store, { MutableStateFlow(setOf("p1")) }, log).start(backgroundScope)
        runCurrent()

        store.ids.size shouldBe 1
        log.texts shouldBe emptyList()
    }

    @Test
    fun `S037 RF-28 los proyectos que se crean despues no borran nada y barrer a mano da cuantas zonas quito`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "a", "A", pnoa)
        store.zone("z2", "b", "B", pnoa)
        val janitor = OfflineZoneJanitor(store, { MutableStateFlow(setOf("a")) }, AppLog.NONE)

        janitor.sweep(setOf("a", "nuevo")) shouldBe 1

        store.list().map { it.meta?.zoneId } shouldBe listOf("z1")
    }
}
