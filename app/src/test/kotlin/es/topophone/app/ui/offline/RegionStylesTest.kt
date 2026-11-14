package es.topophone.app.ui.offline

import es.topophone.app.ui.map.MapStyles
import es.topophone.core.formats.wms.WmsRequests
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.SourceKind
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/** El estilo de cada región sin conexión (S037/RF-4, RF-8, RF-22): MapLibre no lee `file://`, así que va en una dirección que resuelve el cliente HTTP. */
class RegionStylesTest {

    private val pnoa = DownloadableSource(SourceKind.PNOA, "Ortofoto PNOA")
    private val layer = DownloadableSource(SourceKind.WMS, "Ortoimagen", serviceId = "s1", layerName = "OI.OrthoimageCoverage")

    private val template: String = run {
        val address = (WmsAddress.parse("https://www.ign.es/wms-inspire/pnoa-ma") as WmsAddressResult.Valid).address
        WmsRequests.tileTemplate(address, "1.3.0", "capa uno|x", "EPSG:3857", "image/jpeg")
    }

    private fun styleOf(url: String): JsonObject = Json.parseToJsonElement(RegionStyles.json(url.toHttpUrl())!!).jsonObject

    private fun tileSource(style: JsonObject) = style.getValue("sources").jsonObject.getValue("s").jsonObject

    @Test
    fun `S037 RF-8 el IGN y OpenFreeMap se descargan con el estilo que usa el mapa`() {
        RegionStyles.styleUrl(DownloadableSource(SourceKind.IGN_VECTOR, "Mapa IGN"), null) shouldBe MapStyles.IGN_STYLE_URL
        RegionStyles.styleUrl(DownloadableSource(SourceKind.OPENFREEMAP, "OSM"), null) shouldBe MapStyles.OPENFREEMAP_STYLE_URL
    }

    @Test
    fun `S037 RF-22 el estilo de la ortofoto lleva la plantilla y el ultimo nivel del mapa de S009`() {
        val source = tileSource(styleOf(RegionStyles.styleUrl(pnoa, null)))

        source.getValue("type").jsonPrimitive.content shouldBe "raster"
        source.getValue("tiles").jsonArray.single().jsonPrimitive.content shouldBe MapStyles.PNOA_TILES
        source.getValue("tileSize").jsonPrimitive.content shouldBe "256"
        source.getValue("maxzoom").jsonPrimitive.content shouldBe MapStyles.PNOA_MAX_ZOOM.toString()
    }

    @Test
    fun `S037 RF-22 el estilo de una capa WMS lleva la misma plantilla que pide el mapa vivo`() {
        val source = tileSource(styleOf(RegionStyles.styleUrl(layer, template)))

        source.getValue("tiles").jsonArray.single().jsonPrimitive.content shouldBe template
        source.getValue("tileSize").jsonPrimitive.content shouldBe "256"
        source.getValue("maxzoom").jsonPrimitive.content shouldBe "22"
    }

    @Test
    fun `S037 RF-8 el estilo tiene una sola capa raster sobre su fuente y es la version 8`() {
        val style = styleOf(RegionStyles.styleUrl(layer, template))

        style.getValue("version").jsonPrimitive.content shouldBe "8"
        val layers = style.getValue("layers").jsonArray
        layers.size shouldBe 1
        layers.single().jsonObject.getValue("type").jsonPrimitive.content shouldBe "raster"
        layers.single().jsonObject.getValue("source").jsonPrimitive.content shouldBe "s"
    }

    @Test
    fun `S037 RF-18 una misma fuente da siempre la misma direccion para que una region se reanude tras reiniciar la app`() {
        RegionStyles.styleUrl(layer, template) shouldBe RegionStyles.styleUrl(layer, template)
        (RegionStyles.styleUrl(layer, template) == RegionStyles.styleUrl(layer, template.replace("image/jpeg", "image/png"))) shouldBe false
    }

    @Test
    fun `S037 RF-8 la direccion es de un dominio que no existe y solo el cliente del mapa la contesta`() {
        val url = RegionStyles.styleUrl(pnoa, null).toHttpUrl()

        url.host shouldBe "topophone.invalid"
        RegionStyles.isOurs(url) shouldBe true
        RegionStyles.isOurs("https://www.ign.es/wmts/pnoa-ma".toHttpUrl()) shouldBe false
    }

    @Test
    fun `S037 RF-8 una direccion nuestra sin plantilla o con valores raros no da estilo`() {
        RegionStyles.json("https://topophone.invalid/offline/style".toHttpUrl()) shouldBe null
        RegionStyles.json("https://topophone.invalid/offline/style?tiles=&size=256&maxzoom=19".toHttpUrl()) shouldBe null
        RegionStyles.json("https://topophone.invalid/offline/style?tiles=http://a/%7Bz%7D&size=300&maxzoom=19".toHttpUrl()) shouldBe null
        RegionStyles.json("https://topophone.invalid/offline/style?tiles=http://a/%7Bz%7D&size=256&maxzoom=99".toHttpUrl()) shouldBe null
        RegionStyles.json("https://www.ign.es/wmts/pnoa-ma".toHttpUrl()) shouldBe null
    }

    @Test
    fun `S037 RF-8 una capa WMS sin plantilla no tiene estilo`() {
        shouldThrow<IllegalArgumentException> { RegionStyles.styleUrl(layer, null) }
    }
}
