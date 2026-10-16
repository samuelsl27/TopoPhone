package es.topophone.core.model.ntrip

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Reglas del perfil NTRIP (S007/RF-2, RF-3). */
class NtripProfileRulesTest {

    private val valid = NtripProfileDraft(name = "ERGNSS (IGN): red", host = "ergnss-tr.ign.es")

    @Test
    fun `S007 RF-2 un perfil nuevo usa el puerto 2101 sin TLS ni usuario ni punto de montaje`() {
        with(NtripProfileDraft(name = "Mi base", host = "192.168.1.10")) {
            port shouldBe 2101
            useTls shouldBe false
            username shouldBe ""
            mountpoint shouldBe ""
        }
    }

    @Test
    fun `S007 RF-3 un perfil con nombre, servidor y puerto validos se puede guardar`() {
        NtripProfileRules.validate(valid, otherNames = listOf("CatNet (ICGC)")).shouldBeEmpty()
    }

    @Test
    fun `S007 RF-3 el nombre vacio no vale`() {
        NtripProfileRules.validate(valid.copy(name = "   "), emptyList()) shouldBe setOf(NtripProfileError.EMPTY_NAME)
    }

    @Test
    fun `S007 RF-3 el nombre de otro perfil no vale aunque cambien las mayusculas o las tildes`() {
        val others = listOf("Ergnss (IGN): RED", "Aragón")

        NtripProfileRules.validate(valid, others) shouldBe setOf(NtripProfileError.DUPLICATE_NAME)
        NtripProfileRules.validate(valid.copy(name = "aragon"), others) shouldBe setOf(NtripProfileError.DUPLICATE_NAME)
    }

    @Test
    fun `S007 RF-3 el servidor vacio no vale`() {
        NtripProfileRules.validate(valid.copy(host = " "), emptyList()) shouldBe setOf(NtripProfileError.EMPTY_HOST)
    }

    @Test
    fun `S007 RF-3 el puerto tiene que estar entre 1 y 65535`() {
        NtripProfileRules.validate(valid.copy(port = 0), emptyList()) shouldBe setOf(NtripProfileError.INVALID_PORT)
        NtripProfileRules.validate(valid.copy(port = 65536), emptyList()) shouldBe setOf(NtripProfileError.INVALID_PORT)
        NtripProfileRules.validate(valid.copy(port = 1), emptyList()).shouldBeEmpty()
        NtripProfileRules.validate(valid.copy(port = 65535), emptyList()).shouldBeEmpty()
    }

    @Test
    fun `S007 RF-3 se indican todos los motivos a la vez`() {
        val draft = NtripProfileDraft(name = "", host = "", port = -1)

        NtripProfileRules.validate(draft, emptyList()) shouldBe
            setOf(NtripProfileError.EMPTY_NAME, NtripProfileError.EMPTY_HOST, NtripProfileError.INVALID_PORT)
    }
}
