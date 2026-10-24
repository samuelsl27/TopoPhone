package es.topophone.core.model.point

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El orden de "Siguiente" y "Anterior" en el replanteo: por nombre, con los números como números (S018/RF-3). */
class NaturalOrderTest {

    @Test
    fun `S018 RF-3 1, 2 y 10 van en ese orden y no 1, 10 y 2`() {
        listOf("10", "2", "1").sortedWith(NaturalOrder) shouldBe listOf("1", "2", "10")
    }

    @Test
    fun `S018 RF-3 un prefijo con numero se ordena por el prefijo y despues por el numero`() {
        listOf("P10", "P2", "A9", "P1").sortedWith(NaturalOrder) shouldBe listOf("A9", "P1", "P2", "P10")
    }

    @Test
    fun `S018 RF-3 las mayusculas no cambian el orden y los ceros a la izquierda no cuentan`() {
        listOf("b2", "A1", "a10", "B1").sortedWith(NaturalOrder) shouldBe listOf("A1", "a10", "B1", "b2")
        listOf("P010", "P9", "P011").sortedWith(NaturalOrder) shouldBe listOf("P9", "P010", "P011")
    }

    @Test
    fun `S018 RF-3 los replanteados van detras de su diseno`() {
        listOf("12_stkd2", "12", "12_stkd", "2").sortedWith(NaturalOrder) shouldBe listOf("2", "12", "12_stkd", "12_stkd2")
    }

    @Test
    fun `S018 RF-3 dos nombres iguales salvo las mayusculas se distinguen siempre`() {
        (NaturalOrder.compare("a1", "A1") > 0) shouldBe true
        (NaturalOrder.compare("A1", "a1") < 0) shouldBe true
        NaturalOrder.compare("x", "x") shouldBe 0
    }

    @Test
    fun `S018 RF-3 un numero enorme no desborda`() {
        listOf("P99999999999999999999999", "P2").sortedWith(NaturalOrder) shouldBe listOf("P2", "P99999999999999999999999")
    }
}
