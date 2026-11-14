package es.topophone.app.ui.offline

import es.topophone.app.ui.map.MapCamera
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.model.map.Basemap
import es.topophone.core.model.offline.DetailLevel
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.OfflineArea
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.offline.ZoneMeta
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

/** Lo que el mapa sabe de sus zonas sin conexión: si está cubierto y si se acerca más de lo guardado, al día con la cámara y con la descarga (S037/RF-20, RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineCoverageViewModelTest {

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() = Dispatchers.resetMain()

    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val box = GeoBox(40.4100, -3.7100, 40.4240, -3.6970)
    private val inside = MapCamera(40.4170, -3.7035, 15.0)

    private suspend fun FakeOfflineRegionStore.zone(downloadedAt: Long?, maxZoom: Double = 17.0) {
        val meta =
            ZoneMeta("z1", "p1", "Zona", pnoa, OfflineArea.Radius(40.4, -3.7, 500.0), DetailLevel.HIGH, 1_000, 1_000_000, downloadedAt, 0)
        create(RegionDefinition("https://estilo", box, 7.0, maxZoom), meta)
    }

    private fun kotlinx.coroutines.test.TestScope.vm(store: FakeOfflineRegionStore) =
        OfflineCoverageViewModel(store, OfflineDownloader(store, backgroundScope, clockMs = { 0L }))

    @Test
    fun `S037 RF-20 con una zona completa y la camara dentro el mapa esta cubierto`() = runTest {
        val store = FakeOfflineRegionStore().also { it.zone(downloadedAt = 5L) }
        val vm = vm(store)

        vm.open()
        vm.drawing(Basemap.PNOA_ORTHOPHOTO, emptyList())
        vm.onCamera(inside)
        runCurrent()

        vm.state.value shouldBe OfflineCoverageUiState(covered = true, beyondDetail = false)
    }

    @Test
    fun `S037 RF-20 al mover la camara fuera de la zona deja de estar cubierto`() = runTest {
        val store = FakeOfflineRegionStore().also { it.zone(downloadedAt = 5L) }
        val vm = vm(store)
        vm.open()
        vm.drawing(Basemap.PNOA_ORTHOPHOTO, emptyList())
        vm.onCamera(inside)
        runCurrent()

        vm.onCamera(MapCamera(41.9, -3.7, 15.0))

        vm.state.value.covered shouldBe false
    }

    @Test
    fun `S037 RF-21 acercar el mapa por encima de lo guardado dentro de la zona avisa del detalle maximo`() = runTest {
        val store = FakeOfflineRegionStore().also { it.zone(downloadedAt = 5L, maxZoom = 17.0) }
        val vm = vm(store)
        vm.open()
        vm.drawing(Basemap.PNOA_ORTHOPHOTO, emptyList())
        vm.onCamera(inside.copy(zoom = 17.5))
        runCurrent()

        vm.state.value shouldBe OfflineCoverageUiState(covered = true, beyondDetail = true)
    }

    @Test
    fun `S037 RF-20 una zona incompleta no cubre y cambiar de mapa base deja de cubrir`() = runTest {
        val incomplete = FakeOfflineRegionStore().also { it.zone(downloadedAt = null) }
        val vmIncomplete = vm(incomplete)
        vmIncomplete.open()
        vmIncomplete.drawing(Basemap.PNOA_ORTHOPHOTO, emptyList())
        vmIncomplete.onCamera(inside)
        runCurrent()
        vmIncomplete.state.value.covered shouldBe false

        val complete = FakeOfflineRegionStore().also { it.zone(downloadedAt = 5L) }
        val vmComplete = vm(complete)
        vmComplete.open()
        vmComplete.drawing(Basemap.PNOA_ORTHOPHOTO, emptyList())
        vmComplete.onCamera(inside)
        runCurrent()
        vmComplete.state.value.covered shouldBe true

        vmComplete.drawing(Basemap.IGN_VECTOR, emptyList())

        vmComplete.state.value.covered shouldBe false
    }

    @Test
    fun `S037 RF-20 sin camara todavia no hay nada cubierto y borrar la zona lo quita al releer`() = runTest {
        val store = FakeOfflineRegionStore().also { it.zone(downloadedAt = 5L) }
        val vm = vm(store)
        vm.open()
        vm.drawing(Basemap.PNOA_ORTHOPHOTO, emptyList())
        runCurrent()
        vm.state.value.covered shouldBe false

        vm.onCamera(inside)
        runCurrent()
        vm.state.value.covered shouldBe true
        store.delete(store.ids.single())
        vm.reload()
        runCurrent()

        vm.state.value.covered shouldBe false
    }
}
