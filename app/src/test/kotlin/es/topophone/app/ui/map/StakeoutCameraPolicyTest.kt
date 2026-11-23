package es.topophone.app.ui.map

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.WebMercator
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.cos
import org.junit.Test

/** La cámara que sigue al jalón en el replanteo, orientada por la marcha y con zoom automático (S060/RF-25, RF-26, RNF-T13). */
class StakeoutCameraPolicyTest {

    private val tip = GeoPoint(40.4168, -3.7038)

    /** Un punto a [metres] al norte de [tip]. */
    private fun north(metres: Double) = GeoPoint(tip.latitudeDeg + Math.toDegrees(metres / WebMercator.RADIUS_M), tip.longitudeDeg)

    private val shortSideDp = 360.0

    private fun visibleSideM(camera: FollowCamera) = WebMercator.metresPerPixel(camera.latitudeDeg, camera.zoom) * shortSideDp

    @Test
    fun `S060 RF-25 el mapa se centra en el jalon`() {
        val camera = StakeoutCameraPolicy().next(tip, north(40.0), null, shortSideDp, 0)!!

        camera.latitudeDeg shouldBe tip.latitudeDeg
        camera.longitudeDeg shouldBe tip.longitudeDeg
    }

    @Test
    fun `S060 RF-25 el zoom deja caber el destino con un margen del 25 por ciento`() {
        val camera = StakeoutCameraPolicy().next(tip, north(40.0), null, shortSideDp, 0)!!

        // El jalón va en el centro: el lado corto tiene que cubrir el doble de la distancia más el margen.
        visibleSideM(camera) shouldBeGreaterThanOrEqual 40.0 * 2 * 1.25 - 0.5
        visibleSideM(camera) shouldBeLessThanOrEqual 40.0 * 2 * 1.25 + 0.5
    }

    @Test
    fun `S060 RF-25 con el destino casi encima el zoom llega al maximo del mapa y nunca baja de 3 m de lado visible`() {
        val camera = StakeoutCameraPolicy().next(tip, north(0.3), null, shortSideDp, 0)!!

        // A 360 dp el máximo del mapa (zoom 22) ya enseña unos 5 m: ese es el tope real. Nunca se baja de 3 m de lado visible.
        visibleSideM(camera) shouldBeGreaterThanOrEqual 3.0 - 0.01
        camera.zoom shouldBe WebMercator.MAX_ZOOM
    }

    @Test
    fun `S060 RF-25 sin destino el lado visible es de unos 20 m`() {
        val camera = StakeoutCameraPolicy().next(tip, null, null, shortSideDp, 0)!!

        visibleSideM(camera) shouldBeGreaterThanOrEqual 19.5
        visibleSideM(camera) shouldBeLessThanOrEqual 20.5
    }

    @Test
    fun `S060 RF-25 el zoom solo cambia si la diferencia pasa de 0 3 niveles`() {
        val policy = StakeoutCameraPolicy()
        val first = policy.next(tip, north(40.0), null, shortSideDp, 0)!!

        // 40 m a 42 m son 0,07 niveles: no se mueve el zoom.
        val small = policy.next(tip, north(42.0), null, shortSideDp, 1_000)!!
        small.zoom shouldBe first.zoom

        // 40 m a 80 m es un nivel entero: sí.
        val big = policy.next(tip, north(80.0), null, shortSideDp, 2_000)!!
        abs(big.zoom - first.zoom) shouldBeGreaterThan 0.3
    }

    @Test
    fun `S060 RF-25 con rumbo el mapa se gira hacia la marcha y sin rumbo el norte queda arriba`() {
        val policy = StakeoutCameraPolicy()

        policy.next(tip, north(40.0), Math.toRadians(90.0), shortSideDp, 0)!!.bearingDeg shouldBe 90.0
        policy.next(tip, north(40.0), null, shortSideDp, 1_000)!!.bearingDeg shouldBe 0.0
    }

    @Test
    fun `S060 RF-25 el rumbo solo cambia si se mueve mas de 5 grados`() {
        val policy = StakeoutCameraPolicy()
        policy.next(tip, north(40.0), Math.toRadians(90.0), shortSideDp, 0)

        policy.next(tip, north(40.0), Math.toRadians(93.0), shortSideDp, 1_000)!!.bearingDeg shouldBe 90.0
        policy.next(tip, north(40.0), Math.toRadians(100.0), shortSideDp, 2_000)!!.bearingDeg shouldBe 100.0
    }

    @Test
    fun `S060 RF-25 el rumbo da la vuelta por el norte sin saltos`() {
        val policy = StakeoutCameraPolicy()
        policy.next(tip, north(40.0), Math.toRadians(359.0), shortSideDp, 0)

        // De 359° a 2° son 3°: dentro del umbral, no se gira.
        policy.next(tip, north(40.0), Math.toRadians(2.0), shortSideDp, 1_000)!!.bearingDeg shouldBe 359.0
    }

    @Test
    fun `S060 RNF-T13 como mucho 5 ordenes por segundo`() {
        val policy = StakeoutCameraPolicy()

        val orders = (0 until 50).count { policy.next(tip, north(40.0), null, shortSideDp, it * 20L) != null } // 50 Hz durante 1 s

        orders shouldBe 5
    }

    @Test
    fun `S060 RF-26 un gesto del usuario suelta el seguimiento y Recentrar lo recupera enseguida`() {
        val policy = StakeoutCameraPolicy()
        policy.next(tip, north(40.0), null, shortSideDp, 0).shouldNotBeNull()

        policy.onUserGesture()
        policy.following shouldBe false
        policy.next(tip, north(40.0), null, shortSideDp, 1_000).shouldBeNull()

        policy.recenter()
        policy.following shouldBe true
        // Recentrar no espera al ritmo de las órdenes: la siguiente posición ya mueve la cámara.
        policy.next(tip, north(40.0), null, shortSideDp, 1_010).shouldNotBeNull()
    }

    @Test
    fun `S060 RF-25 la latitud no cambia la cobertura del zoom`() {
        val spain = GeoPoint(28.1, -15.4) // Canarias
        val camera = StakeoutCameraPolicy().next(
            spain,
            GeoPoint(spain.latitudeDeg + Math.toDegrees(40.0 / WebMercator.RADIUS_M), spain.longitudeDeg),
            null,
            shortSideDp,
            0,
        )!!

        val side = WebMercator.metresPerPixel(camera.latitudeDeg, camera.zoom) * shortSideDp
        side shouldBeGreaterThanOrEqual 40.0 * 2 * 1.25 - 0.5 * cos(Math.toRadians(28.1))
    }

    @Test
    fun `S060 RF-25 al cerrar el replanteo se vuelve a empezar`() {
        val policy = StakeoutCameraPolicy()
        policy.next(tip, north(40.0), Math.toRadians(90.0), shortSideDp, 0)
        policy.onUserGesture()

        policy.reset()

        policy.following shouldBe true
        policy.next(tip, north(40.0), Math.toRadians(95.0), shortSideDp, 10)!!.bearingDeg shouldBe 95.0
    }
}
