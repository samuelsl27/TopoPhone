package es.topophone.core.formats.wms

import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsLimits
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Las URL de las peticiones a un servicio WMS (S036/RF-1, RF-5, RF-17, RF-29, RF-36). Todas empiezan por la dirección del servicio y los
 * parámetros que ya traía (`map=…`, RF-5), a los que se añaden los de la petición; los estándar que traiga la dirección se quitaron al
 * normalizarla ([WmsAddress]), así que no se repite ninguno. Los números van con `Locale.ROOT`, nunca con la configuración regional de la
 * interfaz.
 *
 * `version` es la que declara el servicio (RF-2): `1.3.0` usa `CRS`, `I` y `J`; `1.1.1`, `SRS`, `X` e `Y`. El eje de Web Mercator es siempre
 * este y norte, así que el cuadro envolvente no se invierte en ninguna versión.
 */
object WmsRequests {
    /** Lado de una tesela que se pide al servicio. */
    const val TILE_PX = 256

    /** Lado máximo de la imagen de una capa en «una imagen» y de la zona visible de una consulta, como el Catastro de S009. */
    const val MAX_SIDE_PX = 2048

    /** La plantilla que MapLibre rellena con el cuadro envolvente de cada tesela en EPSG:3857. */
    const val BBOX_PLACEHOLDER = "{bbox-epsg-3857}"

    private const val UNRESERVED = "-._~"
    private const val LOOSE_KEPT = "-._~/:,"

    /** El GetCapabilities en la versión [version] (RF-1). */
    fun capabilities(address: WmsAddress, version: String): String =
        url(address, "SERVICE=WMS", "REQUEST=GetCapabilities", "VERSION=$version")

    /** La plantilla de teselas de una capa para una fuente de MapLibre (RF-29); [code] es el código de Web Mercator que anuncia la capa. */
    fun tileTemplate(address: WmsAddress, version: String, layer: String, code: String, format: String): String =
        getMap(address, version, layer, code, format, TILE_PX, TILE_PX, BBOX_PLACEHOLDER)

    /**
     * Una sola imagen de la zona visible, de [south], [west], [north] y [east] en grados, para una vista de [widthPx] por [heightPx] píxeles
     * (RF-17, RF-29). El lado mayor no pasa de [MAX_SIDE_PX] y la caja va en EPSG:3857 con 3 decimales.
     */
    fun image(
        address: WmsAddress,
        version: String,
        layer: String,
        code: String,
        format: String,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        widthPx: Int,
        heightPx: Int,
    ): String {
        val size = fit(widthPx, heightPx)
        return getMap(address, version, layer, code, format, size.first, size.second, bbox(south, west, north, east))
    }

    /**
     * La consulta de información de una capa en el píxel ([pixelX], [pixelY]) de una vista de [widthPx] por [heightPx] píxeles que cubre
     * [south], [west], [north] y [east] (RF-36); [format] es el formato de la respuesta. El tamaño y el píxel se ajustan igual que en [image].
     */
    fun featureInfo(
        address: WmsAddress,
        version: String,
        layer: String,
        code: String,
        format: String,
        south: Double,
        west: Double,
        north: Double,
        east: Double,
        widthPx: Int,
        heightPx: Int,
        pixelX: Int,
        pixelY: Int,
    ): String {
        val size = fit(widthPx, heightPx)
        val scale = size.first.toDouble() / widthPx
        val i = (pixelX * scale).roundToInt().coerceIn(0, size.first - 1)
        val j = (pixelY * scale).roundToInt().coerceIn(0, size.second - 1)
        val (columnName, rowName) = if (version == VERSION_1_3_0) "I" to "J" else "X" to "Y"
        val encoded = component(layer)
        return url(
            address,
            "SERVICE=WMS",
            "VERSION=$version",
            "REQUEST=GetFeatureInfo",
            "LAYERS=$encoded",
            "QUERY_LAYERS=$encoded",
            "STYLES=",
            "${crsName(version)}=$code",
            "BBOX=${bbox(south, west, north, east)}",
            "WIDTH=${size.first}",
            "HEIGHT=${size.second}",
            "INFO_FORMAT=${loose(format)}",
            "FEATURE_COUNT=${WmsLimits.FEATURE_COUNT}",
            "$columnName=$i",
            "$rowName=$j",
        )
    }

    private fun getMap(
        address: WmsAddress,
        version: String,
        layer: String,
        code: String,
        format: String,
        width: Int,
        height: Int,
        bbox: String,
    ) = url(
        address,
        "SERVICE=WMS",
        "VERSION=$version",
        "REQUEST=GetMap",
        "LAYERS=${component(layer)}",
        "STYLES=",
        "FORMAT=${loose(format)}",
        "TRANSPARENT=TRUE",
        "WIDTH=$width",
        "HEIGHT=$height",
        "${crsName(version)}=$code",
        "BBOX=$bbox",
    )

    private const val VERSION_1_3_0 = "1.3.0"

    private fun crsName(version: String) = if (version == VERSION_1_3_0) "CRS" else "SRS"

    private fun url(address: WmsAddress, vararg own: String): String {
        val fromAddress = address.parameters.map { (name, value) -> if (value.isEmpty()) loose(name) else "${loose(name)}=${loose(value)}" }
        return address.base + "?" + (fromAddress + own).joinToString("&")
    }

    /** El cuadro envolvente en EPSG:3857 de una zona en grados, con 3 decimales y Locale.ROOT. */
    private fun bbox(south: Double, west: Double, north: Double, east: Double): String {
        val (x0, y0) = WebMercator.forward(south, west)
        val (x1, y1) = WebMercator.forward(north, east)
        return "%.3f,%.3f,%.3f,%.3f".format(Locale.ROOT, x0, y0, x1, y1)
    }

    /** El tamaño [width] por [height] con el lado mayor reducido a [MAX_SIDE_PX] si lo pasa, sin cambiar la proporción. */
    private fun fit(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "La vista no tiene tamaño: $width x $height" }
        val scale = minOf(1.0, MAX_SIDE_PX.toDouble() / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Un valor que va entero en un parámetro, como un nombre de capa: todo lo que no es letra, cifra o `-._~` se codifica en UTF-8. */
    private fun component(text: String): String = encode(text, UNRESERVED, keepEscapes = false)

    /** Un valor que puede llevar `/`, `:` y `,` sin codificar (un formato, un código o un parámetro de la dirección); un `%HH` ya escrito se respeta. */
    private fun loose(text: String): String = encode(text, LOOSE_KEPT, keepEscapes = true)

    private fun encode(text: String, kept: String, keepEscapes: Boolean): String {
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isAsciiLetterOrDigit() || c in kept -> out.append(c)
                keepEscapes && c == '%' && i + 2 < text.length && text[i + 1].isHexDigit() && text[i + 2].isHexDigit() -> out.append(c)
                else -> {
                    val end = if (c.isHighSurrogate() && i + 1 < text.length) i + 2 else i + 1
                    for (byte in text.substring(i, end).toByteArray(Charsets.UTF_8)) {
                        out.append('%').append("%02X".format(Locale.ROOT, byte.toInt() and 0xFF))
                    }
                    i = end - 1
                }
            }
            i++
        }
        return out.toString()
    }

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
