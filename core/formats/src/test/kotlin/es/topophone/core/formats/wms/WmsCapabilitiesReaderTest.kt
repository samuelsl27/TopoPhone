package es.topophone.core.formats.wms

import es.topophone.core.model.wms.WmsLayerProblem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Test

class WmsCapabilitiesReaderTest {

    private fun fixture(name: String) = WmsCapabilitiesReader.read(ByteArrayInputStream(WmsFixtures.bytes(name)))

    private fun read(xml: String) = WmsCapabilitiesReader.read(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun problem(name: String) = shouldThrow<WmsCapabilitiesException> { fixture(name) }

    private fun capabilities(layers: String): String {
        val head = "<?xml version=\"1.0\"?><WMS_Capabilities version=\"1.3.0\"><Service><Title>S</Title></Service>"
        return "$head<Capability>$layers</Capability></WMS_Capabilities>"
    }

    @Test
    fun `S036 RF-3 cada documento de capacidades se lee como dice la referencia de tools`() {
        val expected = WmsFixtures.expected().filter { it.state == "ok" }

        expected.size shouldBe 11
        for (doc in expected) {
            withClue(doc.file) {
                val caps = fixture(doc.file)
                caps.version shouldBe doc.version
                caps.title shouldBe doc.title
                caps.attribution shouldBe doc.attribution
                caps.mapFormats shouldBe doc.mapFormats
                caps.infoFormats shouldBe doc.infoFormats
                caps.layers.size shouldBe doc.layers.size
                doc.layers.zip(caps.layers).forEach { (want, got) ->
                    withClue("capa ${want.ordinal}") {
                        got.ordinal shouldBe want.ordinal
                        got.depth shouldBe want.depth
                        got.name shouldBe want.name
                        got.title shouldBe want.title
                        got.abstract shouldBe want.abstract
                        got.transparent shouldBe want.transparent
                        got.queryable shouldBe want.queryable
                        got.mercatorCode shouldBe want.code
                    }
                }
            }
        }
    }

    @Test
    fun `S036 RF-8 lo que no son capacidades sale con su motivo y el mensaje de la excepcion`() {
        val expected = WmsFixtures.expected().filter { it.state != "ok" }

        expected.size shouldBe 6
        for (doc in expected) {
            val error = problem(doc.file)
            withClue(doc.file) {
                error.problem.name shouldBe doc.state
                if (doc.state == "SERVICE_EXCEPTION") error.serviceMessage shouldBe doc.message
            }
        }
    }

    @Test
    fun `S036 RF-2 la version es la que declara el documento`() {
        fixture("caps-1.3.0-basico.xml").version shouldBe "1.3.0"
        fixture("caps-1.1.1-basico.xml").version shouldBe "1.1.1"
    }

    @Test
    fun `S036 RF-11 diez niveles se leen todos y los de mas de diez se ignoran`() {
        fixture("caps-1.3.0-anidadas-10.xml").layers.map { it.depth } shouldBe (0..9).toList()
        fixture("caps-1.3.0-anidadas-12.xml").layers.map { it.depth } shouldBe (0..9).toList()
    }

    @Test
    fun `S036 RF-11 el DOCTYPE con subconjunto interno y el ISO-8859-1 del Catastro se leen`() {
        val caps = fixture("caps-1.1.1-doctype-interno.xml")

        caps.title shouldBe "Cartografía catastral"
        caps.layers.single().title shouldBe "Cartografía catastral: parcelas y edificios"
    }

    @Test
    fun `S036 RF-11 un prefijo sin declarar no impide leer el documento`() {
        fixture("caps-1.1.1-prefijo-sin-declarar.xml").layers.single().name shouldBe "mta10r"
    }

    @Test
    fun `S036 RF-9 un servicio sin 3857 se lee y ninguna de sus capas se puede anadir`() {
        val caps = fixture("caps-1.3.0-sin-3857.xml")

        caps.hasAddableLayer shouldBe false
        caps.layers.single().notAddableReason shouldBe WmsLayerProblem.NO_WEB_MERCATOR
    }

    @Test
    fun `S036 RF-9 los alias de Web Mercator valen y el 3857 se prefiere`() {
        val byName = fixture("caps-1.3.0-alias.xml").layers.associateBy { it.name }

        byName["a-3785"]?.mercatorCode shouldBe "EPSG:3785"
        byName["a-102113"]?.mercatorCode shouldBe "EPSG:102113"
        byName["a-102100"]?.mercatorCode shouldBe "EPSG:900913"
        byName["a-ambos"]?.mercatorCode shouldBe "EPSG:3857"
        byName["a-minus"]?.mercatorCode shouldBe "EPSG:3857"
        byName["a-25830"]?.mercatorCode shouldBe null
        fixture("caps-1.3.0-alias.xml").hasAddableLayer shouldBe true
    }

    @Test
    fun `S036 RF-12 una capa que solo agrupa se ve y no se puede anadir`() {
        val layers = fixture("caps-1.3.0-sin-nombre.xml").layers

        layers.map { it.notAddableReason } shouldBe listOf(WmsLayerProblem.GROUP_ONLY, null, WmsLayerProblem.GROUP_ONLY, null)
        layers.map { it.mercatorCode } shouldBe List(4) { "EPSG:3857" } // el 3857 baja de la capa que agrupa
    }

    @Test
    fun `S036 RF-3 las capas heredan lo consultable y la transparencia de su ascendiente y lo pueden cambiar`() {
        val layers = fixture("caps-1.3.0-sin-nombre.xml").layers

        layers.map { it.queryable } shouldBe listOf(true, true, true, false)
        val basic = fixture("caps-1.3.0-basico.xml").layers.associateBy { it.name }
        basic["irg"]?.transparent shouldBe false
        basic["rgb"]?.transparent shouldBe true
    }

    @Test
    fun `S036 RF-3 los nombres con espacios, barras verticales, solo numeros y repetidos se conservan y las entidades se resuelven`() {
        val layers = fixture("caps-1.3.0-nombres-raros.xml").layers

        layers.map { it.name } shouldBe listOf(
            "OWS_0101_BCV05",
            "Usos del suelo | Usos del sòl | Land use",
            "0",
            "repetida",
            "repetida",
            "con & ampersand",
        )
        layers.last().title shouldBe "Título con <etiqueta> y & más"
        layers.last().abstract shouldBe "Línea 1\nLínea 2"
    }

    @Test
    fun `S036 RF-3 sin atribucion y sin formatos de consulta salen vacios`() {
        val caps = fixture("caps-1.3.0-sin-atribucion.xml")

        caps.attribution shouldBe ""
        caps.infoFormats shouldBe emptyList()
    }

    @Test
    fun `S036 RF-3 el estilo de una capa no cambia su nombre ni su titulo`() {
        val layer = "<Layer><Name>capa</Name><Title>Capa</Title><CRS>EPSG:3857</CRS>" +
            "<Style><Name>default</Name><Title>Estilo</Title></Style>" +
            "<KeywordList><Keyword>clave</Keyword></KeywordList></Layer>"
        val caps = read(capabilities(layer))

        caps.layers.single().name shouldBe "capa"
        caps.layers.single().title shouldBe "Capa"
    }

    @Test
    fun `S036 RF-3 la atribucion es la del primer Attribution del documento`() {
        val layers = "<Layer><Name>a</Name><Title>A</Title><Attribution><Title>Primera</Title></Attribution>" +
            "<Layer><Name>b</Name><Title>B</Title><Attribution><Title>Segunda</Title></Attribution></Layer></Layer>"
        val caps = read(capabilities(layers))

        caps.attribution shouldBe "Primera"
        caps.layers.map { it.title } shouldBe listOf("A", "B")
    }

    @Test
    fun `S036 RF-3 un titulo en CDATA y un SRS con varios codigos separados por comas o espacios se leen`() {
        val layer = "<Layer><Name>a</Name><Title><![CDATA[Capa <A> & B]]></Title><SRS>EPSG:4326,EPSG:102100 EPSG:25830</SRS></Layer>"
        val caps = read(capabilities(layer))

        caps.layers.single().title shouldBe "Capa <A> & B"
        caps.layers.single().mercatorCode shouldBe "EPSG:102100"
    }

    @Test
    fun `S036 RF-3 un documento de otra version desconocida se lee igual con la version que declara`() {
        read("""<WMS_Capabilities version="1.3.0"><Service><Title>S</Title></Service><Capability></Capability></WMS_Capabilities>""")
            .version shouldBe "1.3.0"
    }

    @Test
    fun `S036 RF-8 un fallo de lectura de la red sale como IOException y no como documento danado`() {
        val broken = object : InputStream() {
            private var sent = 0

            override fun read(): Int = throw IOException("sin red")

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent > 0) throw IOException("sin red")
                sent = 1
                val head = "<?xml version=\"1.0\"?><WMS_Capabilities version=\"1.3.0\"><Service>".toByteArray()
                head.copyInto(b, off)
                return head.size
            }
        }

