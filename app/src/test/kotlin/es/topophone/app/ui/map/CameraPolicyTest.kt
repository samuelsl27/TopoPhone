package es.topophone.app.ui.map

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.map.MapViewport
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Seguimiento del rover y encuadre al abrir (S009/RF-19 a RF-21). */
class CameraPolicyTest {

    private val madrid = GeoPoint(40.4168, -3.7037)
    private val nearby = GeoPoint(40.4169, -3.7036)
    private val zoom2000 = WebMercator.zoomForScale(2_000.0, madrid.latitudeDeg)
    private val spainView = MapCamera(40.0, -4.0, zoom = 5.0)

    @Test
    fun `S009 RF-21 al abrir sin posicion se ve el ultimo encuadre del proyecto o, la primera vez, España entera`() {
        val saved = MapViewport(40.4, -3.7, 16.0)

        CameraPolicy().onOpen(saved) shouldBe CameraCommand.MoveTo(saved)
        CameraPolicy().onOpen(null) shouldBe CameraCommand.ShowSpain
    }

    @Test
    fun `S009 RF-19 con la primera posicion se centra en el rover y se acerca a 1 2000 si estaba mas lejos`() {
        CameraPolicy().onPosition(madrid, spainView) shouldBe
            CameraCommand.MoveTo(MapViewport(madrid.latitudeDeg, madrid.longitudeDeg, zoom2000))
        // Más cerca que 1:2.000: se conserva el zoom.
        CameraPolicy().onPosition(madrid, spainView.copy(zoom = 19.0)) shouldBe
            CameraCommand.MoveTo(MapViewport(madrid.latitudeDeg, madrid.longitudeDeg, 19.0))
    }

    @Test
    fun `S009 RF-19 mientras se sigue al rover cada posicion mueve el centro sin cambiar el zoom`() {
        val policy = CameraPolicy()
        policy.onPosition(madrid, spainView)

        policy.onPosition(nearby, MapCamera(madrid.latitudeDeg, madrid.longitudeDeg, zoom = 18.2)) shouldBe
            CameraCommand.MoveTo(MapViewport(nearby.latitudeDeg, nearby.longitudeDeg, 18.2))
        policy.following shouldBe true
    }

    @Test
    fun `S009 RF-20 al mover el mapa con el dedo se deja de seguir hasta pulsar Centrar en el rover`() {
        val policy = CameraPolicy()
        policy.onPosition(madrid, spainView)

        policy.onUserGesture()

        policy.following shouldBe false
        policy.onPosition(nearby, MapCamera(40.5, -3.5, zoom = 12.0)).shouldBeNull()
        policy.onCenterPressed(nearby, MapCamera(40.5, -3.5, zoom = 12.0)) shouldBe
            CameraCommand.MoveTo(
                MapViewport(nearby.latitudeDeg, nearby.longitudeDeg, WebMercator.zoomForScale(2_000.0, nearby.latitudeDeg)),
            )
        policy.following shouldBe true
    }
}
