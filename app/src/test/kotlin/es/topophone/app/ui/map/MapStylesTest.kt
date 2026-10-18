package es.topophone.app.ui.map

import es.topophone.core.model.map.Basemap
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/** Estilo de cada mapa base y parche del estilo vectorial del IGN (S009/RF-6, RF-14). */
class MapStylesTest {

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `S009 RF-6 los mapas vectoriales del IGN y de OpenStreetMap se cargan de sus servicios`() {
        MapStyles.style(Basemap.IGN_VECTOR) shouldBe BasemapStyle.Remote(MapStyles.IGN_STYLE_URL)
        MapStyles.style(Basemap.OSM_VECTOR) shouldBe BasemapStyle.Remote("https://tiles.openfreemap.org/styles/liberty")
    }

    @Test
    fun `S009 RF-6 la ortofoto PNOA son teselas del WMTS del IGN hasta el nivel 19`() {
        val style = parse(MapStyles.style(Basemap.PNOA_ORTHOPHOTO).shouldBeInstanceOf<BasemapStyle.Local>().json)

        style["version"]!!.jsonPrimitive.int shouldBe 8
        val source = style["sources"]!!.jsonObject["pnoa"]!!.jsonObject
        source["type"]!!.jsonPrimitive.content shouldBe "raster"
        source["tileSize"]!!.jsonPrimitive.int shouldBe 256
        source["maxzoom"]!!.jsonPrimitive.int shouldBe 19
        source["tiles"]!!.jsonArray.single().jsonPrimitive.content shouldBe
            "https://www.ign.es/wmts/pnoa-ma?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=OI.OrthoimageCoverage&STYLE=default" +
            "&TILEMATRIXSET=GoogleMapsCompatible&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/jpeg"
        style["layers"]!!.jsonArray.single().jsonObject["source"]!!.jsonPrimitive.content shouldBe "pnoa"
    }

    @Test
    fun `S009 RF-6 y RF-14 sin mapa base el estilo esta vacio y solo queda el fondo neutro con la cuadricula`() {
        val style = parse(MapStyles.style(Basemap.NONE).shouldBeInstanceOf<BasemapStyle.Local>().json)

        style["sources"]!!.jsonObject.size shouldBe 0
        style["layers"]!!.jsonArray.size shouldBe 0
    }

    @Test
    fun `S009 RF-6 el estilo del IGN declara el zoom maximo de sus teselas en minusculas, que es lo que lee MapLibre`() {
        val original = """
            {"version":8,"sources":{
              "mapaBaseXYZ":{"type":"vector","tiles":["https://vt-mapabase.idee.es/1.0.0/mapabase/{z}/{x}/{y}.pbf"],"minZoom":0,"maxZoom":17},
              "relieve":{"type":"raster-dem","tiles":["https://xyz-mdt.idee.es/1.0.0/raster-dem/{z}/{x}/{y}.png"],"minZoom":5,"maxZoom":15}},
             "layers":[{"id":"fondo","type":"background","minzoom":0,"maxzoom":24}]}
        """.trimIndent()

        val patched = parse(IgnStylePatch.patch(original))

        val base = patched["sources"]!!.jsonObject["mapaBaseXYZ"]!!.jsonObject
        base["maxzoom"]!!.jsonPrimitive.int shouldBe 17
        base["minzoom"]!!.jsonPrimitive.int shouldBe 0
        base.containsKey("maxZoom") shouldBe false
        patched["sources"]!!.jsonObject["relieve"]!!.jsonObject["maxzoom"]!!.jsonPrimitive.int shouldBe 15
        patched["layers"]!!.jsonArray.single().jsonObject["maxzoom"]!!.jsonPrimitive.int shouldBe 24
    }

    @Test
    fun `S009 RF-6 si el IGN no dice el zoom maximo de sus teselas vectoriales se pone 17`() {
        val tiles = "https://vt-mapabase.idee.es/1.0.0/mapabase/{z}/{x}/{y}.pbf"
        val original = """{"version":8,"sources":{"mapaBaseXYZ":{"type":"vector","tiles":["$tiles"]}},"layers":[]}"""

        parse(IgnStylePatch.patch(original))["sources"]!!.jsonObject["mapaBaseXYZ"]!!.jsonObject["maxzoom"]!!.jsonPrimitive.int shouldBe 17
    }

    @Test
    fun `S009 RF-6 el cliente del mapa corrige solo la respuesta del estilo del IGN`() {
        val style = """{"version":8,"sources":{"s":{"type":"vector","tiles":["https://vt-mapabase.idee.es/x"],"maxZoom":17}},"layers":[]}"""
        val client = MapHttpClient.create(versionName = "1.0.0").newBuilder()
            .addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(style.toResponseBody()).build()
            }
            .build()
        fun fetch(url: String) = client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }

        parse(fetch(MapStyles.IGN_STYLE_URL))["sources"]!!.jsonObject["s"]!!.jsonObject.containsKey("maxzoom") shouldBe true
        fetch("https://tiles.openfreemap.org/styles/liberty") shouldBe style
    }

    @Test
    fun `S010 RF-20 los estilos locales llevan los glifos del IGN para los nombres de los puntos`() {
        listOf(Basemap.PNOA_ORTHOPHOTO, Basemap.NONE).forEach { basemap ->
            val style = parse(MapStyles.style(basemap).shouldBeInstanceOf<BasemapStyle.Local>().json)
            style["glyphs"]!!.jsonPrimitive.content shouldBe "https://vt-mapabase.idee.es/files/styles/fonts/pbf/{fontstack}/{range}.pbf"
        }
        MapStyles.LABEL_FONT shouldBe "Noto Sans Regular"
    }
}
