package es.topophone.core.model.wms

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class WmsAddressTest {

    private fun valid(input: String): WmsAddress = WmsAddress.parse(input).shouldBeInstanceOf<WmsAddressResult.Valid>().address

    private fun invalid(input: String): WmsAddressProblem = WmsAddress.parse(input).shouldBeInstanceOf<WmsAddressResult.Invalid>().problem

    @Test
    fun `S036 RF-1 una direccion sin esquema se toma como HTTPS`() {
        val address = valid("ovc.catastro.meh.es/Cartografia/WMS/ServidorWMS.aspx")

        address.base shouldBe "https://ovc.catastro.meh.es/Cartografia/WMS/ServidorWMS.aspx"
        address.isHttps shouldBe true
    }

    @Test
    fun `S036 RF-1 los espacios de los extremos, el esquema y el servidor en mayusculas y el fragmento no cuentan`() {
        valid("  HTTPS://WWW.IGN.ES/wms-inspire/pnoa-ma#capas  \n").base shouldBe "https://www.ign.es/wms-inspire/pnoa-ma"
    }

    @Test
    fun `S036 RF-1 el camino conserva sus mayusculas`() {
        valid("https://Host.es/Servicio/WMS").base shouldBe "https://host.es/Servicio/WMS"
    }

    @Test
    fun `S036 RF-5 los parametros de la direccion se conservan en su orden`() {
        val address = valid("https://mapas.es/cgi-bin/mapserv?map=/datos/obra.map&Idioma=es")

        address.base shouldBe "https://mapas.es/cgi-bin/mapserv"
        address.parameters shouldBe listOf("map" to "/datos/obra.map", "Idioma" to "es")
    }

    @Test
    fun `S036 RF-5 los parametros estandar de una peticion pegada entera se quitan sin distinguir mayusculas`() {
        val address = valid(
            "https://x.es/wms?SERVICE=WMS&Request=GetCapabilities&version=1.3.0&map=a.map&LAYERS=a&bbox=1,2,3,4&CRS=EPSG:3857&FORMAT=image/png",
        )

        address.parameters shouldBe listOf("map" to "a.map")
    }

    @Test
    fun `S036 RF-5 un valor con igual, uno vacio y una clave repetida se conservan`() {
        valid("https://x.es/wms?a=b=c&vacio=&k=1&k=2").parameters shouldBe listOf("a" to "b=c", "vacio" to "", "k" to "1", "k" to "2")
    }

    @Test
    fun `S036 RF-5 un signo de interrogacion o un ampersand de sobra no dejan parametros`() {
        valid("https://x.es/wms?").parameters shouldBe emptyList()
        valid("https://x.es/wms?&&map=a&").parameters shouldBe listOf("map" to "a")
    }

    @Test
    fun `S036 RF-5 un parametro sin igual se conserva con valor vacio`() {
        valid("https://x.es/wms?soloclave").parameters shouldBe listOf("soloclave" to "")
    }

    @Test
    fun `S036 RF-15 dos direcciones equivalentes tienen la misma clave`() {
        val keys = listOf(
            "https://X.es:443/wms?map=a&b=1",
            "https://x.es/wms?b=1&map=a",
            "x.es/wms?SERVICE=WMS&REQUEST=GetCapabilities&map=a&b=1",
        ).map { valid(it).key }

        keys.toSet().size shouldBe 1
    }

    @Test
    fun `S036 RF-15 el puerto que no es el de omision, el camino o un parametro distinto dan otra clave`() {
        val base = valid("https://x.es/wms").key

        valid("https://x.es:8443/wms").key shouldBe valid("https://x.es:8443/wms").key
        (valid("https://x.es:8443/wms").key == base) shouldBe false
        (valid("https://x.es/wms2").key == base) shouldBe false
        (valid("https://x.es/wms?map=a").key == base) shouldBe false
        (valid("https://x.es/WMS").key == base) shouldBe false
    }

    @Test
    fun `S036 RF-10 una direccion HTTP publica se rechaza`() {
        invalid("http://ovc.catastro.meh.es/Cartografia/WMS/ServidorWMS.aspx") shouldBe WmsAddressProblem.PUBLIC_HTTP
        invalid("http://8.8.8.8/wms") shouldBe WmsAddressProblem.PUBLIC_HTTP
        invalid("http://172.15.0.1/wms") shouldBe WmsAddressProblem.PUBLIC_HTTP
        invalid("http://172.32.0.1/wms") shouldBe WmsAddressProblem.PUBLIC_HTTP
        invalid("http://192.169.0.1/wms") shouldBe WmsAddressProblem.PUBLIC_HTTP
        invalid("http://[2001:db8::1]/wms") shouldBe WmsAddressProblem.PUBLIC_HTTP
    }

    @Test
    fun `S036 RF-10 una direccion HTTP de una red privada o de enlace local se admite`() {
        listOf(
            "http://10.0.0.5/wms",
            "http://172.16.5.1/wms",
            "http://172.31.255.254/wms",
            "http://192.168.1.10:8080/wms",
            "http://169.254.1.1/wms",
            "http://127.0.0.1/wms",
            "http://localhost:8080/geoserver/wms",
            "http://servidor/wms",
            "http://wms.local/wms",
            "http://[::1]/wms",
            "http://[fd00::1]/wms",
            "http://[fe80::1]/wms",
        ).forEach { valid(it).isHttps shouldBe false }
    }

    @Test
    fun `S036 RF-10 HTTPS vale para cualquier servidor`() {
        valid("https://8.8.8.8/wms").isHttps shouldBe true
        valid("https://[2001:db8::1]/wms").isHttps shouldBe true
    }

    @Test
    fun `S036 RF-1 lo que no es una direccion se rechaza con su motivo`() {
        invalid("") shouldBe WmsAddressProblem.EMPTY
        invalid("   ") shouldBe WmsAddressProblem.EMPTY
        invalid("https://") shouldBe WmsAddressProblem.NOT_A_URL
        invalid("no es una direccion") shouldBe WmsAddressProblem.NOT_A_URL
        invalid("https://host con espacios/wms") shouldBe WmsAddressProblem.NOT_A_URL
        invalid("https://:8080/wms") shouldBe WmsAddressProblem.NOT_A_URL
        invalid("https://x.es:abc/wms") shouldBe WmsAddressProblem.NOT_A_URL
    }

    @Test
    fun `S036 RF-1 otro esquema distinto de HTTP y HTTPS se rechaza`() {
        invalid("ftp://x.es/wms") shouldBe WmsAddressProblem.UNSUPPORTED_SCHEME
        invalid("file:///datos/wms") shouldBe WmsAddressProblem.UNSUPPORTED_SCHEME
    }

    @Test
    fun `S036 fuera de alcance una direccion con usuario y contrasena se rechaza`() {
        invalid("https://usuario:clave@x.es/wms") shouldBe WmsAddressProblem.USER_INFO
        invalid("https://usuario@x.es/wms") shouldBe WmsAddressProblem.USER_INFO
    }

    @Test
    fun `S036 RF-5 un nombre de capa con barra vertical o espacio en el parametro no rompe la lectura`() {
        valid("https://x.es/wms?map=a b|c").parameters shouldBe listOf("map" to "a b|c")
    }
}
