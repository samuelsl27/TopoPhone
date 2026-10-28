package es.topophone.core.formats.gpkg

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * El blob de geometría de un punto de GeoPackage (S053/RF-5, RF-6): `GP`, versión 0, indicadores (little-endian, sin envolvente, vacía o
 * no), el `srs_id` de la capa y el WKB en códigos ISO: `Point` (1) o `Point Z` (1001). Los puntos no llevan envolvente: no hace falta para
 * un punto y el recuadro de la capa va en `gpkg_contents`. Lo lee [GpkgGeometryReader] (S048).
 */
object GpkgGeometryWriter {
    private const val FIXED_HEADER = 8
    private const val FLAGS_LITTLE_ENDIAN = 1
    private const val FLAG_EMPTY = 16
    private const val WKB_LITTLE_ENDIAN: Byte = 1
    private const val WKB_POINT = 1
    private const val WKB_POINT_Z = 1001
    private const val DOUBLE_BYTES = 8

    /** El blob de un punto en ([x], [y]) con la altura [z] como tercera coordenada si la hay, en el sistema [srsId]. */
    fun point(srsId: Int, x: Double, y: Double, z: Double?): ByteArray {
        val withZ = z != null
        val buffer = ByteBuffer.allocate(
            FIXED_HEADER + 1 + Int.SIZE_BYTES + (if (withZ) 3 else 2) * DOUBLE_BYTES,
        ).order(ByteOrder.LITTLE_ENDIAN)
        header(buffer, srsId, FLAGS_LITTLE_ENDIAN)
        buffer.put(WKB_LITTLE_ENDIAN).putInt(if (withZ) WKB_POINT_Z else WKB_POINT).putDouble(x).putDouble(y)
        if (z != null) buffer.putDouble(z)
        return buffer.array()
    }

    /** La geometría vacía de un punto: el indicador de vacía y un `Point` con las coordenadas NaN, como lo escribe GeoPackage (RF-5). */
    fun empty(srsId: Int): ByteArray {
        val buffer = ByteBuffer.allocate(FIXED_HEADER + 1 + Int.SIZE_BYTES + 2 * DOUBLE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header(buffer, srsId, FLAGS_LITTLE_ENDIAN or FLAG_EMPTY)
        buffer.put(WKB_LITTLE_ENDIAN).putInt(WKB_POINT).putDouble(Double.NaN).putDouble(Double.NaN)
        return buffer.array()
    }

    /**
     * El blob de un punto que puede no tener posición: sin [x] o [y], o con alguna que no es un número, la geometría vacía (D6); una [z] que no
     * es un número se deja fuera (el punto conserva su posición).
     */
    fun of(srsId: Int, x: Double?, y: Double?, z: Double?): ByteArray {
        if (x == null || y == null || !x.isFinite() || !y.isFinite()) return empty(srsId)
        return point(srsId, x, y, z?.takeIf { it.isFinite() })
    }

    private fun header(buffer: ByteBuffer, srsId: Int, flags: Int) {
        buffer.put('G'.code.toByte()).put('P'.code.toByte()).put(0).put(flags.toByte()).putInt(srsId)
    }
}
