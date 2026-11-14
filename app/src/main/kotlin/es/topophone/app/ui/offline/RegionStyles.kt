package es.topophone.app.ui.offline

import es.topophone.app.ui.map.MapStyles
import es.topophone.core.model.offline.DownloadableSource
import es.topophone.core.model.offline.SourceKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl

/**
 * El estilo de cada región sin conexión (S037/RF-4, RF-8, plan «RegionStyles»). El IGN y OpenFreeMap se descargan con el estilo que usa el mapa. La
 * ortofoto y cada capa WMS no tienen un estilo en un servidor: se generan con su plantilla de teselas, y como MapLibre 13.6.1 no lee estilos `file://`
 * (T1), el estilo va en una dirección de un dominio que no existe (`topophone.invalid`) que contesta el cliente HTTP del mapa. La plantilla va dentro
 * de la propia dirección, así que una región que se reanuda tras reiniciar la app no depende de nada que se haya perdido.
 */
object RegionStyles {
    const val HOST = "topophone.invalid"
    private const val PATH = "offline/style"
    private const val TILE_SIZE = 256
    private const val WMS_MAX_ZOOM = 22
    private const val MAX_ZOOM_LIMIT = 24
    private val TILE_SIZES = setOf(256, 512)

    /** La dirección del estilo de [source]; las capas WMS necesitan su plantilla de teselas ([wmsTemplate], de `WmsRequests.tileTemplate`). */
    fun styleUrl(source: DownloadableSource, wmsTemplate: String?): String = when (source.kind) {
        SourceKind.IGN_VECTOR -> MapStyles.IGN_STYLE_URL
        SourceKind.OPENFREEMAP -> MapStyles.OPENFREEMAP_STYLE_URL
        SourceKind.PNOA -> generated(MapStyles.PNOA_TILES, MapStyles.PNOA_MAX_ZOOM)
        SourceKind.WMS -> generated(requireNotNull(wmsTemplate) { "Una capa WMS necesita su plantilla de teselas" }, WMS_MAX_ZOOM)
    }

    fun isOurs(url: HttpUrl): Boolean = url.host == HOST

    /** El estilo JSON de una dirección nuestra, o null si no es una o le faltan datos o traen valores que no valen. */
    fun json(url: HttpUrl): String? {
        if (!isOurs(url)) return null
        val tiles = url.queryParameter("tiles")?.takeIf { it.isNotBlank() } ?: return null
        val size = url.queryParameter("size")?.toIntOrNull()?.takeIf { it in TILE_SIZES } ?: return null
        val maxZoom = url.queryParameter("maxzoom")?.toIntOrNull()?.takeIf { it in 0..MAX_ZOOM_LIMIT } ?: return null
        return buildJsonObject {
            put("version", 8)
            putJsonObject("sources") {
                putJsonObject("s") {
                    put("type", "raster")
                    putJsonArray("tiles") { add(JsonPrimitive(tiles)) }
                    put("tileSize", size)
                    put("minzoom", 0)
                    put("maxzoom", maxZoom)
                }
            }
            put(
                "layers",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "l")
                            put("type", "raster")
                            put("source", "s")
                        },
                    )
                },
            )
        }.toString()
    }

    private fun generated(tiles: String, maxZoom: Int): String = HttpUrl.Builder()
        .scheme("https")
        .host(HOST)
        .addPathSegments(PATH)
        .addQueryParameter("tiles", tiles)
        .addQueryParameter("size", TILE_SIZE.toString())
        .addQueryParameter("maxzoom", maxZoom.toString())
        .build()
        .toString()
}
