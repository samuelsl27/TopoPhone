package es.topophone.app.ui.map

import es.topophone.core.geodesy.WebMercator
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Petición del Catastro: una sola imagen `GetMap` de la zona visible, sin teselas ni descargas masivas,
 * como exigen sus condiciones de uso (S009/RF-7 y RNF de la spec).
 */
object CadastreRequest {
    private const val ENDPOINT = "https://ovc.catastro.meh.es/cartografia/INSPIRE/spadgcwms.aspx"
    private const val LAYERS = "CP.CadastralParcel,BU.Building"

    /** Lado máximo de la imagen pedida. */
    const val MAX_SIDE_PX = 2048

    fun url(south: Double, west: Double, north: Double, east: Double, widthPx: Int, heightPx: Int): String {
        val (x0, y0) = WebMercator.forward(south, west)
        val (x1, y1) = WebMercator.forward(north, east)
        val scale = minOf(1.0, MAX_SIDE_PX.toDouble() / max(widthPx, heightPx))
        val width = (widthPx * scale).roundToInt()
        val height = (heightPx * scale).roundToInt()
        // Números en Locale.ROOT, nunca con la configuración regional de la interfaz (AGENTS.md).
        val bbox = "%.3f,%.3f,%.3f,%.3f".format(Locale.ROOT, x0, y0, x1, y1)
        return "$ENDPOINT?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=$LAYERS&STYLES=&CRS=EPSG:3857&BBOX=$bbox" +
            "&WIDTH=$width&HEIGHT=$height&FORMAT=image/png&TRANSPARENT=TRUE"
    }
}
