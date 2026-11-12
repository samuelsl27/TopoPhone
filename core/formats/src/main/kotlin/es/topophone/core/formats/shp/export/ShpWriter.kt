package es.topophone.core.formats.shp.export

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** El tipo de forma de un Shapefile que escribe la exportación, con su código en el `.shp` (S055/RF-3): todos con Z. */
enum class ShpType(val code: Int) {
    POINT_Z(11),
    POLYLINE_Z(13),
    POLYGON_Z(15),
}

/** Un vértice con X, Y y Z, sin redondear (S055/RF-12 a RF-14). Una Z ausente es 0: quien prepara la capa la cuenta. */
data class ShpVertex(val x: Double, val y: Double, val z: Double)

/** El objeto de un registro del `.shp`. Un polígono lleva su contorno abierto y el escritor lo orienta y lo cierra (S055/RF-5). */
sealed interface ShpGeometry {
    /** Un objeto sin geometría: un registro nulo, que conserva su fila del `.dbf` (S055/RF-14b). */
    data object Null : ShpGeometry

    data class Point(val vertex: ShpVertex) : ShpGeometry

    /** Una línea abierta de al menos 2 vértices, en una sola parte. */
    data class Line(val vertices: List<ShpVertex>) : ShpGeometry {
        init {
            require(vertices.size >= 2) { "Una línea necesita al menos 2 vértices" }
        }
    }

    /** Un polígono de al menos 3 vértices, sin repetir el primero al final, en una sola parte. */
    data class Polygon(val vertices: List<ShpVertex>) : ShpGeometry {
        init {
            require(vertices.size >= 3) { "Un polígono necesita al menos 3 vértices" }
        }
    }
}

/**
 * Escribe el `.shp` y el `.shx` de una capa `PointZ`, `PolyLineZ` o `PolygonZ` (S055/RF-3, RF-5, RF-12 a RF-14b, plan A3), con la
 * especificación de ESRI: la cabecera de 100 bytes con la longitud en palabras de 16 bits y la envolvente de X, Y y Z de lo escrito, y un
 * registro por objeto con su número desde 1. El `PointZ` lleva M = −1e38 («sin dato»); las líneas y los polígonos no llevan M, que el formato
 * deja como opcional. Un polígono se escribe en una sola parte, con el contorno en sentido horario (se conserva el primer vértice) y el
 * primer vértice repetido al final. Los números van en el orden de bytes del formato, sin depender de nada local.
 */
object ShpWriter {
    private const val FILE_CODE = 9994
    private const val VERSION = 1000
    private const val HEADER_BYTES = 100
    private const val RECORD_HEADER_BYTES = 8
    private const val NULL_TYPE = 0
    private const val NO_DATA = -1e38
    private const val BUFFER_BYTES = 1 shl 16

    /**
     * Escribe [geometries] como una capa de [type]: el `.shp` en [shp] y el `.shx` en [shx], que no se cierran. Lanza
     * [IllegalArgumentException] si una geometría no es del tipo de la capa.
     */
    fun write(type: ShpType, geometries: List<ShpGeometry>, shp: OutputStream, shx: OutputStream) {
        val extent = Extent()
        val contentBytes = IntArray(geometries.size)
        var total = HEADER_BYTES.toLong()
        geometries.forEachIndexed { i, geometry ->
            contentBytes[i] = measure(type, geometry, extent)
            total += RECORD_HEADER_BYTES + contentBytes[i]
        }
        val shpOut = BufferedOutputStream(shp, BUFFER_BYTES)
        val shxOut = BufferedOutputStream(shx, BUFFER_BYTES)
        shpOut.write(header(type, total, extent))
        shxOut.write(header(type, HEADER_BYTES + 8L * geometries.size, extent))
        var offset = HEADER_BYTES.toLong()
        geometries.forEachIndexed { i, geometry ->
            val content = ByteBuffer.allocate(RECORD_HEADER_BYTES + contentBytes[i])
            content.order(ByteOrder.BIG_ENDIAN).putInt(i + 1).putInt(contentBytes[i] / 2)
            content.order(ByteOrder.LITTLE_ENDIAN)
            put(type, geometry, content)
            shpOut.write(content.array())
            shxOut.write(
                ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putInt((offset / 2).toInt()).putInt(contentBytes[i] / 2).array(),
            )
            offset += RECORD_HEADER_BYTES + contentBytes[i]
        }
        shpOut.flush()
        shxOut.flush()
    }

