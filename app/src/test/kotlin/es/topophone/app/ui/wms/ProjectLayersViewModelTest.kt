package es.topophone.app.ui.wms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.WmsLayerSource
import es.topophone.core.formats.wms.WmsCapabilities
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.wms.RoomProjectLayersRepository
import es.topophone.data.wms.RoomWmsLibraryRepository
import es.topophone.data.wms.WmsAddResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Las capas WMS del proyecto abierto: añadir, visibilidad, opacidad, orden, quitar, el tope de 10 y recuperarlas al abrir (S036/RF-18 a RF-28). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectLayersViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val library = RoomWmsLibraryRepository(db)
    private val repository = RoomProjectLayersRepository(db)
    private val projectId = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun service(url: String, title: String, vararg layers: Pair<String, String?>): String = runBlocking {
        val address = (WmsAddress.parse(url) as WmsAddressResult.Valid).address
        val infos = layers.mapIndexed { i, (name, code) -> WmsLayerInfo(i, 0, name, "Capa $name", "", true, true, code) }
        val caps = WmsCapabilities("1.3.0", title, "© $title", listOf("image/png"), listOf("text/plain"), infos)
        library.add(address, caps, WmsRequestMode.TILES, name = title).shouldBeInstanceOf<WmsAddResult.Added>().service.id
    }

    private fun mercator(vararg names: String) = names.map { it to "EPSG:3857" }.toTypedArray()

    private fun viewModel(start: Boolean = true) = ProjectLayersViewModel(repository).also { if (start) it.start(projectId) }

    private fun ProjectLayersViewModel.await(condition: (ProjectLayersUiState) -> Boolean): ProjectLayersUiState =
        runBlocking { withTimeout(AWAIT_MS) { state.first(condition) } }

    private fun ProjectLayersUiState.names() = layers.map { it.layer.layerName }

    private companion object {
        const val AWAIT_MS = 10_000L
    }

    @Test
    fun `S036 RF-18 sin abrir ningun proyecto no hay capas`() {
        val vm = viewModel(start = false)

        vm.state.value shouldBe ProjectLayersUiState()
        vm.state.value.full shouldBe false
    }

    @Test
    fun `S036 RF-19 una capa nueva entra arriba, visible y al 100 por cien, y el mapa la recibe`() {
        val serviceId = service("https://a.es/wms", "A", *mercator("uno", "dos"))
        val vm = viewModel()

        vm.add(serviceId, "uno")
        vm.add(serviceId, "dos")
        val state = vm.await { it.layers.size == 2 }

        state.names() shouldBe listOf("dos", "uno")
        state.layers.map { it.layer.visible to it.layer.opacity } shouldBe listOf(true to 100, true to 100)
        state.plan.layers.size shouldBe 2
        state.plan.layers.last().source.shouldBeInstanceOf<WmsLayerSource.Tiles>()
        state.message shouldBe null
    }

    @Test
    fun `S036 RF-20 ocultar una capa no la quita del proyecto pero el mapa deja de recibirla`() {
        val serviceId = service("https://a.es/wms", "A", *mercator("uno", "dos"))
        val vm = viewModel()
        vm.add(serviceId, "uno")
        vm.add(serviceId, "dos")
        val first = vm.await { it.layers.size == 2 }.layers.first().layer.id

        vm.setVisible(first, false)
        val state = vm.await { s -> s.layers.any { !it.layer.visible } }

        state.layers.size shouldBe 2
        state.plan.layers.size shouldBe 1
        vm.setVisible(first, true)
        vm.await { it.plan.layers.size == 2 }
    }

    @Test
    fun `S036 RF-21 la opacidad va de 5 en 5 y el plan la lleva de 0 a 1`() {
        val serviceId = service("https://a.es/wms", "A", *mercator("uno"))
        val vm = viewModel()
        vm.add(serviceId, "uno")
        val id = vm.await { it.layers.size == 1 }.layers.single().layer.id

        vm.setOpacity(id, 47)
        val state = vm.await { it.layers.single().layer.opacity != 100 }

        state.layers.single().layer.opacity shouldBe 45
        state.plan.layers.single().opacity shouldBe 0.45f
        vm.setOpacity(id, 0)
        vm.await { it.layers.single().layer.opacity == 0 }.plan.layers.single().opacity shouldBe 0f
    }

    @Test
    fun `S036 RF-22 subir y bajar cambia el orden y el mapa pinta la primera de la lista encima`() {
        val serviceId = service("https://a.es/wms", "A", *mercator("a", "b", "c"))
        val vm = viewModel()
        listOf("a", "b", "c").forEach { vm.add(serviceId, it) }
        var state = vm.await { it.layers.size == 3 }
        state.names() shouldBe listOf("c", "b", "a")
        state.plan.layers.map { it.id } shouldBe state.layers.map { it.layer.id }.reversed()

        vm.moveDown(state.layers.first().layer.id)
        state = vm.await { it.names() == listOf("b", "c", "a") }
        vm.moveUp(state.layers.last().layer.id)
        state = vm.await { it.names() == listOf("b", "a", "c") }

        state.plan.layers.map { it.id } shouldBe state.layers.map { it.layer.id }.reversed()
        vm.moveUp(state.layers.first().layer.id) // ya es la primera: no cambia nada
        vm.moveDown(state.layers.last().layer.id) // ya es la ultima
        vm.await { true }.names() shouldBe listOf("b", "a", "c")
    }

    @Test
    fun `S036 RF-22 arrastrar una capa la pone en la posicion elegida`() {
        val serviceId = service("https://a.es/wms", "A", *mercator("a", "b", "c"))
        val vm = viewModel()
        listOf("a", "b", "c").forEach { vm.add(serviceId, it) }
        val last = vm.await { it.layers.size == 3 }.layers.last().layer.id

        vm.move(last, 0)

        vm.await { it.names().first() == "a" }.names() shouldBe listOf("a", "c", "b")
    }

    @Test
    fun `S036 RF-23 quitar una capa la saca del proyecto y no borra el servicio`() {
        val serviceId = service("https://a.es/wms", "A", *mercator("uno", "dos"))
        val vm = viewModel()
        vm.add(serviceId, "uno")
        vm.add(serviceId, "dos")
        val id = vm.await { it.layers.size == 2 }.layers.first().layer.id

        vm.remove(id)

        vm.await { it.layers.size == 1 }.names() shouldBe listOf("uno")
        runBlocking { library.service(serviceId) }?.name shouldBe "A"
        runBlocking { library.layers(serviceId) }.size shouldBe 2
    }

    @Test
    fun `S036 RF-24 las capas, su orden, su visibilidad y su opacidad siguen al volver a abrir el proyecto`() {
        val serviceId = service("https://a.es/wms", "A", *mercator("uno", "dos"))
        val first = viewModel()
        first.add(serviceId, "uno")
        first.add(serviceId, "dos")
        val ids = first.await { it.layers.size == 2 }.layers.map { it.layer.id }
        first.setOpacity(ids[0], 30)
        first.setVisible(ids[1], false)
        first.await { s -> s.layers[0].layer.opacity == 30 && !s.layers[1].layer.visible }

        val reopened = viewModel()

        val state = reopened.await { it.layers.size == 2 }
        state.names() shouldBe listOf("dos", "uno")
        state.layers.map { it.layer.opacity to it.layer.visible } shouldBe listOf(30 to true, 100 to false)
        state.plan.layers.map { it.opacity } shouldBe listOf(0.3f)
    }

    @Test
    fun `S036 RF-26 con 10 capas no se admite la undecima y se dice por que`() {
        val names = (1..11).map { "capa$it" }
        val serviceId = service("https://a.es/wms", "A", *mercator(*names.toTypedArray()))
        val vm = viewModel()
        names.take(10).forEach { vm.add(serviceId, it) }
        vm.await { it.layers.size == 10 }.full shouldBe true

        vm.add(serviceId, "capa11")
        val state = vm.await { it.message != null }

        state.message shouldBe ProjectLayersMessage.LIMIT_REACHED
        state.layers.size shouldBe 10
        vm.dismissMessage()
        vm.await { it.message == null }
    }

    @Test
    fun `S036 RF-26 una capa repetida no se anade y se dice`() {
        val serviceId = service("https://a.es/wms", "A", *mercator("uno"))
        val vm = viewModel()
        vm.add(serviceId, "uno")
        vm.await { it.layers.size == 1 }

        vm.add(serviceId, "uno")

        val state = vm.await { it.message != null }
        state.message shouldBe ProjectLayersMessage.ALREADY_THERE
        state.layers.size shouldBe 1
    }

    @Test
    fun `S036 RF-12 una capa sin Web Mercator no se anade y se dice`() {
        val serviceId = service("https://a.es/wms", "A", "uno" to "EPSG:3857", "utm" to null)
        val vm = viewModel()

        vm.add(serviceId, "utm")

        vm.await { it.message != null }.message shouldBe ProjectLayersMessage.NO_WEB_MERCATOR
        vm.await { true }.layers shouldBe emptyList()
    }

    @Test
    fun `S036 RF-27 una capa que el servicio ya no ofrece sigue en el proyecto como no disponible y el mapa no la recibe`() {
        val serviceId = service("https://a.es/wms", "A", *mercator("uno", "dos"))
        val vm = viewModel()
        vm.add(serviceId, "uno")
        vm.add(serviceId, "dos")
        vm.await { it.layers.size == 2 }

        runBlocking {
            val address = (WmsAddress.parse("https://a.es/wms") as WmsAddressResult.Valid).address
            val caps =
                WmsCapabilities(
                    "1.3.0",
                    "A",
                    "© A",
                    listOf("image/png"),
                    listOf("text/plain"),
                    listOf(WmsLayerInfo(0, 0, "uno", "Capa uno", "", true, true, "EPSG:3857")),
                )
            library.refresh(library.findByAddress(address)!!.id, caps)
        }
        val state = vm.await { s -> s.layers.any { !it.available } }

        state.layers.associate { it.layer.layerName to it.available } shouldBe mapOf("dos" to false, "uno" to true)
        state.plan.layers.size shouldBe 1
    }

    @Test
    fun `S036 RF-28 dos capas con el mismo titulo de servicios distintos llevan el nombre de su servicio`() {
        val a = service("https://a.es/wms", "Servicio A", "ortofoto" to "EPSG:3857")
        val b = service("https://b.es/wms", "Servicio B", "ortofoto" to "EPSG:3857")
        val vm = viewModel()
        vm.add(a, "ortofoto")
        vm.add(b, "ortofoto")

        val state = vm.await { it.layers.size == 2 }

        state.layers.map { it.showService } shouldBe listOf(true, true)
        state.layers.map { it.service.name } shouldBe listOf("Servicio B", "Servicio A")
    }
}
