package es.topophone.app.ui.map

import es.topophone.core.model.map.Basemap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody

/** Estilo de un mapa base: de su servicio o construido en la app (S009/RF-6). */
sealed interface BasemapStyle {
    data class Remote(val url: String) : BasemapStyle

    data class Local(val json: String) : BasemapStyle
}

/**
 * Mapas base del plan de S009 ("Fuentes de mapas"). Debajo de todos ellos, la app pone un fondo neutro con
 * cuadrícula, que se ve donde falta una tesela (S009/RF-14).
 */
object MapStyles {
    const val IGN_STYLE_URL = "https://vt-mapabase.idee.es/files/styles/mapaBase_scn_color1_CNIG.json"
    private const val OPENFREEMAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    private const val PNOA_TILES = "https://www.ign.es/wmts/pnoa-ma?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
        "&LAYER=OI.OrthoimageCoverage&STYLE=default&TILEMATRIXSET=GoogleMapsCompatible&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}" +
        "&FORMAT=image/jpeg"

    /** Teselas del PNOA generadas: por encima, MapLibre amplía las del nivel 19. */
    private const val PNOA_MAX_ZOOM = 19

    /** Glifos del IGN para los nombres de los puntos en los estilos locales; la fuente la sirven el IGN y OpenFreeMap (S010/RF-20). */
    const val IGN_GLYPHS = "https://vt-mapabase.idee.es/files/styles/fonts/pbf/{fontstack}/{range}.pbf"
    const val LABEL_FONT = "Noto Sans Regular"

    /** Fondo neutro y líneas de la cuadrícula (S009/RF-14). */
    const val BACKGROUND_COLOR = "#E4E6E8"
    const val GRID_COLOR = "#A9B0B6"

    fun style(basemap: Basemap): BasemapStyle = when (basemap) {
        Basemap.IGN_VECTOR -> BasemapStyle.Remote(IGN_STYLE_URL)
        Basemap.OSM_VECTOR -> BasemapStyle.Remote(OPENFREEMAP_STYLE_URL)
        Basemap.PNOA_ORTHOPHOTO -> BasemapStyle.Local(
            localStyle {
                putJsonObject("pnoa") {
                    put("type", "raster")
                    putJsonArray("tiles") { add(JsonPrimitive(PNOA_TILES)) }
                    put("tileSize", 256)
                    put("maxzoom", PNOA_MAX_ZOOM)
                }
            },
        )
        Basemap.NONE -> BasemapStyle.Local(localStyle {})
    }

    /** Estilo con las fuentes ráster dadas, cada una con su capa. */
    private fun localStyle(sources: JsonObjectBuilder.() -> Unit): String {
        val sourceObject = buildJsonObject(sources)
        val layers = buildJsonArray {
            sourceObject.keys.forEach { id ->
                add(
                    buildJsonObject {
                        put("id", id)
                        put("type", "raster")
                        put("source", id)
                    },
                )
            }
        }
        return buildJsonObject {
            put("version", 8)
            put("glyphs", IGN_GLYPHS)
            put("sources", sourceObject)
            put("layers", layers)
        }.toString()
    }
}

/**
 * Los estilos del IGN escriben `minZoom`/`maxZoom` en las fuentes, que MapLibre no lee: pediría teselas por
 * encima del nivel 17 (respuesta 403) y el mapa se quedaría en blanco al acercarse. Se corrigen en la
 * respuesta, antes de que MapLibre la guarde en su caché (S009/RF-6).
 */
object IgnStylePatch {
    private const val HOST = "vt-mapabase.idee.es"
    private const val VECTOR_MAX_ZOOM = 17

    val interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val url = chain.request().url
        if (url.host != HOST || !url.encodedPath.endsWith(".json") || !response.isSuccessful) return@Interceptor response
        val body = response.body ?: return@Interceptor response
        val contentType = body.contentType()
        response.newBuilder().body(patch(body.string()).toResponseBody(contentType)).build()
    }

    fun patch(json: String): String {
        val style = Json.parseToJsonElement(json).jsonObject
        val sources = style["sources"]?.jsonObject ?: return json
        val patchedSources = JsonObject(sources.mapValues { (_, source) -> patchSource(source.jsonObject) })
        return JsonObject(style + ("sources" to patchedSources)).toString()
    }

    private fun patchSource(source: JsonObject): JsonElement {
        val fixed = source.entries.associate { (key, value) ->
            when (key) {
                "minZoom" -> "minzoom" to value
                "maxZoom" -> "maxzoom" to value
                else -> key to value
            }
        }.toMutableMap()
        val isIgnVector = fixed["type"] == JsonPrimitive("vector") && fixed["tiles"].toString().contains(HOST)
        if (isIgnVector && "maxzoom" !in fixed) fixed["maxzoom"] = JsonPrimitive(VECTOR_MAX_ZOOM)
        return JsonObject(fixed)
    }
}
