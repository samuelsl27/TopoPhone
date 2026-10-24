package es.topophone.core.model.point

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El nombre de un punto replanteado: el del diseño con `_stkd`, `_stkd2`… (S018/RF-27). */
class StakeoutNamesTest {

    @Test
    fun `S018 RF-27 el primer replanteo de un punto se llama como el con _stkd`() {
        StakeoutNames.next("12", emptySet()) shouldBe "12_stkd"
        StakeoutNames.next("arqueta", setOf("arqueta", "1")) shouldBe "arqueta_stkd"
    }

    @Test
    fun `S018 RF-27 si ya existe, el siguiente libre es _stkd2, _stkd3 y los que sigan`() {
        StakeoutNames.next("12", setOf("12_stkd")) shouldBe "12_stkd2"
        StakeoutNames.next("12", setOf("12_stkd", "12_stkd2")) shouldBe "12_stkd3"
        StakeoutNames.next("12", setOf("12_stkd", "12_stkd3")) shouldBe "12_stkd2"
    }

    @Test
    fun `S018 RF-27 el nombre del diseno no cambia el de los demas replanteos`() {
        StakeoutNames.next("13", setOf("12_stkd")) shouldBe "13_stkd"
        StakeoutNames.next("12_stkd", setOf("12_stkd")) shouldBe "12_stkd_stkd"
    }

    @Test
    fun `S018 RF-27 el nombre del diseno se limpia de espacios`() {
        StakeoutNames.next("  12 ", emptySet()) shouldBe "12_stkd"
    }

    @Test
    fun `S019 RF-21 el numero de replanteo es 1 para _stkd y el del sufijo para los demas`() {
        StakeoutNames.number("12_stkd") shouldBe 1
        StakeoutNames.number("12_stkd2") shouldBe 2
        StakeoutNames.number("arqueta_stkd10") shouldBe 10
        StakeoutNames.number("12_stkd_stkd3") shouldBe 3
    }

    @Test
    fun `S019 RF-21 un nombre editado que no sigue el patron no tiene numero`() {
        StakeoutNames.number("12") shouldBe null
        StakeoutNames.number("12_stkd_viejo") shouldBe null
        StakeoutNames.number("stkd2") shouldBe null
        StakeoutNames.number("") shouldBe null
        StakeoutNames.number("12_stkd99999999999999999999") shouldBe null
    }

    @Test
    fun `S019 RF-21 el numero es el de next`() {
        val taken = mutableSetOf<String>()
        repeat(3) { index -> StakeoutNames.next("7", taken).also { taken += it }.let { StakeoutNames.number(it) shouldBe index + 1 } }
    }
}
