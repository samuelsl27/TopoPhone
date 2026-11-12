package es.topophone.core.formats.shp

import es.topophone.core.formats.gpkg.GpkgPart
import es.topophone.core.formats.gpkg.GpkgPartKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Del registro de un `.shp` al blob de geometría de GeoPackage (S030/RF-13, RF-15, decisión D1 del plan): una cabecera `GP` con la envolvente
 * XY y el WKB de la forma. Así el importador, el recuento por zona y el convertidor de S048 leen un Shapefile con `GpkgGeometryReader` sin
 * cambios. Un polígono es un solo `Polygon` con todos sus anillos en orden, para conservar la posición de cada uno; una línea de varias
 * partes, un `MultiLineString`; un multipunto, un `MultiPoint`. Con Z, el WKB lleva la Z de cada vértice.
 */
object ShpBlobEncoder {
    private const val HEADER_BYTES = 40 // `GP`, versión, indicadores, SRS y la envolvente de 4 valores
    private const val POINT = 1
    private const val LINESTRING = 2
    private const val POLYGON = 3
    private const val MULTIPOINT = 4
    private const val MULTILINESTRING = 5
    private const val Z_OFFSET = 1000

    /** Solo la cabecera de 40 bytes del blob de una forma con esa envolvente: lo que lee el recuento por zona sin leer los vértices. */
    fun header(envelope: es.topophone.core.formats.gpkg.GpkgEnvelope): ByteArray {
        val out = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        out.put('G'.code.toByte()).put('P'.code.toByte()).put(0).put(FLAGS)
        out.putInt(0)
        out.putDouble(envelope.minX).putDouble(envelope.maxX).putDouble(envelope.minY).putDouble(envelope.maxY)
        return out.array()
    }

    /** El blob de [shape], con la envolvente en la cabecera. */
    fun encode(shape: ShpShape.Parts): ByteArray {
        val parts = shape.parts
        val hasZ = parts.first().z != null
        val body = ByteBuffer.allocate(wkbSize(parts, hasZ)).order(ByteOrder.LITTLE_ENDIAN)
        when {
            parts.first().isPolygonRing -> polygon(body, parts, hasZ)
            parts.first().kind == GpkgPartKind.POINT -> points(body, parts, hasZ)
            else -> lines(body, parts, hasZ)
        }
        val out = ByteBuffer.allocate(HEADER_BYTES + body.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        out.put('G'.code.toByte()).put('P'.code.toByte()).put(0).put(FLAGS)
        out.putInt(0)
        val e = shape.envelope
        out.putDouble(e.minX).putDouble(e.maxX).putDouble(e.minY).putDouble(e.maxY)
        out.put(body.array())
        return out.array()
    }

    // Bit 0: orden de bytes pequeño; bits 1 a 3: envolvente XY (1); bit 4: no vacía. Son 0b0000_0011.
    private const val FLAGS: Byte = 3

    private fun dims(hasZ: Boolean) = if (hasZ) 3 else 2

    private fun header(out: ByteBuffer, type: Int, hasZ: Boolean) {
        out.put(1).putInt(type + if (hasZ) Z_OFFSET else 0)
    }

    private fun vertex(out: ByteBuffer, part: GpkgPart, i: Int) {
        out.putDouble(part.x[i]).putDouble(part.y[i])
        part.z?.let { out.putDouble(it[i]) }
    }

    private fun ring(out: ByteBuffer, part: GpkgPart) {
        out.putInt(part.vertexCount)
        for (i in 0 until part.vertexCount) vertex(out, part, i)
    }

    private fun polygon(out: ByteBuffer, parts: List<GpkgPart>, hasZ: Boolean) {
        header(out, POLYGON, hasZ)
        out.putInt(parts.size)
        parts.forEach { ring(out, it) }
    }

    private fun points(out: ByteBuffer, parts: List<GpkgPart>, hasZ: Boolean) {
        if (parts.size == 1) return point(out, parts.single(), hasZ)
        header(out, MULTIPOINT, hasZ)
        out.putInt(parts.size)
        parts.forEach { point(out, it, hasZ) }
    }

    private fun point(out: ByteBuffer, part: GpkgPart, hasZ: Boolean) {
        header(out, POINT, hasZ)
        vertex(out, part, 0)
    }

    private fun lines(out: ByteBuffer, parts: List<GpkgPart>, hasZ: Boolean) {
        if (parts.size == 1) return line(out, parts.single(), hasZ)
        header(out, MULTILINESTRING, hasZ)
        out.putInt(parts.size)
        parts.forEach { line(out, it, hasZ) }
    }

    private fun line(out: ByteBuffer, part: GpkgPart, hasZ: Boolean) {
        header(out, LINESTRING, hasZ)
        ring(out, part)
    }

    private fun wkbSize(parts: List<GpkgPart>, hasZ: Boolean): Int {
        val vertices = parts.sumOf { it.vertexCount }
        val coordinates = vertices * dims(hasZ) * Double.SIZE_BYTES
        val headers = 5 // el orden de bytes y el tipo
        return when {
            parts.first().isPolygonRing -> headers + 4 + parts.size * 4 + coordinates
            parts.first().kind == GpkgPartKind.POINT ->
                if (parts.size == 1) headers + coordinates else headers + 4 + parts.size * headers + coordinates
            else -> if (parts.size == 1) headers + 4 + coordinates else headers + 4 + parts.size * (headers + 4) + coordinates
        }
    }
}
