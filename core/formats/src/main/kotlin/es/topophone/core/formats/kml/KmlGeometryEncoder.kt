package es.topophone.core.formats.kml

import es.topophone.core.formats.gpkg.GpkgEnvelope
import es.topophone.core.formats.shp.ShpBlobEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * De las partes de una marca al blob de geometría de GeoPackage (S031/RF-7, RF-8, decisión D1 del plan): una cabecera `GP` con la envolvente
 * XY y el WKB, de modo que el importador, el recuento por zona y el convertidor de S048 leen un KML con `GpkgGeometryReader` sin cambios. Con
 * una sola parte es un `Point`, un `LineString` o un `Polygon` de un anillo (un contorno o un hueco); con varias, una `GeometryCollection` con
 * cada parte. Las coordenadas son longitud y latitud tal como están en el KML; el blob lleva Z solo si **todas** las partes la llevan.
 */
object KmlGeometryEncoder {
    private const val HEADER_BYTES = 40
    private const val POINT = 1
    private const val LINESTRING = 2
    private const val POLYGON = 3
    private const val COLLECTION = 7
    private const val Z_OFFSET = 1000

    /** El blob de [parts], que no puede estar vacía. */
    fun encode(parts: List<KmlPart>): ByteArray {
        require(parts.isNotEmpty()) { "Un objeto necesita alguna parte" }
        val hasZ = parts.all { it.hasZ }
        val body = ByteBuffer.allocate(wkbSize(parts, hasZ)).order(ByteOrder.LITTLE_ENDIAN)
        if (parts.size == 1) {
            part(body, parts.single(), hasZ)
        } else {
            body.put(1).putInt(COLLECTION).putInt(parts.size)
            for (part in parts) part(body, part, hasZ)
        }
        val head = ShpBlobEncoder.header(envelope(parts))
        return head + body.array()
    }

    /** La envolvente XY de [parts], en grados. */
    fun envelope(parts: List<KmlPart>): GpkgEnvelope {
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (part in parts) {
            for (x in part.vertices.lon) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
            }
            for (y in part.vertices.lat) {
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return GpkgEnvelope(minX, maxX, minY, maxY)
    }

    private fun dims(hasZ: Boolean) = if (hasZ) 3 else 2

    private fun wkbSize(parts: List<KmlPart>, hasZ: Boolean): Int {
        val perPart = parts.sumOf { partSize(it, hasZ) }
        return if (parts.size == 1) perPart else 1 + Int.SIZE_BYTES + Int.SIZE_BYTES + perPart
    }

    private fun partSize(part: KmlPart, hasZ: Boolean): Int {
        val vertices = part.vertices.size * dims(hasZ) * Double.SIZE_BYTES
        val header = 1 + Int.SIZE_BYTES
        return when {
            part.isPoint -> header + dims(hasZ) * Double.SIZE_BYTES
            part.contour -> header + Int.SIZE_BYTES + Int.SIZE_BYTES + vertices // un anillo
            else -> header + Int.SIZE_BYTES + vertices
        }
    }

    private fun part(out: ByteBuffer, part: KmlPart, hasZ: Boolean) {
        val z = if (hasZ) Z_OFFSET else 0
        when {
            part.isPoint -> {
                out.put(1).putInt(POINT + z)
                vertex(out, part, 0, hasZ)
            }

            part.contour -> {
                out.put(1).putInt(POLYGON + z).putInt(1)
                ring(out, part, hasZ)
            }

            else -> {
                out.put(1).putInt(LINESTRING + z)
                ring(out, part, hasZ)
            }
        }
    }

    /** `n` y los vértices: lo que sigue a la cabecera de una línea y lo que es cada anillo de un polígono. */
    private fun ring(out: ByteBuffer, part: KmlPart, hasZ: Boolean) {
        out.putInt(part.vertices.size)
        for (i in 0 until part.vertices.size) vertex(out, part, i, hasZ)
    }

    private fun vertex(out: ByteBuffer, part: KmlPart, i: Int, hasZ: Boolean) {
        out.putDouble(part.vertices.lon[i]).putDouble(part.vertices.lat[i])
        if (hasZ) out.putDouble(checkNotNull(part.vertices.alt)[i])
    }
}
