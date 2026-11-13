package es.topophone.app.ui.map

import es.topophone.core.formats.wms.WmsRequests
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Test

/** La pulsación larga del mapa: el punto, el píxel de la vista y la zona visible con su tamaño (S036/RF-34, A6 del plan). */
class MapPressTest {

    private val area = VisibleArea(south = 39.0, west = -0.5, north = 39.1, east = -0.4, widthPx = 1080, heightPx = 1800)

    @Test
    fun `S036 RF-34 la pulsacion lleva el punto sin redondear y la zona visible con su tamano`() {
        val press = MapPress.of(39.123456789, -0.987654321, 540f, 900f, area)

        press.latitudeDeg shouldBe 39.123456789
        press.longitudeDeg shouldBe -0.987654321
        press.area shouldBe area
        press.area.widthPx shouldBe 1080
        press.area.heightPx shouldBe 1800
    }

    @Test
    fun `S036 RF-34 el pixel se redondea al mas cercano`() {
        val press = MapPress.of(39.0, -0.4, 10.4f, 20.5f, area)

        press.pixelX shouldBe 10
        press.pixelY shouldBe 21
    }

    @Test
    fun `S036 RF-34 un pixel fuera de la vista se queda en el borde`() {
        MapPress.of(39.0, -0.4, -3f, -0.4f, area).let { (it.pixelX to it.pixelY) shouldBe (0 to 0) }
        MapPress.of(39.0, -0.4, 1080f, 1900.7f, area).let { (it.pixelX to it.pixelY) shouldBe (1079 to 1799) }
    }

    @Test
    fun `S036 RF-36 el pixel de la pulsacion es el I y J de la consulta de informacion`() {
        val address = (WmsAddress.parse("https://ejemplo.es/wms") as WmsAddressResult.Valid).address
        val press = MapPress.of(39.05, -0.45, 1080f, 300f, area)

        val url = WmsRequests.featureInfo(
            address,
            "1.3.0",
            "capa",
            "EPSG:3857",
            "text/plain",
            area.south,
            area.west,
            area.north,
            area.east,
            area.widthPx,
            area.heightPx,
            press.pixelX,
            press.pixelY,
        )

        url shouldContain "&I=1079&J=300"
    }
}
