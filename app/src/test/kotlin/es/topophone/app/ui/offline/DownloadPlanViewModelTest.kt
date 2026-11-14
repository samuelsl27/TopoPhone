package es.topophone.app.ui.offline

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
import es.topophone.core.model.wms.ProjectMapLayer
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.data.offline.OfflinePreferences
import es.topophone.data.wms.ProjectLayerView
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** El plan de la descarga de una zona: el área, las fuentes, el nivel, la previsión y lo que impide o pide confirmar (S037/RF-1 a RF-6, RF-8 a RF-14). */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadPlanViewModelTest {

    private class MemoryPreferences(initial: OfflineSettings = OfflineSettings()) : OfflinePreferences {
        val current = MutableStateFlow(initial)
        override val settings: Flow<OfflineSettings> = current

        override suspend fun save(settings: OfflineSettings) {
            current.value = settings
        }
    }

    private val noon = 1_794_657_600_000L // 14/11/2026 12:00 UTC

    private val clock = object : ZoneClock {
        override fun nowMs() = noon

        override fun newId() = "zona-1"
    }

    private val visible = GeoBox(40.4160, -3.7050, 40.4180, -3.7020)
    private val preset = service("preset", "PNOA WMS", presetKey = "ign-pnoa-ma")
    private val own = service("own", "Mi servicio")

    private fun service(id: String, name: String, mode: WmsRequestMode = WmsRequestMode.TILES, presetKey: String? = null) = WmsService(
        id, name, "https://$id.es/wms", "Servicio $id", "1.3.0", "", presetKey, mode,
        listOf(
            "image/png",
            "image/jpeg",
        ),
        listOf("text/plain"), 0L, 0L,
    )

    private fun layer(service: WmsService, name: String, position: Int, code: String? = "EPSG:3857") = ProjectLayerView(
        ProjectMapLayer("${service.id}-$name", "p1", service.id, name, position, true, 100),
        service,
        if (code == null) null else WmsLayerInfo(0, 0, name, "Capa $name", "", true, true, code),
        showService = false,
    )

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private class Setup(
        val store: FakeOfflineRegionStore,
        val downloader: OfflineDownloader,
        val preferences: MemoryPreferences,
        val vm: DownloadPlanViewModel,
    )

    private fun TestScope.setup(
        inputs: FakeZoneInputs = FakeZoneInputs(),
        preferences: MemoryPreferences = MemoryPreferences(),
        free: Long = 100L * GB,
        network: NetworkKind = NetworkKind.UNMETERED,
        store: FakeOfflineRegionStore = FakeOfflineRegionStore(),
    ): Setup {
        val downloader = OfflineDownloader(store, backgroundScope, clockMs = { 0L })
        val vm = DownloadPlanViewModel(inputs, preferences, store, FakeStorageInfo(free), FakeNetworkInfo(network), downloader, clock)
        return Setup(store, downloader, preferences, vm)
    }

    private fun Setup.open(visible: GeoBox? = this@DownloadPlanViewModelTest.visible) {
        vm.open("p1", "Obra", visible)
    }

    @Test
    fun `S037 RF-2 con centro, puntos y encuadre se ofrecen las tres areas y de fabrica sale el radio`() = runTest {
        val s = setup(FakeZoneInputs(points = listOf(CentralPoint(40.41, -3.71), CentralPoint(40.42, -3.70))))

        s.open()
        runCurrent()

        s.vm.state.value.let {
            it.loaded shouldBe true
            it.areaKinds shouldContainExactly listOf(OfflineAreaKind.RADIUS, OfflineAreaKind.VIEWPORT, OfflineAreaKind.PROJECT)
            it.areaKind shouldBe OfflineAreaKind.RADIUS
            it.radiusM shouldBe 500.0
            it.detail shouldBe DetailLevel.NORMAL
        }
    }

    @Test
    fun `S037 RF-5 un proyecto sin centro solo ofrece el encuadre visible`() = runTest {
        val s = setup(FakeZoneInputs(center = null))

        s.open()
        runCurrent()

        s.vm.state.value.areaKinds shouldContainExactly listOf(OfflineAreaKind.VIEWPORT)
        s.vm.state.value.areaKind shouldBe OfflineAreaKind.VIEWPORT
    }

    @Test
    fun `S037 RF-6 un proyecto sin puntos no ofrece todo el proyecto`() = runTest {
        val s = setup(FakeZoneInputs(points = emptyList()))

        s.open()
        runCurrent()

        s.vm.state.value.areaKinds shouldContainExactly listOf(OfflineAreaKind.RADIUS, OfflineAreaKind.VIEWPORT)
    }

    @Test
    fun `S037 RF-8 se marcan de entrada el mapa base elegido y las capas WMS por teselas del proyecto`() = runTest {
        val layers = listOf(
            layer(preset, "OI.OrthoimageCoverage", 0),
            layer(own, "una-imagen", 1).copy(service = service("img", "Imagen", WmsRequestMode.IMAGE)),
            layer(own, "no-disponible", 2, code = null),
            layer(own, "capa", 3),
        )
        val s = setup(FakeZoneInputs(layers = layers, basemap = Basemap.PNOA_ORTHOPHOTO))

        s.open()
        runCurrent()

        val sources = s.vm.state.value.sources
        sources.map { it.source.key }.take(3) shouldContainExactly listOf("ign", "pnoa", "ofm")
        sources.filter { it.selected }.map { it.source.key } shouldContainExactly
            listOf("pnoa", "wms:preset:OI.OrthoimageCoverage", "wms:own:capa")
        sources.map { it.source.key } shouldContainExactly
            listOf("ign", "pnoa", "ofm", "wms:preset:OI.OrthoimageCoverage", "wms:own:capa")
    }

    @Test
    fun `S037 RF-8 el mapa base OpenStreetMap marca OpenFreeMap y ninguno no marca nada del mapa base`() = runTest {
        val osm = setup(FakeZoneInputs(basemap = Basemap.OSM_VECTOR))
        val none = setup(FakeZoneInputs(basemap = Basemap.NONE))

        osm.open()
        none.open()
        runCurrent()

        osm.vm.state.value.sources.filter { it.selected }.map { it.source.kind } shouldContainExactly listOf(SourceKind.OPENFREEMAP)
        none.vm.state.value.sources.filter { it.selected }.shouldBeEmpty()
    }

    @Test
    fun `S037 RF-11 la prevision de teselas y de tamano es la de DownloadEstimate para esa area, ese nivel y esas fuentes`() = runTest {
        val s = setup(FakeZoneInputs(basemap = Basemap.PNOA_ORTHOPHOTO))
        s.open()
        runCurrent()

        s.vm.setRadius(1_000.0)
        s.vm.setDetail(DetailLevel.HIGH)

        val box = AreaBounds.of(OfflineArea.Radius(40.4, -3.7, 1_000.0))
        val expected = DownloadEstimate.of(
            box,
            DetailZoom.maxMapZoom(DetailLevel.HIGH, (box.south + box.north) / 2),
            listOf(DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")),
            emptyMap(),
            emptyMap(),
            StorageState(100L * GB, 0, OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES),
            NetworkKind.UNMETERED,
        )
        s.vm.state.value.estimate!!.let {
            it.totalTiles shouldBe expected.totalTiles
            it.totalBytes shouldBe expected.totalBytes
        }
        s.vm.state.value.storage shouldBe StorageState(100L * GB, 0, OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES)
    }

    @Test
    fun `S037 RF-3 y RF-4 el radio y el margen se acotan a sus limites`() = runTest {
        val s = setup()
        s.open()
        runCurrent()

        s.vm.setRadius(5.0)
        s.vm.state.value.radiusM shouldBe OfflineLimits.MIN_RADIUS_M
        s.vm.setRadius(99_999.0)
        s.vm.state.value.radiusM shouldBe OfflineLimits.MAX_RADIUS_M
        s.vm.setMargin(10.0)
        s.vm.state.value.marginM shouldBe OfflineLimits.MIN_MARGIN_M
    }

    @Test
    fun `S037 RF-2 el encuadre visible y todo el proyecto cambian la caja de la zona`() = runTest {
        val s =
            setup(
                FakeZoneInputs(points = listOf(CentralPoint(40.41, -3.71), CentralPoint(40.42, -3.70)), basemap = Basemap.PNOA_ORTHOPHOTO),
            )
        s.open()
        runCurrent()
        val radius = s.vm.state.value.estimate!!.totalTiles

        s.vm.selectAreaKind(OfflineAreaKind.VIEWPORT)
        val viewport = s.vm.state.value.estimate!!.totalTiles
        s.vm.selectAreaKind(OfflineAreaKind.PROJECT)
        val project = s.vm.state.value.estimate!!.totalTiles

        (viewport < radius) shouldBe true
        (project != radius && project != viewport) shouldBe true
    }

    @Test
    fun `S037 RF-1 el nombre propuesto es el del proyecto y la fecha y se puede cambiar`() = runTest {
        val s = setup()
        s.open()
        runCurrent()

        s.vm.state.value.name shouldBe "Obra – 14/11/2026"
        s.vm.setName("Mi zona")
        s.vm.state.value.name shouldBe "Mi zona"
    }

    @Test
    fun `S037 RF-13 sin red con internet no se puede empezar y no se crea ninguna region`() = runTest {
        val s = setup(network = NetworkKind.NONE, inputs = FakeZoneInputs(basemap = Basemap.PNOA_ORTHOPHOTO))
        s.open()
        runCurrent()

        s.vm.state.value.estimate!!.blocks shouldContainExactly listOf(EstimateBlock.NoInternet)
        s.vm.state.value.canStart shouldBe false
        s.vm.start()
        runCurrent()

        s.store.ids.shouldBeEmpty()
        s.vm.state.value.started shouldBe false
    }

    @Test
    fun `S037 RF-12 con poco espacio libre se bloquea y dice cuanto falta`() = runTest {
        val s = setup(free = 100L * 1024 * 1024, inputs = FakeZoneInputs(basemap = Basemap.PNOA_ORTHOPHOTO))
        s.open()
        runCurrent()

        s.vm.state.value.estimate!!.blocks.single().let { (it is EstimateBlock.NoSpace) shouldBe true }
        s.vm.state.value.canStart shouldBe false
    }

    @Test
    fun `S037 RF-12 lo que ya ocupan los mapas sin conexion cuenta contra el limite y las regiones ajenas no`() = runTest {
        val store = FakeOfflineRegionStore()
        val id = store.create(
            RegionDefinition("https://x", visible, 0.0, 17.0),
            es.topophone.core.model.offline.ZoneMeta(
                "z0",
                "p0",
                "Otra",
                DownloadableSource(SourceKind.PNOA, "PNOA"),
                OfflineArea.Radius(40.4, -3.7, 500.0),
                DetailLevel.NORMAL,
                1,
                1,
                null,
                0,
            ),
        )
        store.progress(id, 5, 10, bytes = 123_456)
        store.progress(store.addForeign(RegionDefinition("https://y", visible, 0.0, 17.0)), 5, 10, bytes = 999_999)
        val s = setup(store = store)

        s.open()
        runCurrent()

        s.vm.state.value.storage!!.usedBytes shouldBe 123_456
    }

    @Test
    fun `S037 RF-10 un servicio WMS propio pide confirmacion y sin ella no empieza`() = runTest {
        val s =
            setup(FakeZoneInputs(layers = listOf(layer(own, "capa", 0), layer(preset, "OI.OrthoimageCoverage", 1)), basemap = Basemap.NONE))
        s.open()
        runCurrent()

        s.vm.state.value.pendingConfirmations shouldContainExactly listOf(EstimateConfirmation.OwnWms("Mi servicio"))
        s.vm.start()
        runCurrent()
        s.store.ids.shouldBeEmpty()
        s.vm.state.value.started shouldBe false

        s.vm.confirm(EstimateConfirmation.OwnWms("Mi servicio"))
        s.vm.start()
        runCurrent()

        s.vm.state.value.started shouldBe true
        s.store.ids.size shouldBe 2
    }

    @Test
    fun `S037 RF-15 empezar crea el plan con las fuentes elegidas, sus estilos y sus zooms y lo guarda para la proxima vez`() = runTest {
        val s = setup(FakeZoneInputs(layers = listOf(layer(preset, "OI.OrthoimageCoverage", 0)), basemap = Basemap.PNOA_ORTHOPHOTO))
        s.open()
        runCurrent()
        s.vm.setRadius(750.0)
        s.vm.setDetail(DetailLevel.HIGH)

        s.vm.start()
        runCurrent()

        s.vm.state.value.started shouldBe true
        s.store.ids.size shouldBe 2
        val pnoa = s.store.meta(s.store.ids[0])!!
        pnoa.zoneId shouldBe "zona-1"
        pnoa.projectId shouldBe "p1"
        pnoa.name shouldBe "Obra – 14/11/2026"
        pnoa.source.kind shouldBe SourceKind.PNOA
        pnoa.detail shouldBe DetailLevel.HIGH
        (pnoa.area as OfflineArea.Radius).radiusM shouldBe 750.0
        s.store.list()[0].definition.styleUrl.startsWith("https://topophone.invalid/offline/style") shouldBe true
        s.store.list()[0].definition.let { it.minMapZoom shouldBe ((it.maxMapZoom - 10.0) plusOrMinus 1e-9) }
        s.store.meta(s.store.ids[1])!!.source.key shouldBe "wms:preset:OI.OrthoimageCoverage"
        s.preferences.current.value.let {
            it.radiusM shouldBe 750.0
            it.detail shouldBe DetailLevel.HIGH
            it.areaKind shouldBe OfflineAreaKind.RADIUS
        }
    }

    @Test
    fun `S037 RF-8 lo ultimo que se eligio se recuerda al abrir otra vez`() = runTest {
        val preferences =
            MemoryPreferences(
                OfflineSettings(detail = DetailLevel.MAX, areaKind = OfflineAreaKind.VIEWPORT, radiusM = 3_000.0, marginM = 900.0),
            )
        val s = setup(preferences = preferences)

        s.open()
        runCurrent()

        s.vm.state.value.let {
            it.detail shouldBe DetailLevel.MAX
            it.areaKind shouldBe OfflineAreaKind.VIEWPORT
            it.radiusM shouldBe 3_000.0
            it.marginM shouldBe 900.0
        }
        preferences.settings.first().storageLimitBytes shouldBe OfflineLimits.DEFAULT_STORAGE_LIMIT_BYTES
    }

    @Test
    fun `S037 RF-8 una area recordada que este proyecto no ofrece pasa a la primera que ofrece`() = runTest {
        val s = setup(FakeZoneInputs(points = emptyList()), MemoryPreferences(OfflineSettings(areaKind = OfflineAreaKind.PROJECT)))

        s.open()
        runCurrent()

        s.vm.state.value.areaKind shouldBe OfflineAreaKind.RADIUS
    }

    @Test
    fun `S037 RF-8 marcar y desmarcar fuentes cambia la prevision`() = runTest {
        val s = setup(FakeZoneInputs(basemap = Basemap.PNOA_ORTHOPHOTO))
        s.open()
        runCurrent()
        s.vm.state.value.estimate!!.sources.size shouldBe 1

        s.vm.toggleSource("pnoa")
        s.vm.state.value.estimate!!.sources.size shouldBe 0
        s.vm.toggleSource("ign")
        s.vm.state.value.estimate!!.sources.map { it.source.key } shouldContainExactly listOf("ign")
    }

    private companion object {
        const val GB = 1024L * 1024 * 1024
    }
}
