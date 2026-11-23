package es.topophone.app.ui.map

import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * El contrato de `follow` y `resetBearing` del mapa (S060/RF-25). MapLibre no funciona en Robolectric (código nativo): la transición y el
 * hueco de los paneles se ven en la demo del emulador y los tests de la interfaz usan este doble.
 */
class MapControllerFollowTest {

    @Test
    fun `S060 RF-25 el mapa recibe el centro el zoom y el rumbo del seguimiento`() {
        val map: MapController = FakeMapController()

        map.follow(FollowCamera(40.4168, -3.7038, 20.5, 135.0))

        (map as FakeMapController).follows shouldBe listOf(FollowCamera(40.4168, -3.7038, 20.5, 135.0))
    }

    @Test
    fun `S060 RF-25 al cerrar el replanteo el mapa vuelve a tener el norte arriba`() {
        val map = FakeMapController()

        map.resetBearing()

        map.bearingResets shouldBe 1
    }

    @Test
    fun `S060 RF-25 el mapa dice el lado corto de lo que se ve para calcular el zoom`() {
        val map = FakeMapController()
        map.visibleShortSideDp = 300.0

        (map as MapController).visibleShortSideDp shouldBe 300.0
    }
}
