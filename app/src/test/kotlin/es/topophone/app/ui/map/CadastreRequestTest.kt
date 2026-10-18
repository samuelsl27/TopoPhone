package es.topophone.app.ui.map

import es.topophone.core.geodesy.WebMercator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Test

/** El Catastro como una sola imagen GetMap del encuadre, sin teselas (S009/RF-7 y RNF de la spec). */
class CadastreRequestTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    private val south = 40.4140
    private val west = -3.7060
    private val north = 40.4194
    private val east = -3.7013

    @Test
    fun `S009 RF-7 el Catastro se pide como una imagen GetMap de la zona visible en EPSG 3857`() {
        val url = CadastreRequest.url(south, west, north, east, widthPx = 1080, heightPx = 1500).toHttpUrl()

        url.toString() shouldStartWith "https://ovc.catastro.meh.es/cartografia/INSPIRE/spadgcwms.aspx?"
        url.queryParameter("SERVICE") shouldBe "WMS"
        url.queryParameter("VERSION") shouldBe "1.3.0"
        url.queryParameter("REQUEST") shouldBe "GetMap"
        url.queryParameter("LAYERS") shouldBe "CP.CadastralParcel,BU.Building"
        url.queryParameter("CRS") shouldBe "EPSG:3857"
        url.queryParameter("FORMAT") shouldBe "image/png"
        url.queryParameter("TRANSPARENT") shouldBe "TRUE"
        url.queryParameter("WIDTH") shouldBe "1080"
        url.queryParameter("HEIGHT") shouldBe "1500"
        val (x0, y0) = WebMercator.forward(south, west)
        val (x1, y1) = WebMercator.forward(north, east)
        url.queryParameter("BBOX") shouldBe "%.3f,%.3f,%.3f,%.3f".format(Locale.ROOT, x0, y0, x1, y1)
    }

    @Test
    fun `S009 RF-7 la imagen no pasa de 2048 px por lado y conserva la proporcion`() {
        val url = CadastreRequest.url(south, west, north, east, widthPx = 3000, heightPx = 1500).toHttpUrl()

        url.queryParameter("WIDTH") shouldBe "2048"
        url.queryParameter("HEIGHT") shouldBe "1024"
    }

    @Test
    fun `S009 RF-7 los numeros van con punto aunque el movil este en español`() {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))

        val bbox = CadastreRequest.url(south, west, north, east, widthPx = 1080, heightPx = 1500).toHttpUrl().queryParameter("BBOX")!!

        // Con coma decimal saldrían más de cuatro números.
        bbox.split(',').map { it.toDouble() }.size shouldBe 4
    }
}
