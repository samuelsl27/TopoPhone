package es.topophone.core.model.ntrip

import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** Casters públicos de España, comprobados el 16/10/2026 (S007/RF-11 a RF-13). */
class PublicCastersTest {

    private fun caster(id: String) = PublicCasters.ALL.single { it.id == id }

    @Test
    fun `S007 RF-11 la lista tiene el IGN y las redes autonomicas, sin repetidos`() {
        PublicCasters.ALL shouldHaveSize 21
        PublicCasters.ALL.map { it.id }.shouldBeUnique()
        PublicCasters.ALL.map { it.host to it.port }.shouldBeUnique()
        with(caster("ign_network")) {
            host shouldBe "ergnss-tr.ign.es"
            port shouldBe 2101
        }
        caster("ign_station").port shouldBe 2102
    }

    @Test
    fun `S007 RF-11 cada caster publico rellena servidor, puerto valido y TLS desactivado`() {
        PublicCasters.ALL.forEach { caster ->
            caster.host.isNotBlank() shouldBe true
            (caster.port in 1..65535) shouldBe true
            caster.useTls shouldBe false // ninguno ofrece TLS hoy
        }
    }

    @Test
    fun `S007 RF-12 cada caster publico tiene la direccion web donde se pide el usuario`() {
        PublicCasters.ALL.forEach { it.signupUrl shouldStartWith "https://" }
    }

    @Test
    fun `S007 RF-13 los de acceso libre traen el usuario y la contrasena publicos y los demas no`() {
        caster("rgapa").let { it.openUsername to it.openPassword } shouldBe ("anonimo" to "anonimo")
        caster("rgac").let { it.openUsername to it.openPassword } shouldBe ("anonimo" to "anonimo")
        caster("bizkaia").let { it.openUsername to it.openPassword } shouldBe ("anonimo" to "1234")
        PublicCasters.ALL.filter { it.access == CasterAccess.OPEN }.map { it.id }.toSet() shouldBe setOf("rgapa", "rgac", "bizkaia")
        PublicCasters.ALL.filter { it.access != CasterAccess.OPEN }.forEach {
            it.openUsername.shouldBeNull()
            it.openPassword.shouldBeNull()
        }
    }

    @Test
    fun `S007 RF-12 el acceso de cada red es el de su web`() {
        caster("grafcan").access shouldBe CasterAccess.PAID
        caster("rap_network").access shouldBe CasterAccess.REQUEST
        caster("regam").access shouldBe CasterAccess.REQUEST
        caster("larioja").access shouldBe CasterAccess.REGISTRATION
        caster("catnet").access shouldBe CasterAccess.FREE_REGISTRATION
    }
}
