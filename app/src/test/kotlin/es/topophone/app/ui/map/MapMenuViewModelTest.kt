package es.topophone.app.ui.map

import dagger.Lazy
import es.topophone.app.ui.wms.FakeReply
import es.topophone.app.ui.wms.FakeWmsHttp
import es.topophone.core.formats.wms.FeatureInfoResult
import es.topophone.core.formats.wms.FeatureInfoText
import es.topophone.core.formats.wms.WmsRequests
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.wms.ProjectMapLayer
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.wms.ProjectLayerView
import es.topophone.data.wms.WmsTimeoutException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** El menú del mapa y la consulta de información de las capas con respuestas grabadas de servicios reales (S036/RF-34 a RF-39). */
@OptIn(ExperimentalCoroutinesApi::class)
class MapMenuViewModelTest {

    private val area = VisibleArea(south = 40.41, west = -3.71, north = 40.42, east = -3.70, widthPx = 1080, heightPx = 1800)
    private val press = MapPress.of(40.4168, -3.7038, 540f, 900f, area)

    private val project = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0L,
        modifiedAtMs = 0L,
        openedAtMs = null,
        deletedAtMs = null,
    )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun fixture(name: String) = File("../fixtures/formatos/wms/info/$name").readBytes()

    private fun service(
        id: String,
        address: String = "https://$id.es/wms",
        version: String = "1.3.0",
        presetKey: String? = null,
        infoFormats: List<String> = listOf("text/plain"),
    ) = WmsService(id, "Servicio $id", address, id, version, "", presetKey, WmsRequestMode.TILES, listOf("image/png"), infoFormats, 0L, 0L)

    private fun view(
        id: String,
        service: WmsService = service(id),
        position: Int = 0,
        visible: Boolean = true,
        queryable: Boolean = true,
        available: Boolean = true,
        layerName: String = "capa-$id",
    ): ProjectLayerView {
        val layer = ProjectMapLayer(id, "p1", service.id, layerName, position, visible, 100)
        val info = if (available) WmsLayerInfo(0, 0, layerName, "Capa $id", "", true, queryable, "EPSG:3857") else null
        return ProjectLayerView(layer, service, info, showService = false)
    }

    private fun viewModel(http: FakeWmsHttp) = MapMenuViewModel(http, Lazy { ProjectPositionCalculator() })

    private fun MapMenuViewModel.statusOf(layerId: String) = state.value.results.first { it.layerId == layerId }.status

    @Test
    fun `S036 RF-34 abrir el menu da el punto marcado y empieza en las dos opciones`() {
        val vm = viewModel(FakeWmsHttp { ByteArray(0) })
        vm.state.value.isOpen shouldBe false

        vm.open(press, listOf(view("a")))

        vm.state.value.press shouldBe press
        vm.state.value.isOpen shouldBe true
        vm.state.value.screen shouldBe MapMenuScreen.MENU
    }

    @Test
    fun `S036 RF-36 solo se pueden consultar las capas visibles, disponibles y consultables, de arriba abajo`() {
        val vm = viewModel(FakeWmsHttp { ByteArray(0) })
        val layers = listOf(
            view("a", position = 0),
            view("b", position = 1, visible = false),
            view("c", position = 2, queryable = false),
            view("d", position = 3, available = false),
            view("e", position = 4),
        )

        vm.open(press, layers)

        vm.state.value.queryable.map { it.layer.id } shouldBe listOf("a", "e")
        vm.state.value.visibleLayers shouldBe 4
        vm.state.value.canQuery shouldBe true
    }

    @Test
    fun `S036 RF-37 si ninguna capa visible se puede consultar la opcion queda desactivada y no se pide nada`() {
        val http = FakeWmsHttp { ByteArray(0) }
        val vm = viewModel(http)

        vm.open(press, listOf(view("a", queryable = false), view("b", visible = false)))
        vm.queryLayers()

        vm.state.value.canQuery shouldBe false
        vm.state.value.visibleLayers shouldBe 1
        vm.state.value.screen shouldBe MapMenuScreen.MENU
        http.urls shouldBe emptyList()
        vm.open(press, emptyList())
        vm.state.value.visibleLayers shouldBe 0
        vm.state.value.canQuery shouldBe false
    }

    @Test
    fun `S036 RF-36 cada capa muestra su respuesta con el nombre de su capa y el de su servicio`() {
        val http = FakeWmsHttp { fixture("ign-pnoa-ma.txt") }
        val vm = viewModel(http)
        vm.open(press, listOf(view("a", service("a", infoFormats = listOf("text/plain", "text/html")))))

        vm.queryLayers()

        vm.state.value.screen shouldBe MapMenuScreen.INFO
        val result = vm.state.value.results.single()
        result.layerTitle shouldBe "Capa a"
        result.serviceName shouldBe "Servicio a"
        val text = result.status.shouldBeInstanceOf<LayerInfoStatus.Text>().text
        text shouldBe (FeatureInfoText.parse(fixture("ign-pnoa-ma.txt")) as FeatureInfoResult.Text).text
        text shouldContain "2024-07"
    }

    @Test
    fun `S036 RF-38 una capa sin datos en el punto lo dice`() {
        val vm = viewModel(FakeWmsHttp { fixture("ign-hidrografia-sin-datos.txt") })
        vm.open(press, listOf(view("a")))

        vm.queryLayers()

        vm.statusOf("a") shouldBe LayerInfoStatus.NoData
    }

    @Test
    fun `S036 RF-39 un servicio que falla se indica en su capa y las demas salen igual`() {
        val exception = "<ServiceExceptionReport version=\"1.3.0\">" +
            "<ServiceException>Capa no valida</ServiceException></ServiceExceptionReport>"
        val http = FakeWmsHttp { url ->
            when {
                "/ok/" in url -> fixture("ign-pnoa-ma.txt")
                "/timeout/" in url -> WmsTimeoutException()
                "/red/" in url -> UnknownHostException("sin red")
                "/http/" in url -> FakeReply(503, "<html>No disponible</html>".toByteArray())
                else -> exception.toByteArray()
            }
        }
        val vm = viewModel(http)
        vm.open(
            press,
            listOf("ok", "timeout", "red", "http", "excepcion").mapIndexed { i, id ->
                view(id, service(id, address = "https://$id.es/$id/wms"), position = i)
            },
        )

        vm.queryLayers()

        vm.statusOf("ok").shouldBeInstanceOf<LayerInfoStatus.Text>()
        vm.statusOf("timeout") shouldBe LayerInfoStatus.Failed(LayerInfoProblem.TIMEOUT)
        vm.statusOf("red") shouldBe LayerInfoStatus.Failed(LayerInfoProblem.NO_RESPONSE)
        vm.statusOf("http") shouldBe LayerInfoStatus.Failed(LayerInfoProblem.HTTP_ERROR, "503")
        vm.statusOf("excepcion") shouldBe LayerInfoStatus.Failed(LayerInfoProblem.SERVICE_EXCEPTION, "Capa no valida")
    }

    @Test
    fun `S036 RF-39 cada capa sale en cuanto llega su respuesta sin esperar a las demas`() {
        val slow = CompletableDeferred<Unit>()
        val http = FakeWmsHttp { url ->
            if ("/lenta/" in url) slow.await()
            fixture("ign-pnoa-ma.txt")
        }
        val vm = viewModel(http)
        vm.open(
            press,
            listOf(
                view("rapida", service("rapida", "https://rapida.es/rapida/wms")),
                view("lenta", service("lenta", "https://lenta.es/lenta/wms"), 1),
            ),
        )

        vm.queryLayers()

        vm.statusOf("rapida").shouldBeInstanceOf<LayerInfoStatus.Text>()
        vm.statusOf("lenta") shouldBe LayerInfoStatus.Loading
        slow.complete(Unit)
        vm.statusOf("lenta").shouldBeInstanceOf<LayerInfoStatus.Text>()
    }

    @Test
    fun `S036 RF-36 no se piden mas de 5 capas a la vez y las demas esperan su turno`() {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val http = FakeWmsHttp {
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            gate.await()
            running.decrementAndGet()
            fixture("ign-pnoa-ma.txt")
        }
        val vm = viewModel(http)
        vm.open(press, (0 until 7).map { view("l$it", service("l$it"), position = it) })

        vm.queryLayers()

        http.urls.size shouldBe 5
        vm.state.value.results.count { it.status == LayerInfoStatus.Loading } shouldBe 7
        gate.complete(Unit)
        http.urls.size shouldBe 7
        most.get() shouldBe 5
        vm.state.value.results.all { it.status is LayerInfoStatus.Text } shouldBe true
    }

    @Test
    fun `S036 RF-36 la peticion lleva la capa, el punto y la zona visible en la version y el formato del servicio`() {
        val http = FakeWmsHttp { fixture("ign-pnoa-ma.txt") }
        val s = service("a", address = "https://a.es/wms", version = "1.3.0", infoFormats = listOf("text/html", "application/vnd.ogc.gml"))
        val vm = viewModel(http)
        vm.open(press, listOf(view("a", s, layerName = "OI.MosaicElement")))

        vm.queryLayers()

        val address = (WmsAddress.parse("https://a.es/wms") as WmsAddressResult.Valid).address
        http.urls.single() shouldBe WmsRequests.featureInfo(
            address, "1.3.0", "OI.MosaicElement", "EPSG:3857", "application/vnd.ogc.gml", 40.41, -3.71, 40.42, -3.70, 1080, 1800, 540, 900,
        )
        http.urls.single() shouldContain "INFO_FORMAT=application/vnd.ogc.gml"
        http.urls.single() shouldContain "QUERY_LAYERS=OI.MosaicElement"
        http.urls.single() shouldContain "&I=540&J=900"
    }

    @Test
    fun `S036 RF-36 el formato es el primero que anuncia el servicio y si no anuncia ninguno conocido el primero que anuncia`() {
        fun formatOf(formats: List<String>): String {
            val http = FakeWmsHttp { ByteArray(0) }
            val vm = viewModel(http)
            vm.open(press, listOf(view("a", service("a", infoFormats = formats))))
            vm.queryLayers()
            return http.urls.single().substringAfter("INFO_FORMAT=").substringBefore("&")
        }

        formatOf(listOf("text/html", "text/plain")) shouldBe "text/plain"
        formatOf(listOf("text/html", "text/xml")) shouldBe "text/xml"
        formatOf(listOf("application/json")) shouldBe "application/json"
        formatOf(emptyList()) shouldBe "text/plain"
    }

    @Test
    fun `S036 RF-36 el Catastro INSPIRE se consulta en 1_1_1 aunque el servicio declare otra version`() {
        val http = FakeWmsHttp { fixture("catastro-inspire-parcela-1.1.1.html") }
        val s =
            service(
                "cat",
                address = "https://ovc.catastro.meh.es/cartografia/INSPIRE/spadgcwms.aspx",
                version = "1.3.0",
                presetKey = "catastro-inspire",
                infoFormats = listOf("text/html"),
            )
        val vm = viewModel(http)
        vm.open(press, listOf(view("cat", s, layerName = "CP.CadastralParcel")))

        vm.queryLayers()

        http.urls.single() shouldContain "VERSION=1.1.1"
        http.urls.single() shouldContain "SRS=EPSG:3857"
        http.urls.single() shouldContain "&X=540&Y=900"
        http.urls.single() shouldNotContain "CRS=EPSG:3857&"
        vm.statusOf("cat").shouldBeInstanceOf<LayerInfoStatus.Text>()
    }

    @Test
    fun `S036 RF-35 cerrar el menu lo deja cerrado y cancela lo que estaba en vuelo`() {
        val gate = CompletableDeferred<Unit>()
        val http = FakeWmsHttp {
            gate.await()
            fixture("ign-pnoa-ma.txt")
        }
        val vm = viewModel(http)
        vm.open(press, listOf(view("a")))
        vm.queryLayers()
        vm.statusOf("a") shouldBe LayerInfoStatus.Loading

        vm.close()
        gate.complete(Unit)

        vm.state.value shouldBe MapMenuUiState()
        vm.state.value.isOpen shouldBe false
    }

    @Test
    fun `S036 RF-36 volver al menu deja las dos opciones y quita lo consultado`() {
        val vm = viewModel(FakeWmsHttp { fixture("ign-pnoa-ma.txt") })
        vm.open(press, listOf(view("a")))
        vm.queryLayers()
        vm.state.value.results.size shouldBe 1

        vm.backToMenu()

        vm.state.value.screen shouldBe MapMenuScreen.MENU
        vm.state.value.results shouldBe emptyList()
        vm.state.value.press shouldBe press
    }

    @Test
    fun `S036 RF-34 una pulsacion nueva sustituye a la anterior y su consulta`() {
        val http = FakeWmsHttp { fixture("ign-pnoa-ma.txt") }
        val vm = viewModel(http)
        vm.open(press, listOf(view("a")))
        vm.queryLayers()
        val other = MapPress.of(40.415, -3.705, 100f, 200f, area)

        vm.open(other, listOf(view("a")))

        vm.state.value.press shouldBe other
        vm.state.value.screen shouldBe MapMenuScreen.MENU
        vm.state.value.results shouldBe emptyList()
    }

    @Test
    fun `S036 RF-40 Ver coordenadas da las del punto marcado en el SC del proyecto`() {
        val vm = viewModel(FakeWmsHttp { ByteArray(0) })
        vm.open(press, listOf(view("a")), project)

        vm.showCoordinates()

        vm.state.value.screen shouldBe MapMenuScreen.COORDINATES
        val coordinates = vm.state.value.coordinates.shouldBeInstanceOf<MapCoordinates>()
        coordinates.latitudeDeg shouldBe 40.4168
        coordinates.longitudeDeg shouldBe -3.7038
        coordinates.outOfScope shouldBe null
        coordinates.position.easting.shouldBeInstanceOf<Double>()
    }

    @Test
    fun `S036 RF-41 Ver coordenadas fuera del ambito del SC solo lleva latitud y longitud`() {
        val vm = viewModel(FakeWmsHttp { ByteArray(0) })
        vm.open(MapPress.of(28.1, -15.4, 10f, 10f, area), emptyList(), project)

        vm.showCoordinates()

        vm.state.value.coordinates?.outOfScope shouldBe es.topophone.core.survey.PositionUnavailable.OUTSIDE_CRS_AREA
    }

    @Test
    fun `S036 RF-40 Ver coordenadas no hace nada sin el SC del proyecto`() {
        val vm = viewModel(FakeWmsHttp { ByteArray(0) })
        vm.open(press, listOf(view("a")))

        vm.showCoordinates()

        vm.state.value.screen shouldBe MapMenuScreen.MENU
        vm.state.value.coordinates shouldBe null
    }

    @Test
    fun `S036 RF-35 volver al menu o cerrarlo quita las coordenadas`() {
        val vm = viewModel(FakeWmsHttp { ByteArray(0) })
        vm.open(press, listOf(view("a")), project)
        vm.showCoordinates()

        vm.backToMenu()
        vm.state.value.screen shouldBe MapMenuScreen.MENU
        vm.state.value.coordinates shouldBe null
        vm.showCoordinates()
        vm.close()

        vm.state.value shouldBe MapMenuUiState()
    }
}
