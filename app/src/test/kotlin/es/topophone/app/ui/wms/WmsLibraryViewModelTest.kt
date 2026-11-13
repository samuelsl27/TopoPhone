package es.topophone.app.ui.wms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.wms.WmsPresets
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.wms.WmsAddressProblem
import es.topophone.core.model.wms.WmsLayerProblem
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.wms.AddLayerResult
import es.topophone.data.wms.MissingProjectLayer
import es.topophone.data.wms.RoomProjectLayersRepository
import es.topophone.data.wms.RoomWmsLibraryRepository
import es.topophone.data.wms.WmsAddProblem
import es.topophone.data.wms.WmsServiceAdder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
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

/** La biblioteca de servicios WMS vista desde su hoja: alta, presets, capas, actualizar, renombrar y borrar (S036/RF-1 a RF-17). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class WmsLibraryViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val library = RoomWmsLibraryRepository(db)
    private val projectLayers = RoomProjectLayersRepository(db)
    private val http = FakeWmsHttp { fixture("caps-1.3.0-basico.xml") }
    private val dispatcher = UnconfinedTestDispatcher()
    private val viewModels = TestViewModels()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
        db.close()
    }

    private fun fixture(name: String) = File("../fixtures/formatos/wms/$name").readBytes()

    private fun viewModel() = viewModels.keep(WmsLibraryViewModel(library, WmsServiceAdder(http, library)))

    private fun WmsLibraryViewModel.await(condition: (WmsLibraryUiState) -> Boolean): WmsLibraryUiState =
        runBlocking { withTimeout(AWAIT_MS) { state.first(condition) } }

    private fun WmsLibraryViewModel.addAndWait(address: String): WmsLibraryUiState {
        onAddressChange(address)
        add()
        return await { it.add !is WmsAddState.Loading }
    }

    private companion object {
        const val AWAIT_MS = 10_000L
    }

    @Test
    fun `S036 RF-1 anadir una direccion guarda el servicio y abre sus capas`() {
        val vm = viewModel()

        val state = vm.addAndWait("ejemplo.es/wms")

        state.add shouldBe WmsAddState.Idle
        state.address shouldBe ""
        val opened = state.opened.shouldNotBeNull()
        opened.notice shouldBe WmsOpenNotice.ADDED
        opened.layers.map { it.info.name } shouldBe listOf("raiz", "rgb", "irg")
        vm.await { it.services.size == 1 }.services.single().id shouldBe opened.service.id
    }

    @Test
    fun `S036 RF-3 el servicio abierto lleva su titulo, su version y su atribucion`() {
        val state = viewModel().addAndWait("https://ejemplo.es/wms")

        val service = state.opened.shouldNotBeNull().service
        service.title shouldBe "Servicio de ejemplo"
        service.version shouldBe "1.3.0"
        service.attribution shouldBe "© Servicio de ejemplo"
    }

    @Test
    fun `S036 RF-4 las capas leidas se ven de nuevo sin pedir nada a la red`() {
        val vm = viewModel()
        val id = vm.addAndWait("https://ejemplo.es/wms").opened.shouldNotBeNull().service.id
        vm.close()
        vm.await { it.opened == null }
        http.urls.clear()
        http.answer = { UnknownHostException("sin red") }

        vm.open(id)

        vm.await { it.opened != null }.opened.shouldNotBeNull().layers.size shouldBe 3
        http.urls shouldBe emptyList()
    }

    @Test
    fun `S036 RF-8 un servicio que no responde no se anade y da su motivo`() {
        val cases = listOf(
            UnknownHostException("sin red") to WmsAddProblem.NO_RESPONSE,
            IOException("rechazada") to WmsAddProblem.NO_RESPONSE,
            fixture("error-html-200.html") to WmsAddProblem.NOT_CAPABILITIES,
            fixture("error-excepcion-1.3.0.xml") to WmsAddProblem.SERVICE_EXCEPTION,
            fixture("error-xml-danado.xml") to WmsAddProblem.MALFORMED,
        )
        cases.forEachIndexed { index, (answer, problem) ->
            http.answer = { answer }
            val vm = viewModel()

            val state = vm.addAndWait("https://ejemplo.es/caso$index")

            state.add.shouldBeInstanceOf<WmsAddState.Error>().failure.shouldBeInstanceOf<WmsFailure.Service>().problem shouldBe problem
            state.opened shouldBe null
            vm.await { true }.services shouldBe emptyList()
        }
    }

    @Test
    fun `S036 RF-8 editar la direccion despues de un error lo quita`() {
        http.answer = { UnknownHostException("sin red") }
        val vm = viewModel()
        vm.addAndWait("https://ejemplo.es/wms").add.shouldBeInstanceOf<WmsAddState.Error>()

        vm.onAddressChange("https://ejemplo.es/otra")

        vm.state.value.add shouldBe WmsAddState.Idle
    }

    @Test
    fun `S036 RF-9 un servicio sin ninguna capa en Web Mercator se rechaza con su motivo`() {
        http.answer = { fixture("caps-1.3.0-sin-3857.xml") }

        val state = viewModel().addAndWait("https://ejemplo.es/wms")

        state.add shouldBe WmsAddState.Error(WmsFailure.Service(WmsAddProblem.NO_WEB_MERCATOR))
    }

    @Test
    fun `S036 RF-10 una direccion HTTP publica o vacia no se pide y da su motivo`() {
        val vm = viewModel()

        vm.addAndWait("http://ovc.catastro.meh.es/wms").add shouldBe WmsAddState.Error(WmsFailure.Address(WmsAddressProblem.PUBLIC_HTTP))
        vm.addAndWait("   ").add shouldBe WmsAddState.Error(WmsFailure.Address(WmsAddressProblem.EMPTY))
        http.urls shouldBe emptyList()
    }

    @Test
    fun `S036 RF-12 las capas que solo agrupan o no tienen 3857 salen con su motivo`() {
        http.answer = { fixture("caps-1.3.0-alias.xml") }

        val layers = viewModel().addAndWait("https://ejemplo.es/alias").opened.shouldNotBeNull().layers

        layers.first().problem shouldBe WmsLayerProblem.GROUP_ONLY
        layers.single { it.info.name == "a-25830" }.problem shouldBe WmsLayerProblem.NO_WEB_MERCATOR
        layers.single { it.info.name == "a-3785" }.problem shouldBe null
    }

    @Test
    fun `S036 RF-13 los presets salen por grupos con su atribucion y su licencia`() {
        val groups = viewModel().state.value.presetGroups

        groups.flatMap { it.items }.size shouldBe WmsPresets.all.size
        groups.map { it.name } shouldBe WmsPresets.all.map { it.group }.distinct()
        groups.flatMap { it.items }.forEach {
            it.preset.attribution.shouldNotBeEmpty()
            it.preset.license.shouldNotBeEmpty()
            it.inLibrary shouldBe false
        }
    }

    @Test
    fun `S036 RF-14 anadir un preset lo guarda con su clave y su modo de peticion`() {
        http.answer = { fixture("presets/catastro-wms/capabilities-1.3.0.xml") }
        val vm = viewModel()

        vm.addPreset("catastro-wms")
        val state = vm.await { it.add == WmsAddState.Idle && it.opened != null }

        val service = state.opened.shouldNotBeNull().service
        service.presetKey shouldBe "catastro-wms"
        service.requestMode shouldBe WmsRequestMode.IMAGE
        service.name shouldBe WmsPresets.byKey("catastro-wms")?.name
    }

    @Test
    fun `S036 RF-15 un preset ya en la biblioteca sale marcado y no se duplica ni se pide otra vez`() {
        http.answer = { fixture("presets/catastro-wms/capabilities-1.3.0.xml") }
        val vm = viewModel()
        vm.addPreset("catastro-wms")
        val id = vm.await { it.opened != null }.opened.shouldNotBeNull().service.id
        vm.close()
        http.urls.clear()

        vm.await { it.presetGroups.flatMap { g -> g.items }.single { i -> i.preset.key == "catastro-wms" }.inLibrary }
        vm.addPreset("catastro-wms")
        val state = vm.await { it.opened?.notice == WmsOpenNotice.ALREADY_THERE }

        state.services.size shouldBe 1
        state.opened.shouldNotBeNull().service.id shouldBe id
        http.urls shouldBe emptyList()
    }

    @Test
    fun `S036 RF-15 un servicio puesto a mano con la direccion de un preset tambien lo marca`() {
        val vm = viewModel()
        val preset = WmsPresets.byKey("ign-base").shouldNotBeNull()

        vm.addAndWait(preset.address)
        val state = vm.await { s -> s.presetGroups.flatMap { it.items }.any { it.preset.key == "ign-base" && it.inLibrary } }

        state.services.size shouldBe 1
    }

    @Test
    fun `S036 RF-15 una direccion ya guardada se abre en lugar de duplicarla`() {
        val vm = viewModel()
        val first = vm.addAndWait("https://ejemplo.es/wms").opened.shouldNotBeNull().service.id
        http.urls.clear()

        val again = vm.addAndWait("HTTPS://EJEMPLO.es/wms")

        again.opened.shouldNotBeNull().notice shouldBe WmsOpenNotice.ALREADY_THERE
        again.opened.shouldNotBeNull().service.id shouldBe first
        http.urls shouldBe emptyList()
        vm.await { it.services.size == 1 }
    }

    @Test
    fun `S036 RF-6 actualizar avisa de las capas de proyectos que han desaparecido`() {
        val vm = viewModel()
        val id = vm.addAndWait("https://ejemplo.es/wms").opened.shouldNotBeNull().service.id
        val project = runBlocking {
            RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        runBlocking { projectLayers.add(project, id, "irg").shouldBeInstanceOf<AddLayerResult.Added>() }
        http.answer = { fixture("caps-1.3.0-nombres-raros.xml") }

        vm.refresh()
        val state = vm.await { it.refresh is WmsRefreshState.Done }

        state.refresh shouldBe WmsRefreshState.Done(listOf(MissingProjectLayer(project, "Obra", "irg")))
        state.opened.shouldNotBeNull().layers.map { it.info.name }.first() shouldBe "OWS_0101_BCV05"
    }

    @Test
    fun `S036 RF-6 actualizar sin red deja el servicio como estaba y lo dice`() {
        val vm = viewModel()
        vm.addAndWait("https://ejemplo.es/wms")
        http.answer = { UnknownHostException("sin red") }

        vm.refresh()
        val state = vm.await { it.refresh is WmsRefreshState.Failed }

        state.refresh shouldBe WmsRefreshState.Failed(WmsFailure.Service(WmsAddProblem.NO_RESPONSE))
        state.opened.shouldNotBeNull().layers.map { it.info.name } shouldBe listOf("raiz", "rgb", "irg")
        vm.dismissRefresh()
        vm.state.value.refresh shouldBe WmsRefreshState.Idle
    }

    @Test
    fun `S036 RF-7 renombrar cambia el nombre y uno en blanco no`() {
        val vm = viewModel()
        vm.addAndWait("https://ejemplo.es/wms")

        vm.rename("  Ortofoto de la obra ")
        vm.await { it.opened?.service?.name == "Ortofoto de la obra" }
        vm.rename("   ")

        vm.await { true }.opened.shouldNotBeNull().service.name shouldBe "Ortofoto de la obra"
        vm.await { true }.services.single().name shouldBe "Ortofoto de la obra"
    }

    @Test
    fun `S036 RF-7 borrar avisa de los proyectos que lo usan y no borra hasta confirmar`() {
        val vm = viewModel()
        val id = vm.addAndWait("https://ejemplo.es/wms").opened.shouldNotBeNull().service.id
        val project = runBlocking {
            RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        runBlocking { projectLayers.add(project, id, "rgb") }

        vm.requestDelete(id)
        val pending = vm.await { it.pendingDelete != null }.pendingDelete.shouldNotBeNull()

        pending.uses.map { it.projectName to it.layers } shouldBe listOf("Obra" to 1)
        vm.cancelDelete()
        vm.state.value.pendingDelete shouldBe null
        vm.state.value.services.size shouldBe 1

        vm.requestDelete(id)
        vm.await { it.pendingDelete != null }
        vm.confirmDelete()
        val state = vm.await { it.services.isEmpty() }

        state.opened shouldBe null
        state.pendingDelete shouldBe null
        runBlocking { projectLayers.layers(project).first() } shouldBe emptyList()
    }

    @Test
    fun `S036 RF-15 al cerrar la hoja se vuelve a la lista sin el servicio, el aviso ni la direccion de antes`() {
        val vm = viewModel()
        vm.addAndWait("https://ejemplo.es/wms").opened.shouldNotBeNull()
        vm.onAddressChange("https://otra.es/wms")

        vm.reset()

        val state = vm.state.value
        state.opened shouldBe null
        state.address shouldBe ""
        state.add shouldBe WmsAddState.Idle
        state.refresh shouldBe WmsRefreshState.Idle
        state.pendingDelete shouldBe null
        vm.await { it.services.size == 1 }
    }

    @Test
    fun `S036 RF-17 un servicio propio empieza por teselas y se puede pasar a una imagen`() {
        val vm = viewModel()
        vm.addAndWait("https://ejemplo.es/wms").opened.shouldNotBeNull().service.requestMode shouldBe WmsRequestMode.TILES

        vm.setRequestMode(WmsRequestMode.IMAGE)
        vm.await { it.opened?.service?.requestMode == WmsRequestMode.IMAGE }
        vm.setRequestMode(WmsRequestMode.TILES)

        vm.await { it.opened?.service?.requestMode == WmsRequestMode.TILES }.services.single().requestMode shouldBe WmsRequestMode.TILES
    }
}