        shouldThrow<IOException> { WmsCapabilitiesReader.read(broken) }
    }

    @Test
    fun `S036 RF-11 un documento que pasa del tamano maximo se rechaza`() {
        val big = capabilities("<Layer><Name>a</Name><Title>${"x".repeat(5_000)}</Title></Layer>")

        val input = ByteArrayInputStream(big.toByteArray())
        val error = shouldThrow<WmsCapabilitiesException> { WmsCapabilitiesReader.read(input, maxBytes = 1_000) }

        error.problem shouldBe WmsCapabilitiesProblem.TOO_LARGE
    }

    @Test
    fun `S036 RNF un documento de 5 MB con 25000 capas se lee en menos de 2 s`() {
        val xml = StringBuilder("<?xml version=\"1.0\"?><WMS_Capabilities version=\"1.3.0\">")
        xml.append("<Service><Title>Grande</Title></Service><Capability>")
        xml.append("<Layer><Title>Raíz</Title><CRS>EPSG:3857</CRS>")
        repeat(LAYERS) { i ->
            xml.append("<Layer queryable=\"1\"><Name>capa-$i</Name><Title>Capa número $i con un título algo largo</Title>")
            xml.append("<Abstract>Resumen de la capa número $i, que repite texto para ocupar sitio en el documento de prueba</Abstract>")
            xml.append("<Style><Name>default</Name><Title>Estilo por defecto</Title></Style></Layer>")
        }
        xml.append("</Layer></Capability></WMS_Capabilities>")
        val bytes = xml.toString().toByteArray(Charsets.UTF_8)

        val timed = measureTimedValue { WmsCapabilitiesReader.read(ByteArrayInputStream(bytes)) }

        println("S036 GetCapabilities de ${bytes.size / (1024 * 1024)} MB con $LAYERS capas: ${timed.duration.inWholeMilliseconds} ms")
        (bytes.size >= 5 * 1024 * 1024) shouldBe true
        timed.value.layers.size shouldBe LAYERS + 1
        timed.value.layers.last().name shouldBe "capa-${LAYERS - 1}"
        (timed.duration.inWholeMilliseconds < 2_000) shouldBe true
    }

    private companion object {
        const val LAYERS = 25_000
    }
}
