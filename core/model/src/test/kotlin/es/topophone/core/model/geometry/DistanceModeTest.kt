package es.topophone.core.model.geometry

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Modo de distancia de la información geométrica (S026/RF-9). */
class DistanceModeTest {

    @Test
    fun `S026 RF-9 por defecto se usa la cuadricula y se reconoce por clave`() {
        DistanceMode.DEFAULT shouldBe DistanceMode.GRID
        DistanceMode.fromKey("GROUND") shouldBe DistanceMode.GROUND
        DistanceMode.fromKey("GRID") shouldBe DistanceMode.GRID
        DistanceMode.fromKey(null) shouldBe DistanceMode.GRID
        DistanceMode.fromKey("otra") shouldBe DistanceMode.GRID
    }
}
