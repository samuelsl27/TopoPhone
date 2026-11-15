package es.topophone.core.model.guide

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Las direcciones que puede cargar la interfaz web del receptor: solo IP privadas o de enlace local (S043/RF-14, RNF-T4). */
class PrivateAddressTest {

    private fun accepts(text: String) = PrivateAddress.parse(text) != null

    @Test
    fun `S043 RF-14 cada rango privado acepta sus bordes y rechaza lo de al lado`() {
        // 10.0.0.0/8
        accepts("10.0.0.0") shouldBe true
        accepts("10.255.255.255") shouldBe true
        accepts("9.255.255.255") shouldBe false
        accepts("11.0.0.0") shouldBe false
        // 172.16.0.0/12
        accepts("172.15.255.255") shouldBe false
        accepts("172.16.0.0") shouldBe true
        accepts("172.31.255.255") shouldBe true
        accepts("172.32.0.0") shouldBe false
        // 192.168.0.0/16
        accepts("192.167.255.255") shouldBe false
        accepts("192.168.0.0") shouldBe true
        accepts("192.168.255.255") shouldBe true
        accepts("192.169.0.0") shouldBe false
        // enlace local 169.254.0.0/16
        accepts("169.253.255.255") shouldBe false
        accepts("169.254.0.0") shouldBe true
        accepts("169.254.255.255") shouldBe true
        accepts("169.255.0.0") shouldBe false
    }

    @Test
    fun `S043 RF-14 la direccion de fabrica del receptor es valida`() {
        PrivateAddress.parse("192.168.42.1")?.host shouldBe "192.168.42.1"
        PrivateAddress.parse("192.168.42.1")?.port.shouldBeNull()
    }

    @Test
    fun `S043 RF-14 el bucle local, las publicas, los nombres y las IPv6 no se aceptan`() {
        val rejected = listOf("127.0.0.1", "8.8.8.8", "1.1.1.1", "reach.local", "localhost", "example.com", "::1", "fe80::1", "fd00::1")
        for (text in rejected) accepts(text) shouldBe false
    }

    @Test
    fun `S043 RF-14 lo que no es una IP de cuatro numeros no se acepta`() {
        val rejected = listOf("", " ", "192.168.1", "192.168.1.1.1", "192.168.1.256", "192.168.1.-1", "192.168.1.a", "192..1.1")
        for (text in rejected + "192.168.1.1.") accepts(text) shouldBe false
    }

    @Test
    fun `S043 RF-14 los ceros a la izquierda se rechazan porque son ambiguos`() {
        accepts("192.168.01.1") shouldBe false
        accepts("010.0.0.1") shouldBe false
        accepts("192.168.0.1") shouldBe true
    }

    @Test
    fun `S043 RF-14 los espacios de los lados se ignoran y los de en medio no`() {
        PrivateAddress.parse("  192.168.42.1  ")?.host shouldBe "192.168.42.1"
        accepts("192.168. 42.1") shouldBe false
    }

    @Test
    fun `S043 RF-12 el puerto es opcional y va de 1 a 65535`() {
        PrivateAddress.parse("192.168.42.1:80")?.port shouldBe 80
        PrivateAddress.parse("192.168.42.1:1")?.port shouldBe 1
        PrivateAddress.parse("192.168.42.1:65535")?.port shouldBe 65535
        for (port in listOf("0", "65536", "", "ab", "-1", "80:81")) accepts("192.168.42.1:$port") shouldBe false
    }

    @Test
    fun `S043 RF-12 la URL que se carga es http con la IP y el puerto dados o el 80`() {
        PrivateAddress.parse("192.168.42.1")?.url shouldBe "http://192.168.42.1/"
        PrivateAddress.parse("192.168.42.1:8080")?.url shouldBe "http://192.168.42.1:8080/"
        PrivateAddress("10.0.0.5", null).url shouldBe "http://10.0.0.5/"
    }

    @Test
    fun `S043 RF-14 una URL pedida por la pagina solo vale si es http a una IP privada`() {
        PrivateAddress.allowsUrl("http://192.168.42.1/app.js") shouldBe true
        PrivateAddress.allowsUrl("http://192.168.42.1:8080/ws") shouldBe true
        PrivateAddress.allowsUrl("http://169.254.3.4/x") shouldBe true
        PrivateAddress.allowsUrl("https://192.168.42.1/") shouldBe false // el receptor no sirve HTTPS: no se sigue
        PrivateAddress.allowsUrl("http://example.com/") shouldBe false
        PrivateAddress.allowsUrl("http://8.8.8.8/") shouldBe false
        PrivateAddress.allowsUrl("http://reach.local/") shouldBe false
        PrivateAddress.allowsUrl("file:///sdcard/x.html") shouldBe false
        PrivateAddress.allowsUrl("javascript:alert(1)") shouldBe false
        PrivateAddress.allowsUrl("http://192.168.42.1@evil.com/") shouldBe false // usuario en la URL: el host real es otro
        PrivateAddress.allowsUrl("") shouldBe false
    }
}
