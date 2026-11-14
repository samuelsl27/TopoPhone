package es.topophone.core.model.offline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DetailLevelTest {

    @Test
    fun `S037 RF-8 hay tres niveles de detalle y Normal es 1 a 5000 y Alto 1 a 1000`() {
        DetailLevel.entries.toList() shouldBe listOf(DetailLevel.NORMAL, DetailLevel.HIGH, DetailLevel.MAX)
        DetailLevel.NORMAL.scaleDenominator shouldBe 5_000.0
        DetailLevel.HIGH.scaleDenominator shouldBe 1_000.0
    }

    @Test
    fun `S037 RF-8 el nivel Maximo no tiene escala propia y llega hasta el zoom maximo del mapa`() {
        DetailLevel.MAX.scaleDenominator shouldBe null
    }

    @Test
    fun `S037 RF-8 cada nivel se guarda con una clave estable y se recupera con ella`() {
        DetailLevel.entries.forEach { DetailLevel.fromKey(it.key) shouldBe it }
        DetailLevel.entries.map { it.key } shouldBe listOf("normal", "high", "max")
        DetailLevel.fromKey("otro") shouldBe null
    }
}
