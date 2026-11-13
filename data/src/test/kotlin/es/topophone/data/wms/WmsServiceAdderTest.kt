package es.topophone.data.wms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.wms.WmsAddressProblem
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Un servicio que contesta lo que se le diga (o falla) y apunta qué direcciones se le pidieron. */
private class FakeWmsHttp(private val answer: (String) -> Any) : WmsHttp {
    val urls = mutableListOf<String>()

    override suspend fun <T> get(url: String, read: (WmsHttpResponse) -> T): T {
        urls += url
        return when (val reply = answer(url)) {
            is Throwable -> throw reply
            is Reply -> read(WmsHttpResponse(reply.code, ByteArrayInputStream(reply.body)))
            else -> error("Respuesta de prueba desconocida")
        }
    }
}

private class Reply(val code: Int, val body: ByteArray)

/** Añadir un servicio por dirección o por preset y actualizarlo, en 1.3.0 y, si no, en 1.1.1 (S036/RF-1, RF-2, RF-6, RF-8 a RF-10, RF-14). */
@RunWith(AndroidJUnit4::class)
class WmsServiceAdderTest {

    private lateinit var db: TopoPhoneDatabase
    private lateinit var library: RoomWmsLibraryRepository
    private var ids = 0

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        library = RoomWmsLibraryRepository(db, newId = { "s${++ids}" })
    }

    @After
    fun close() = db.close()

    private fun fixture(name: String) = File("../fixtures/formatos/wms/$name").readBytes()

    private fun ok(name: String) = Reply(200, fixture(name))

    private fun adder(http: WmsHttp) = WmsServiceAdder(http, library)

    private fun v13(url: String) = "VERSION=1.3.0" in url

    private fun added(outcome: WmsAddOutcome) = outcome.shouldBeInstanceOf<WmsAddOutcome.Added>().service

    @Test
    fun `S036 RF-1 se pide el GetCapabilities 1_3_0 y se guarda el servicio con sus capas`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("caps-1.3.0-basico.xml") }

        val outcome = adder(http).add("ejemplo.es/wms")

        val service = outcome.shouldBeInstanceOf<WmsAddOutcome.Added>().service
        http.urls shouldBe listOf("https://ejemplo.es/wms?SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.3.0")
        service.name shouldBe "Servicio de ejemplo"
        service.version shouldBe "1.3.0"
        service.attribution shouldBe "© Servicio de ejemplo"
        service.requestMode shouldBe WmsRequestMode.TILES
        library.layers(service.id).map { it.name } shouldBe listOf("raiz", "rgb", "irg")
    }

    @Test
    fun `S036 RF-1 si no ofrece la 1_3_0 se pide la 1_1_1`() = runBlocking<Unit> {
        val http = FakeWmsHttp { url -> if (v13(url)) ok("error-excepcion-1.3.0.xml") else ok("caps-1.1.1-basico.xml") }

        val service = added(adder(http).add("https://ejemplo.es/wms"))

        http.urls.map { it.substringAfter("VERSION=") } shouldBe listOf("1.3.0", "1.1.1")
        service.version shouldBe "1.1.1"
    }

    @Test
    fun `S036 RF-1 una respuesta HTTP de error o una pagina que no es de capacidades tambien prueba la 1_1_1`() = runBlocking<Unit> {
        listOf(Reply(400, ByteArray(0)), ok("error-html-200.html"), ok("error-otro-xml.xml")).forEach { first ->
            val http = FakeWmsHttp { url -> if (v13(url)) first else ok("caps-1.1.1-basico.xml") }

            adder(http).add("https://ejemplo.es/wms${first.code}${first.body.size}").shouldBeInstanceOf<WmsAddOutcome.Added>()

            http.urls.size shouldBe 2
        }
    }

    @Test
    fun `S036 RF-2 el Catastro contesta 1_1_1 a la peticion 1_3_0 y se guarda con esa version sin pedir otra`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("presets/catastro-wms/capabilities-1.3.0.xml") }

        val service = added(adder(http).add("https://ovc.catastro.meh.es/Cartografia/WMS/ServidorWMS.aspx"))

        service.version shouldBe "1.1.1"
        http.urls.size shouldBe 1
    }

    @Test
    fun `S036 RF-8 si las dos versiones fallan sale el motivo de la ultima y no se guarda nada`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("error-excepcion-1.1.1.xml") }

        val outcome = adder(http).add("https://ejemplo.es/wms")

        outcome shouldBe WmsAddOutcome.Failed(WmsAddProblem.SERVICE_EXCEPTION, "Parcela no encontrada")
        http.urls.size shouldBe 2
        library.services().first() shouldBe emptyList()
    }

    @Test
    fun `S036 RF-8 un documento danado, uno demasiado grande o un fallo de red no prueban la otra version`() = runBlocking<Unit> {
        val cases = listOf(
            ok("error-xml-danado.xml") to WmsAddProblem.MALFORMED,
            ok("error-vacio.xml") to WmsAddProblem.MALFORMED,
            Reply(200, "<a>".toByteArray() + ByteArray(WMS_TEST_LIMIT + 1) { 'x'.code.toByte() }) to WmsAddProblem.TOO_LARGE,
            UnknownHostException("sin red") to WmsAddProblem.NO_RESPONSE,
            IOException("conexion rechazada") to WmsAddProblem.NO_RESPONSE,
            WmsTimeoutException() to WmsAddProblem.TIMEOUT,
        )
        cases.forEachIndexed { index, (answer, problem) ->
            val http = FakeWmsHttp { answer }

            val outcome = WmsServiceAdder(http, library, maxBytes = WMS_TEST_LIMIT.toLong()).add("https://ejemplo.es/caso$index")

            outcome.shouldBeInstanceOf<WmsAddOutcome.Failed>().problem shouldBe problem
            http.urls.size shouldBe 1
        }
    }

    @Test
    fun `S036 RF-8 una respuesta de error con una excepcion de servicio da su mensaje`() = runBlocking<Unit> {
        val http = FakeWmsHttp { Reply(500, fixture("error-excepcion-1.1.1.xml")) }

        adder(http).add("https://ejemplo.es/wms") shouldBe WmsAddOutcome.Failed(WmsAddProblem.SERVICE_EXCEPTION, "Parcela no encontrada")
    }

    @Test
    fun `S036 RF-8 una respuesta de error sin nada util da el codigo HTTP`() = runBlocking<Unit> {
        val http = FakeWmsHttp { Reply(503, "<html>No disponible</html>".toByteArray()) }

        adder(http).add("https://ejemplo.es/wms") shouldBe WmsAddOutcome.Failed(WmsAddProblem.HTTP_ERROR, "503")
    }

    @Test
    fun `S036 RF-9 un servicio sin ninguna capa en Web Mercator se rechaza`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("caps-1.3.0-sin-3857.xml") }

        val outcome = adder(http).add("https://ejemplo.es/wms")

        outcome.shouldBeInstanceOf<WmsAddOutcome.Failed>().problem shouldBe WmsAddProblem.NO_WEB_MERCATOR

        library.services().first() shouldBe emptyList()
    }

    @Test
    fun `S036 RF-9 una capa que agrupa con una hija que se puede anadir vale`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("caps-1.3.0-sin-nombre.xml") }

        // la capa que agrupa no se puede añadir, pero su hija tiene nombre y hereda Web Mercator: sí hay capa que añadir
        adder(http).add("https://ejemplo.es/wms").shouldBeInstanceOf<WmsAddOutcome.Added>()
    }

    @Test
    fun `S036 RF-10 una direccion HTTP publica no se pide y una privada si`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("caps-1.3.0-basico.xml") }

        adder(http).add("http://ovc.catastro.meh.es/wms") shouldBe WmsAddOutcome.InvalidAddress(WmsAddressProblem.PUBLIC_HTTP)
        http.urls shouldBe emptyList()
        adder(http).add("http://192.168.1.10:8080/geoserver/wms").shouldBeInstanceOf<WmsAddOutcome.Added>()
        http.urls.single().startsWith("http://192.168.1.10:8080/geoserver/wms?") shouldBe true
    }

    @Test
    fun `S036 RF-1 una direccion vacia o que no lo es no se pide`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("caps-1.3.0-basico.xml") }

        adder(http).add("   ") shouldBe WmsAddOutcome.InvalidAddress(WmsAddressProblem.EMPTY)
        adder(http).add("ftp://x.es/wms") shouldBe WmsAddOutcome.InvalidAddress(WmsAddressProblem.UNSUPPORTED_SCHEME)
        http.urls shouldBe emptyList()
    }

    @Test
    fun `S036 RF-5 los parametros de la direccion van en la peticion y se guardan con el servicio`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("caps-1.3.0-basico.xml") }

        val service = added(adder(http).add("https://x.es/cgi-bin/mapserv?map=/datos/obra.map&SERVICE=WMS"))

        http.urls.single() shouldBe "https://x.es/cgi-bin/mapserv?map=/datos/obra.map&SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.3.0"
        service.address shouldBe "https://x.es/cgi-bin/mapserv?map=/datos/obra.map"
    }

    @Test
    fun `S036 RF-15 una direccion que ya esta en la biblioteca no se pide ni se duplica`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("caps-1.3.0-basico.xml") }
        val first = added(adder(http).add("https://ejemplo.es/wms"))

        val again = adder(http).add("EJEMPLO.es/wms?SERVICE=WMS")

        again.shouldBeInstanceOf<WmsAddOutcome.AlreadyThere>().service.id shouldBe first.id
        http.urls.size shouldBe 1
    }

    @Test
    fun `S036 RF-17 un servicio propio puede guardarse en una imagen y con su nombre`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("caps-1.3.0-basico.xml") }

        val service = added(adder(http).add("https://ejemplo.es/wms", WmsRequestMode.IMAGE, "Mi servicio"))

        service.requestMode shouldBe WmsRequestMode.IMAGE
        service.name shouldBe "Mi servicio"
    }

    @Test
    fun `S036 RF-14 un preset se pide por su direccion y se guarda con su clave, su nombre y su modo`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("presets/catastro-wms/capabilities-1.1.1.xml") }

        val service = adder(http).addPreset("catastro-wms").shouldBeInstanceOf<WmsAddOutcome.Added>().service

        http.urls.first().startsWith("https://ovc.catastro.meh.es/Cartografia/WMS/ServidorWMS.aspx?") shouldBe true
        service.presetKey shouldBe "catastro-wms"
        service.name shouldBe "Catastro: cartografía catastral"
        service.requestMode shouldBe WmsRequestMode.IMAGE
        library.layers(service.id).any { it.name == "Catastro" } shouldBe true
    }

    @Test
    fun `S036 RF-14 un preset por teselas de otro servicio se guarda por teselas`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("presets/ign-pnoa-ma/capabilities-1.3.0.xml") }

        adder(http).addPreset("ign-pnoa-ma").shouldBeInstanceOf<WmsAddOutcome.Added>().service.requestMode shouldBe WmsRequestMode.TILES
    }

    @Test
    fun `S036 RF-15 un preset que ya esta no se vuelve a pedir y uno que no existe se dice`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("presets/ign-pnoa-ma/capabilities-1.3.0.xml") }
        adder(http).addPreset("ign-pnoa-ma")

        adder(http).addPreset("ign-pnoa-ma").shouldBeInstanceOf<WmsAddOutcome.AlreadyThere>()
        adder(http).addPreset("no-existe") shouldBe WmsAddOutcome.UnknownPreset
        http.urls.size shouldBe 1
    }

    @Test
    fun `S036 RF-6 actualizar vuelve a leer las capas y avisa de las que han desaparecido`() = runBlocking<Unit> {
        val service = added(adder(FakeWmsHttp { ok("caps-1.3.0-basico.xml") }).add("https://ejemplo.es/wms"))
        val project = projectWith(service.id, "irg")
        val http = FakeWmsHttp { ok("caps-1.3.0-sin-atribucion.xml") }

        val outcome = adder(http).refresh(service.id)

        outcome.shouldBeInstanceOf<WmsRefreshOutcome.Refreshed>().missing.map { it.layerName } shouldBe listOf("irg")
        library.service(service.id)?.title shouldBe "Sin atribución"
        library.service(service.id)?.name shouldBe "Servicio de ejemplo"
        library.layers(service.id).map { it.name } shouldBe listOf("sola")
        project.isNotEmpty() shouldBe true
    }

    @Test
    fun `S036 RF-6 si al actualizar falla el servicio lo guardado no cambia`() = runBlocking<Unit> {
        val service = added(adder(FakeWmsHttp { ok("caps-1.3.0-basico.xml") }).add("https://ejemplo.es/wms"))

        adder(FakeWmsHttp { WmsTimeoutException() }).refresh(service.id) shouldBe WmsRefreshOutcome.Failed(WmsAddProblem.TIMEOUT, null)
        adder(FakeWmsHttp { ok("caps-1.3.0-sin-3857.xml") }).refresh(service.id) shouldBe
            WmsRefreshOutcome.Failed(WmsAddProblem.NO_WEB_MERCATOR, null)

        library.layers(service.id).map { it.name } shouldBe listOf("raiz", "rgb", "irg")
        library.service(service.id)?.title shouldBe "Servicio de ejemplo"
    }

    @Test
    fun `S036 RF-6 actualizar un servicio que no existe se dice sin pedir nada`() = runBlocking<Unit> {
        val http = FakeWmsHttp { ok("caps-1.3.0-basico.xml") }

        adder(http).refresh("no-existe") shouldBe WmsRefreshOutcome.NotFound
        http.urls shouldBe emptyList()
    }

    @Test
    fun `S036 RF-6 actualizar pide primero la version que declaro el servicio`() = runBlocking<Unit> {
        val service = added(adder(FakeWmsHttp { ok("caps-1.1.1-basico.xml") }).add("https://ejemplo.es/wms"))
        val http = FakeWmsHttp { url -> if (v13(url)) ok("error-excepcion-1.3.0.xml") else ok("caps-1.1.1-basico.xml") }

        adder(http).refresh(service.id).shouldBeInstanceOf<WmsRefreshOutcome.Refreshed>()

        http.urls.map { it.substringAfter("VERSION=") } shouldBe listOf("1.1.1")
    }

    @Test
    fun `S036 RF-6 si la version que declaro ya no responde al actualizar se prueba la otra`() = runBlocking<Unit> {
        val service = added(adder(FakeWmsHttp { ok("caps-1.3.0-basico.xml") }).add("https://ejemplo.es/wms"))
        val http = FakeWmsHttp { url -> if (v13(url)) ok("error-excepcion-1.3.0.xml") else ok("caps-1.1.1-basico.xml") }

        adder(http).refresh(service.id).shouldBeInstanceOf<WmsRefreshOutcome.Refreshed>()

        http.urls.map { it.substringAfter("VERSION=") } shouldBe listOf("1.3.0", "1.1.1")
        library.service(service.id)?.version shouldBe "1.1.1"
    }

    private fun projectWith(serviceId: String, layer: String): String = runBlocking {
        val project = es.topophone.data.project.RoomProjectRepository(db)
            .create(es.topophone.core.model.project.ProjectDefaults().toDraft().copy(name = "Obra"))
            .shouldBeInstanceOf<es.topophone.data.project.SaveResult.Saved>().id
        RoomProjectLayersRepository(db).add(project, serviceId, layer).shouldBeInstanceOf<AddLayerResult.Added>()
        project
    }

    private companion object {
        const val WMS_TEST_LIMIT = 1_000
    }
}
