package es.topophone.app.ui.web

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/** A qué direcciones llega la vista web: solo a la IP privada del receptor que se abrió (S043/RF-14, RNF-T4). */
class WebAddressPolicyTest {

    private val policy = WebAddressPolicy.forUrl("http://192.168.42.1:80/")!!

    @Test
    fun `S043 RF-14 la direccion del receptor y sus recursos se permiten, tambien en otro puerto de la misma IP`() {
        policy.allows("http://192.168.42.1/") shouldBe true
        policy.allows("http://192.168.42.1:80/panel/app.js") shouldBe true
        policy.allows("http://192.168.42.1:8080/ws?x=1#y") shouldBe true
    }

    @Test
    fun `S043 RF-14 una direccion publica se bloquea`() {
        policy.allows("http://8.8.8.8/") shouldBe false
        policy.allows("http://1.1.1.1:80/") shouldBe false
        policy.allows("https://192.168.42.1/") shouldBe false
    }

    @Test
    fun `S043 RF-14 otra IP privada, aunque sea de la misma red, y otro puerto de otra IP se bloquean`() {
        policy.allows("http://192.168.42.2/") shouldBe false
        policy.allows("http://10.0.0.1:80/") shouldBe false
        policy.allows("http://192.168.42.2:8080/") shouldBe false
        policy.allows("http://169.254.1.1/") shouldBe false
    }

    @Test
    fun `S043 RF-14 nombres de equipo, usuario en la direccion y esquemas que no son HTTP se bloquean`() {
        policy.allows("http://reach.local/") shouldBe false
        policy.allows("http://user@192.168.42.1/") shouldBe false
        policy.allows("http://192.168.42.1@8.8.8.8/") shouldBe false
        policy.allows("file:///sdcard/x") shouldBe false
        policy.allows("javascript:alert(1)") shouldBe false
        policy.allows("intent://x") shouldBe false
        policy.allows("") shouldBe false
    }

    @Test
    fun `S043 RF-14 solo se crea una politica para una direccion privada valida`() {
        WebAddressPolicy.forUrl("http://8.8.8.8/").shouldBeNull()
        WebAddressPolicy.forUrl("http://reach.local/").shouldBeNull()
        WebAddressPolicy.forUrl("http://10.1.2.3:8080/")!!.allows("http://10.1.2.3/") shouldBe true
    }
}
