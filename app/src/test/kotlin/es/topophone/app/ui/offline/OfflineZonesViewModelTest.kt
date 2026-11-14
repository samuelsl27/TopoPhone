package es.topophone.app.ui.offline

import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.OfflineLimits
import es.topophone.core.model.offline.OfflineSettings
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.ZoneMeta
import es.topophone.core.model.offline.ZoneState
import es.topophone.data.offline.OfflinePreferences
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Las zonas del proyecto: la lista con su estado, renombrar, actualizar, borrar y el límite de almacenamiento (S037/RF-24 a RF-27, RF-31). */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineZonesViewModelTest {

    private class MemoryPreferences : OfflinePreferences {
        val current = MutableStateFlow(OfflineSettings())
        override val settings: Flow<OfflineSettings> = current

        override suspend fun save(settings: OfflineSettings) {
            current.value = settings
        }
    }

    private class RecordingLog : AppLog {
        val texts = mutableListOf<String>()

        override fun record(level: LogLevel, category: LogCategory, message: String) {
            texts += message
        }
    }

    private val mb = 1024L * 1024
    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val ign = DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN")
    private val definition = RegionDefinition("https://estilo", GeoBox(40.39, -3.71, 40.41, -3.69), 7.0, 17.0)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private fun meta(
        zone: String,
        project: String,
        source: DownloadableSource,
        name: String,
        at: Long? = null,
        detail: DetailLevel = DetailLevel.HIGH,
    ) = ZoneMeta(zone, project, name, source, OfflineArea.Radius(40.4, -3.7, 500.0), detail, 1_000, 1_000_000, at, 0)

    /** Una zona con una región por fuente, cada una con su fecha de descarga y su tamaño. */
    private suspend fun FakeOfflineRegionStore.zone(
        zone: String,
        project: String,
        name: String,
        vararg sources: Triple<DownloadableSource, Long?, Long>,
    ): List<RegionId> = sources.map { (source, at, bytes) ->
        create(definition, meta(zone, project, source, name, at)).also { progress(it, completed = 10, required = 20, bytes = bytes) }
    }

    private class Setup(
        val store: FakeOfflineRegionStore,
        val downloader: OfflineDownloader,
        val preferences: MemoryPreferences,
        val log: RecordingLog,
        val vm: OfflineZonesViewModel,
    )

    private fun TestScope.setup(store: FakeOfflineRegionStore = FakeOfflineRegionStore()): Setup {
        val log = RecordingLog()
        val downloader = OfflineDownloader(store, backgroundScope, clockMs = { testScheduler.currentTime }, log = log)
        val preferences = MemoryPreferences()
        return Setup(store, downloader, preferences, log, OfflineZonesViewModel(store, downloader, preferences, log))
    }

    @Test
    fun `S037 RF-24 lista las zonas del proyecto con su nombre, fuentes, nivel, tamano real, fecha y estado`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "Obra norte", Triple(pnoa, 5_000L, 30 * mb), Triple(ign, 9_000L, 12 * mb))
        val s = setup(store)

        s.vm.open("p1")
        runCurrent()

        val zone = s.vm.state.value.zones.single()
        zone.zoneId shouldBe "z1"
        zone.name shouldBe "Obra norte"
        zone.sources shouldBe listOf(pnoa, ign)
        zone.detail shouldBe DetailLevel.HIGH
        zone.sizeBytes shouldBe 42 * mb
        zone.downloadedAtMs shouldBe 5_000L
        zone.state shouldBe ZoneState.COMPLETE
    }

    @Test
    fun `S037 RF-24 una zona con una fuente sin descargar es Incompleta con su porcentaje y sin fecha`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "A medias", Triple(pnoa, 5_000L, 30 * mb), Triple(ign, null, 2 * mb))
        val s = setup(store)

        s.vm.open("p1")
        runCurrent()

        val zone = s.vm.state.value.zones.single()
        zone.state shouldBe ZoneState.INCOMPLETE
        zone.downloadedAtMs shouldBe null
        zone.fraction shouldBe 0.5f
    }

    @Test
    fun `S037 RF-24 la ocupacion total cuenta todas las zonas y la lista solo las del proyecto`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "Mia", Triple(pnoa, 1L, 10 * mb))
        store.zone("z2", "p2", "De otro proyecto", Triple(pnoa, 1L, 25 * mb))
        store.addForeign(definition).also { store.progress(it, 1, 2, bytes = 999 * mb) }
        val s = setup(store)

        s.vm.open("p1")
        runCurrent()

        s.vm.state.value.zones.map { it.name } shouldBe listOf("Mia")
        s.vm.state.value.totalBytes shouldBe 35 * mb
    }

    @Test
    fun `S037 RF-24 las zonas salen de la mas reciente a la mas antigua`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "Vieja", Triple(pnoa, 1L, mb))
        store.zone("z2", "p1", "Nueva", Triple(pnoa, 2L, mb))
        val s = setup(store)

        s.vm.open("p1")
        runCurrent()

        s.vm.state.value.zones.map { it.name } shouldBe listOf("Nueva", "Vieja")
    }

    @Test
    fun `S037 RF-31 el limite sale de los ajustes y cambiarlo se acota y se guarda`() = runTest {
        val s = setup()
        s.vm.open("p1")
        runCurrent()
        s.vm.state.value.limitBytes shouldBe OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES

        s.vm.setLimit(5L * 1024 * mb)
        runCurrent()
        s.vm.state.value.limitBytes shouldBe 5L * 1024 * mb
        s.vm.setLimit(1L)
        runCurrent()

        s.vm.state.value.limitBytes shouldBe OfflineLimits.MIN_STORAGE_LIMIT_BYTES
        s.preferences.current.value.storageLimitBytes shouldBe OfflineLimits.MIN_STORAGE_LIMIT_BYTES
    }

    @Test
    fun `S037 RF-25 renombrar una zona cambia el nombre en todas sus regiones y en la lista`() = runTest {
        val store = FakeOfflineRegionStore()
        val ids = store.zone("z1", "p1", "Antes", Triple(pnoa, 1L, mb), Triple(ign, 1L, mb))
        val s = setup(store)
        s.vm.open("p1")
        runCurrent()

        s.vm.rename("z1", "  Despues  ")
        runCurrent()

        ids.forEach { store.meta(it)!!.name shouldBe "Despues" }
        s.vm.state.value.zones.single().name shouldBe "Despues"
    }

    @Test
    fun `S037 RF-25 un nombre en blanco no cambia nada`() = runTest {
        val store = FakeOfflineRegionStore()
        val ids = store.zone("z1", "p1", "Antes", Triple(pnoa, 1L, mb))
        val s = setup(store)
        s.vm.open("p1")
        runCurrent()

        s.vm.rename("z1", "   ")
        runCurrent()

        store.meta(ids[0])!!.name shouldBe "Antes"
    }

    @Test
    fun `S037 RF-26 actualizar pide confirmacion y al confirmar invalida las regiones sin descargar nada`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "Obra", Triple(pnoa, 1L, mb))
        val s = setup(store)
        s.vm.open("p1")
        runCurrent()

        s.vm.requestRenew("z1")
        runCurrent()

        s.vm.state.value.pendingRenew?.zoneId shouldBe "z1"
        store.calls shouldNotContain "invalidate:1"

        s.vm.confirmRenew()
        runCurrent()

        store.calls shouldContain "invalidate:1"
        store.calls shouldNotContain "setActive:1:true"
        s.vm.state.value.pendingRenew shouldBe null
        s.vm.state.value.zones.single().state shouldBe ZoneState.COMPLETE
        s.vm.state.value.download.phase shouldBe DownloadPhase.IDLE
    }

    @Test
    fun `S037 RF-26 cancelar la confirmacion de actualizar no invalida nada`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "Obra", Triple(pnoa, 1L, mb))
        val s = setup(store)
        s.vm.open("p1")
        runCurrent()
        s.vm.requestRenew("z1")

        s.vm.dismissRenew()
        s.vm.confirmRenew()
        runCurrent()

        s.vm.state.value.pendingRenew shouldBe null
        store.calls shouldNotContain "invalidate:1"
    }

    @Test
    fun `S037 RF-26 pedir actualizar una zona que no esta en la lista no abre ninguna confirmacion`() = runTest {
        val s = setup(FakeOfflineRegionStore())
        s.vm.open("p1")
        runCurrent()

        s.vm.requestRenew("z9")

        s.vm.state.value.pendingRenew shouldBe null
    }

    @Test
    fun `S037 RF-18 reanudar una zona incompleta sigue la descarga`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "Obra", Triple(pnoa, null, mb))
        val s = setup(store)
        s.vm.open("p1")
        runCurrent()

        s.vm.resume("z1")
        runCurrent()

        store.calls shouldContain "setActive:1:true"
        s.vm.state.value.download.phase shouldBe DownloadPhase.RUNNING
    }

    @Test
    fun `S037 RF-27 borrar pide confirmacion y solo al confirmar quita las regiones y libera el espacio de la lista`() = runTest {
        val store = FakeOfflineRegionStore()
        store.zone("z1", "p1", "Obra", Triple(pnoa, 1L, 30 * mb), Triple(ign, 1L, 12 * mb))
        store.zone("z2", "p1", "Otra", Triple(pnoa, 1L, 8 * mb))
        val s = setup(store)
        s.vm.open("p1")
        runCurrent()
        s.vm.state.value.totalBytes shouldBe 50 * mb

        s.vm.requestDelete("z1")
        s.vm.state.value.pendingDelete?.zoneId shouldBe "z1"
        s.vm.dismissDelete()
        s.vm.state.value.pendingDelete shouldBe null
        store.ids.size shouldBe 3

        s.vm.requestDelete("z1")
        s.vm.confirmDelete()
        runCurrent()

        store.ids.size shouldBe 1
        s.vm.state.value.zones.map { it.name } shouldBe listOf("Otra")
        s.vm.state.value.totalBytes shouldBe 8 * mb
        s.vm.state.value.pendingDelete shouldBe null
        s.log.texts shouldContain "Zona sin conexión «Obra»: borrada"
    }

    @Test
    fun `S037 RF-27 borrar la zona que se esta descargando cancela la descarga y la borra`() = runTest {
        val store = FakeOfflineRegionStore()
        val s = setup(store)
        s.downloader.start(
            DownloadPlan(
                "z9",
                "p1",
                "En curso",
                OfflineArea.Radius(40.4, -3.7, 500.0),
                DetailLevel.HIGH,
                definition.box,
                17.0,
                listOf(PlannedSource(pnoa, "https://estilo", 0.0, 1_000, 1_000_000)),
            ),
        )
        s.vm.open("p1")
        runCurrent()

        s.vm.requestDelete("z9")
        s.vm.confirmDelete()
        runCurrent()

        store.ids.size shouldBe 0
        s.vm.state.value.zones.size shouldBe 0
        s.vm.state.value.download.phase shouldBe DownloadPhase.IDLE
        s.log.texts shouldContain "Zona sin conexión «En curso»: cancelada, se borra lo descargado"
    }

    @Test
    fun `S037 RF-24 al terminar una descarga la zona aparece completa sin recargar la pantalla`() = runTest {
        val store = FakeOfflineRegionStore()
        val s = setup(store)
        s.vm.open("p1")
        runCurrent()
        s.vm.state.value.zones.size shouldBe 0

        s.downloader.start(
            DownloadPlan(
                "z1",
                "p1",
                "Nueva",
                OfflineArea.Radius(40.4, -3.7, 500.0),
                DetailLevel.HIGH,
                definition.box,
                17.0,
                listOf(PlannedSource(pnoa, "https://estilo", 0.0, 1_000, 1_000_000)),
            ),
        )
        runCurrent()
        store.progress(store.ids[0], completed = 1_001, required = 1_001, tiles = 1_000, bytes = 20 * mb)
        store.finish(store.ids[0])
        runCurrent()

        s.vm.state.value.zones.single().let {
            it.name shouldBe "Nueva"
            it.state shouldBe ZoneState.COMPLETE
            it.sizeBytes shouldBe 20 * mb
        }
    }
}
