package es.topophone.core.formats.wms

import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Locale
import org.junit.jupiter.api.Test

class WmsRequestsTest {

    private fun address(text: String): WmsAddress = WmsAddress.parse(text).shouldBeInstanceOf<WmsAddressResult.Valid>().address

    private val ign = address("https://www.ign.es/wms-inspire/pnoa-ma")

    private fun bbox(url: String): List<Double> = Regex("[?&]BBOX=([^&]+)").find(url)!!.groupValues[1].split(',').map { it.toDouble() }

    private fun param(url: String, name: String): String? = Regex("[?&]$name=([^&]*)").find(url)?.groupValues?.get(1)

    /** Una consulta sobre la vista de 39,98 a 39,99 de latitud y de -0,06 a -0,05 de longitud. */
    private fun info(
        version: String = "1.3.0",
        layer: String = "capa",
        format: String = "text/plain",
        width: Int = 800,
        height: Int = 600,
        x: Int = 1,
        y: Int = 1,
    ) = WmsRequests.featureInfo(ign, version, layer, "EPSG:3857", format, 39.98, -0.06, 39.99, -0.05, width, height, x, y)

    @Test
    fun `S036 RF-1 el GetCapabilities lleva la direccion y los parametros de la peticion`() {
        WmsRequests.capabilities(ign, "1.3.0") shouldBe
            "https://www.ign.es/wms-inspire/pnoa-ma?SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.3.0"
        WmsRequests.capabilities(ign, "1.1.1") shouldBe
            "https://www.ign.es/wms-inspire/pnoa-ma?SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.1.1"
    }

    @Test
    fun `S036 RF-5 los parametros de la direccion van primero y se conservan en cada peticion`() {
        val own = address("https://x.es/cgi-bin/mapserv?map=/datos/obra.map&Idioma=es&SERVICE=WMS")

        WmsRequests.capabilities(own, "1.3.0") shouldBe
            "https://x.es/cgi-bin/mapserv?map=/datos/obra.map&Idioma=es&SERVICE=WMS&REQUEST=GetCapabilities&VERSION=1.3.0"
        val template = WmsRequests.tileTemplate(own, "1.3.0", "a", "EPSG:3857", "image/png")
        template.startsWith("https://x.es/cgi-bin/mapserv?map=/datos/obra.map&Idioma=es&") shouldBe true
    }

    @Test
    fun `S036 RF-5 un parametro de la direccion con espacios o barras verticales se codifica sin duplicar lo ya codificado`() {
        val own = address("https://x.es/wms?a=b c|d&e=f%20g&solo")

        WmsRequests.capabilities(own, "1.3.0").substringBefore("&SERVICE") shouldBe "https://x.es/wms?a=b%20c%7Cd&e=f%20g&solo"
    }

    @Test
    fun `S036 RF-29 la plantilla de teselas de 1_3_0 usa CRS, el codigo de la capa y el cuadro de MapLibre sin codificar`() {
        WmsRequests.tileTemplate(ign, "1.3.0", "OI.OrthoimageCoverage", "EPSG:3857", "image/jpeg") shouldBe
            "https://www.ign.es/wms-inspire/pnoa-ma?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=OI.OrthoimageCoverage&STYLES=" +
            "&FORMAT=image/jpeg&TRANSPARENT=TRUE&WIDTH=256&HEIGHT=256&CRS=EPSG:3857&BBOX={bbox-epsg-3857}"
    }

    @Test
    fun `S036 RF-29 la plantilla de 1_1_1 usa SRS y el alias que anuncia la capa`() {
        val url = WmsRequests.tileTemplate(address("https://ideg.xunta.gal/servizos/WMSServer"), "1.1.1", "0", "EPSG:3785", "image/jpeg")

        param(url, "SRS") shouldBe "EPSG:3785"
        param(url, "CRS") shouldBe null
        param(url, "VERSION") shouldBe "1.1.1"
        url.endsWith("&BBOX={bbox-epsg-3857}") shouldBe true
    }

    @Test
    fun `S036 RF-29 el nombre de la capa se codifica por entero`() {
        fun layer(name: String) = param(WmsRequests.tileTemplate(ign, "1.3.0", name, "EPSG:3857", "image/png"), "LAYERS")

        layer("Usos del suelo | Usos del sòl | Land use") shouldBe "Usos%20del%20suelo%20%7C%20Usos%20del%20s%C3%B2l%20%7C%20Land%20use"
        layer("con & ampersand") shouldBe "con%20%26%20ampersand"
        layer("a,b") shouldBe "a%2Cb"
        layer("100%") shouldBe "100%25"
        layer("OWS_0101_BCV05") shouldBe "OWS_0101_BCV05"
        layer("0") shouldBe "0"
        layer("😀") shouldBe "%F0%9F%98%80"
    }