    /** Los bytes del contenido del registro de [geometry] (sin su cabecera de 8 bytes); suma sus vértices a [extent]. */
    private fun measure(type: ShpType, geometry: ShpGeometry, extent: Extent): Int = when (geometry) {
        ShpGeometry.Null -> Int.SIZE_BYTES
        is ShpGeometry.Point -> {
            require(type == ShpType.POINT_Z) { "Un punto no cabe en una capa $type" }
            extent.add(geometry.vertex)
            Int.SIZE_BYTES + 4 * Double.SIZE_BYTES
        }
        is ShpGeometry.Line -> {
            require(type == ShpType.POLYLINE_Z) { "Una línea no cabe en una capa $type" }
            geometry.vertices.forEach(extent::add)
            poly(geometry.vertices.size)
        }
        is ShpGeometry.Polygon -> {
            require(type == ShpType.POLYGON_Z) { "Un polígono no cabe en una capa $type" }
            geometry.vertices.forEach(extent::add)
            poly(geometry.vertices.size + 1)
        }
    }

    /** Tipo, caja, partes, puntos, el índice de la única parte, los puntos y las Z con su rango. */
    private fun poly(points: Int): Int =
        Int.SIZE_BYTES + 4 * Double.SIZE_BYTES + 3 * Int.SIZE_BYTES + 2 * Double.SIZE_BYTES * points + 2 * Double.SIZE_BYTES +
            Double.SIZE_BYTES * points

    private fun put(type: ShpType, geometry: ShpGeometry, out: ByteBuffer) {
        when (geometry) {
            ShpGeometry.Null -> out.putInt(NULL_TYPE)
            is ShpGeometry.Point -> out.putInt(type.code).putDouble(geometry.vertex.x).putDouble(geometry.vertex.y)
                .putDouble(geometry.vertex.z).putDouble(NO_DATA)
            is ShpGeometry.Line -> putPoly(type, geometry.vertices, out)
            is ShpGeometry.Polygon -> putPoly(type, clockwiseClosed(geometry.vertices), out)
        }
    }

    private fun putPoly(type: ShpType, vertices: List<ShpVertex>, out: ByteBuffer) {
        val box = Extent().also { vertices.forEach(it::add) }
        out.putInt(type.code).putDouble(box.minX).putDouble(box.minY).putDouble(box.maxX).putDouble(box.maxY)
        out.putInt(1).putInt(vertices.size).putInt(0)
        vertices.forEach { out.putDouble(it.x).putDouble(it.y) }
        out.putDouble(box.minZ).putDouble(box.maxZ)
        vertices.forEach { out.putDouble(it.z) }
    }

    /** El contorno en sentido horario, conservando el primer vértice, y con el primer vértice repetido al final (S055/RF-5). */
    internal fun clockwiseClosed(vertices: List<ShpVertex>): List<ShpVertex> {
        val ring = if (signedArea(vertices) > 0.0) listOf(vertices[0]) + vertices.drop(1).asReversed() else vertices
        return ring + ring[0]
    }

    /**
     * La mitad de la suma de productos cruzados, con el primer vértice como origen para no perder el signo con coordenadas grandes:
     * positiva si el contorno va en sentido antihorario en el plano X/Y.
     */
    internal fun signedArea(vertices: List<ShpVertex>): Double {
        val x0 = vertices[0].x
        val y0 = vertices[0].y
        var total = 0.0
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            total += (a.x - x0) * (b.y - y0) - (b.x - x0) * (a.y - y0)
        }
        return total / 2.0
    }

    private fun header(type: ShpType, fileBytes: Long, extent: Extent): ByteArray {
        val bytes = ByteBuffer.allocate(HEADER_BYTES)
        bytes.order(ByteOrder.BIG_ENDIAN).putInt(0, FILE_CODE).putInt(24, (fileBytes / 2).toInt())
        bytes.order(ByteOrder.LITTLE_ENDIAN).putInt(28, VERSION).putInt(32, type.code)
        val empty = extent.empty
        bytes.putDouble(36, if (empty) 0.0 else extent.minX).putDouble(44, if (empty) 0.0 else extent.minY)
            .putDouble(52, if (empty) 0.0 else extent.maxX).putDouble(60, if (empty) 0.0 else extent.maxY)
            .putDouble(68, if (empty) 0.0 else extent.minZ).putDouble(76, if (empty) 0.0 else extent.maxZ)
        // El rango de M (bytes 84 a 99) queda en 0: la capa no lleva valores M.
        return bytes.array()
    }

    /** La envolvente de X, Y y Z de lo que se escribe. */
    private class Extent {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        val empty: Boolean get() = minX > maxX

        fun add(v: ShpVertex) {
            minX = minOf(minX, v.x)
            maxX = maxOf(maxX, v.x)
            minY = minOf(minY, v.y)
            maxY = maxOf(maxY, v.y)
            minZ = minOf(minZ, v.z)
            maxZ = maxOf(maxZ, v.z)
        }
    }
}
