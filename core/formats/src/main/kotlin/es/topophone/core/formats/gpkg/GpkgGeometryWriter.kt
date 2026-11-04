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
    private const val WKB_LINE = 2
    private const val WKB_LINE_Z = 1002
    private const val WKB_POLYGON = 3
    private const val WKB_POLYGON_Z = 1003
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

    /** Un vértice de una línea o de un contorno. */
    class Vertex(val x: Double, val y: Double, val z: Double?)

    /** El blob de una línea (LineString, o LineString Z si todos los vértices tienen altura) en el sistema [srsId] (S025/RF-26). */
    fun lineString(srsId: Int, vertices: List<Vertex>): ByteArray {
        val withZ = vertices.all { it.z != null }
        val buffer = ByteBuffer.allocate(
            FIXED_HEADER + 1 + 2 * Int.SIZE_BYTES + vertices.size * (if (withZ) 3 else 2) * DOUBLE_BYTES,
        ).order(ByteOrder.LITTLE_ENDIAN)
        header(buffer, srsId, FLAGS_LITTLE_ENDIAN)
        buffer.put(WKB_LITTLE_ENDIAN).putInt(if (withZ) WKB_LINE_Z else WKB_LINE).putInt(vertices.size)
        vertices.forEach { putVertex(buffer, it, withZ) }
        return buffer.array()
    }

    /**
     * El blob de un polígono de un solo anillo exterior (Polygon, o Polygon Z si todos tienen altura) en el sistema [srsId]. El anillo se
     * cierra repitiendo el primer vértice al final, como pide WKB (S025/RF-26).
     */
    fun polygon(srsId: Int, vertices: List<Vertex>): ByteArray {
        val ring = vertices + vertices.first()
        val withZ = ring.all { it.z != null }
        val buffer = ByteBuffer.allocate(
            FIXED_HEADER + 1 + 3 * Int.SIZE_BYTES + ring.size * (if (withZ) 3 else 2) * DOUBLE_BYTES,
        ).order(ByteOrder.LITTLE_ENDIAN)
        header(buffer, srsId, FLAGS_LITTLE_ENDIAN)
        buffer.put(WKB_LITTLE_ENDIAN).putInt(if (withZ) WKB_POLYGON_Z else WKB_POLYGON).putInt(1).putInt(ring.size)
        ring.forEach { putVertex(buffer, it, withZ) }
        return buffer.array()
    }

    private fun putVertex(buffer: ByteBuffer, vertex: Vertex, withZ: Boolean) {
        buffer.putDouble(vertex.x).putDouble(vertex.y)
        if (withZ) buffer.putDouble(vertex.z!!)
    }

    private fun header(buffer: ByteBuffer, srsId: Int, flags: Int) {
        buffer.put('G'.code.toByte()).put('P'.code.toByte()).put(0).put(flags.toByte()).putInt(srsId)
    }
}
