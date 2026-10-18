package es.topophone.core.model.point

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Nombre propuesto al añadir o duplicar un punto (S011/RF-16, RF-21). */
class PointNamesTest {

    @Test
    fun `S011 RF-16 el nombre propuesto sigue al ultimo punto y salta los ocupados`() {
        PointNames.nextFree("P9", setOf("P9", "P10", "P11")) shouldBe "P12"
        PointNames.nextFree(null, emptySet()) shouldBe "1"
        PointNames.nextFree(null, setOf("1", "2")) shouldBe "3"
        PointNames.nextFree("P009", setOf("P010")) shouldBe "P011"
    }

    @Test
    fun `S011 RF-21 la copia de un punto sin numero toma el siguiente nombre libre`() {
        PointNames.nextFree("arqueta", setOf("arqueta", "arqueta1")) shouldBe "arqueta2"
    }
}