    @Test
    fun `S036 RF-29 una imagen de la zona visible lleva el cuadro de pyproj a menos de un centimetro`() {
        for (box in WmsFixtures.boxes()) {
            val url = WmsRequests.image(ign, "1.3.0", "capa", "EPSG:3857", "image/png", box.south, box.west, box.north, box.east, 800, 600)

            val (x0, y0, x1, y1) = bbox(url)
            listOf(x0 - box.x0, y0 - box.y0, x1 - box.x1, y1 - box.y1).forEach { (kotlin.math.abs(it) < 0.01) shouldBe true }
        }
    }

    @Test
    fun `S036 RF-29 el cuadro lleva 3 decimales con punto aunque la interfaz este en otro idioma`() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        try {
            val url = WmsRequests.image(ign, "1.3.0", "capa", "EPSG:3857", "image/png", 39.98, -0.06, 39.99, -0.05, 800, 600)

            param(url, "BBOX") shouldBe Regex("-?\\d+\\.\\d{3},-?\\d+\\.\\d{3},-?\\d+\\.\\d{3},-?\\d+\\.\\d{3}").find(url)!!.value
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `S036 RF-29 el lado mayor de una imagen no pasa de 2048 px y la proporcion se mantiene`() {
        fun size(w: Int, h: Int): Pair<String?, String?> {
            val url = WmsRequests.image(ign, "1.3.0", "capa", "EPSG:3857", "image/png", 39.0, -1.0, 40.0, 0.0, w, h)
            return param(url, "WIDTH") to param(url, "HEIGHT")
        }

        size(4096, 2048) shouldBe ("2048" to "1024")
        size(1080, 4320) shouldBe ("512" to "2048")
        size(1080, 1920) shouldBe ("1080" to "1920")
        size(2048, 2048) shouldBe ("2048" to "2048")
    }

    @Test
    fun `S036 RF-29 una vista sin tamano no se pide`() {
        shouldThrow<IllegalArgumentException> {
            WmsRequests.image(ign, "1.3.0", "c", "EPSG:3857", "image/png", 39.0, -1.0, 40.0, 0.0, 0, 600)
        }
    }

    @Test
    fun `S036 RF-36 la consulta de 1_3_0 usa I y J y la de 1_1_1 X e Y`() {
        val v13 = info(version = "1.3.0", x = 400, y = 300)
        val v11 = info(version = "1.1.1", x = 400, y = 300)

        val names13 = listOf("I", "J", "X", "CRS", "SRS")
        names13.map { param(v13, it) } shouldBe listOf("400", "300", null, "EPSG:3857", null)
        val names11 = listOf("X", "Y", "I", "SRS", "CRS")
        names11.map { param(v11, it) } shouldBe listOf("400", "300", null, "EPSG:3857", null)
    }

    @Test
    fun `S036 RF-36 la consulta pide la capa, el formato, cinco entidades y el tamano de la vista`() {
        val url = info(layer = "Capa A", format = "application/vnd.ogc.gml", x = 10, y = 20)

        param(url, "REQUEST") shouldBe "GetFeatureInfo"
        param(url, "LAYERS") shouldBe "Capa%20A"
        param(url, "QUERY_LAYERS") shouldBe "Capa%20A"
        param(url, "INFO_FORMAT") shouldBe "application/vnd.ogc.gml"
        param(url, "FEATURE_COUNT") shouldBe "5"
        param(url, "WIDTH") shouldBe "800"
        param(url, "HEIGHT") shouldBe "600"
        param(url, "STYLES") shouldBe ""
    }

    @Test
    fun `S036 RF-36 el pixel se ajusta cuando la vista se reduce a 2048 y nunca sale de la imagen`() {
        fun pixel(x: Int, y: Int): Pair<String?, String?> {
            val url = info(width = 4096, height = 2048, x = x, y = y)
            return param(url, "I") to param(url, "J")
        }

        pixel(2048, 1024) shouldBe ("1024" to "512")
        pixel(4095, 2047) shouldBe ("2047" to "1023")
        pixel(0, 0) shouldBe ("0" to "0")
        pixel(9999, 9999) shouldBe ("2047" to "1023")
    }

    @Test
    fun `S036 RF-36 la consulta usa el mismo cuadro envolvente que la imagen de esa vista`() {
        val image = WmsRequests.image(ign, "1.3.0", "c", "EPSG:3857", "image/png", 39.98, -0.06, 39.99, -0.05, 800, 600)
        val query = info(x = 1, y = 1)

        bbox(query) shouldBe bbox(image)
    }
}
