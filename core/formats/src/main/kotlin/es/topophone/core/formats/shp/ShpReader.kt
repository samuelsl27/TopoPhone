package es.topophone.core.formats.shp

import es.topophone.core.formats.gpkg.GpkgEnvelope
import es.topophone.core.formats.gpkg.GpkgPart
import es.topophone.core.formats.gpkg.GpkgPartKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** La cabecera de un `.shp`: el tipo de forma de todo el fichero y el tamaño que dice tener (S030/RF-15). */
class ShpHeader(val shapeType: Int, val declaredBytes: Long)

/** Lo que hay en un registro de un `.shp` (S030/RF-15, RF-16, RF-17). */
sealed interface ShpShape {
    /** Los elementos de un objeto con geometría: un punto, una línea o un anillo de polígono por parte, con su envolvente. */
    class Parts(val envelope: GpkgEnvelope, val parts: List<GpkgPart>) : ShpShape

    /** Una forma nula o un punto sin coordenadas: el objeto está vacío (RF-16). */
    data object Null : ShpShape

    /** Una superficie 3D, que no se importa (RF-16). */
    data object MultiPatch : ShpShape

    /** Un registro que no se puede leer: tipo desconocido o cuentas que no caben en su longitud. */
    data object Invalid : ShpShape
}

/**
 * Un registro: su posición en el fichero desde 1 ([index], con la que se enlaza con el `.dbf`), el número que dice su cabecera ([number]),
 * dónde empieza ([offset]) y su forma.
 */
class ShpRecord(val index: Int, val number: Int, val offset: Long, val contentBytes: Int, val shape: ShpShape) {
    /** Dónde empieza el registro siguiente. */
    val nextOffset: Long get() = offset + RECORD_HEADER_BYTES + contentBytes

    internal companion object {
        const val RECORD_HEADER_BYTES = 8
    }
}

/**
 * Lee los registros de un `.shp` (S030/RF-15, RF-16, RF-17) de uno en uno desde [source], sin cargarlo entero: `Point`, `MultiPoint`,
 * `PolyLine` y `Polygon` con Z, con M o sin ellas, forma nula y `MultiPatch`. La Z se conserva y la M se descarta; un polígono da una
 * línea por anillo, con la posición del anillo en el registro (el contorno de polígono de S048/RF-17); un multipunto, un punto por punto.
 * Los tipos con Z se leen con y sin los valores de M, que el formato deja como opcionales.
 */
class ShpReader private constructor(private val source: ShpByteSource, val header: ShpHeader) {

    /**
     * Recorre los registros en orden. Devuelve true si llegó hasta el final del fichero y false si un registro está cortado o su cabecera no
     * cuadra con el fichero: se han entregado los anteriores y se para.
     */
    fun forEachRecord(action: (ShpRecord) -> Unit): Boolean {
        var position = FIRST_RECORD
        var index = 0
        val size = source.size
        while (position < size) {
            val record = readAt(position, ++index) ?: return false
            action(record)
            position = record.nextOffset
        }
        return true
    }

    /**
     * Recorre los registros sin leer sus vértices: da la posición de cada uno y la envolvente que dice su cabecera —el recuadro de una
     * línea, un polígono o un multipunto, o el propio punto—, o null si el registro es nulo, un `MultiPatch` o no tiene un recuadro válido
     * (quien llama lo lee entonces con [readAt]). Va por un buffer de 1 MB, así que da el recuadro de un millón de registros en una
     * fracción de lo que cuesta decodificarlos. Devuelve true si llegó al final y false si un registro está cortado.
     */
    fun forEachEnvelope(action: (index: Int, offset: Long, envelope: GpkgEnvelope?) -> Unit): Boolean {
        val size = source.size
        val buffer = ByteArray(ENVELOPE_BUFFER_BYTES)
        val big = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN)
        val little = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        var start = -1L
        var end = -1L

        fun window(offset: Long, need: Int): Boolean {
            if (offset >= start && offset + need <= end) return true
            if (offset + need > size) return false
            val read = source.read(offset, buffer)
            start = offset
            end = offset + read
            return offset + need <= end
        }

        var position = FIRST_RECORD
        var index = 0
        while (position < size) {
            if (!window(position, RECORD_HEADER + 4)) return false
            val at = (position - start).toInt()
            val words = big.getInt(at + 4)
            val content = words.toLong() * 2
            if (words < MIN_CONTENT_WORDS || position + RECORD_HEADER + content > size) return false
            val type = little.getInt(at + RECORD_HEADER)
            val needed = RECORD_HEADER + minOf(content, ENVELOPE_CONTENT_BYTES.toLong()).toInt()
            index++
            var envelope: GpkgEnvelope? = null
            if (window(position, needed)) {
                val base = (position - start).toInt() + RECORD_HEADER
                if (type in POINT_TYPES && content >= 20) {
                    val x = little.getDouble(base + 4)
                    val y = little.getDouble(base + 12)
                    if (x.isFinite() && y.isFinite()) envelope = GpkgEnvelope(x, x, y, y)
                } else if (type in BOXED_TYPES && content >= 36) {
                    val minX = little.getDouble(base + 4)
                    val minY = little.getDouble(base + 12)
                    val maxX = little.getDouble(base + 20)
                    val maxY = little.getDouble(base + 28)
                    if (minX.isFinite() && minY.isFinite() && maxX.isFinite() && maxY.isFinite() && minX <= maxX && minY <= maxY) {
                        envelope = GpkgEnvelope(minX, maxX, minY, maxY)
                    }
                }
            }
            action(index, position, envelope)
            position += RECORD_HEADER + content
        }
        return true
    }

    /** Lo que mide el fichero, en bytes. */
    val sizeBytes: Long get() = source.size

    /** El número y la longitud del contenido en palabras de 16 bits del registro que empieza en [offset], o null si ahí no cabe uno entero. */
    internal fun recordHeaderAt(offset: Long): Pair<Int, Int>? {
        val head = ByteArray(RECORD_HEADER)
        if (offset < FIRST_RECORD || source.read(offset, head) < RECORD_HEADER) return null
        val buffer = ByteBuffer.wrap(head)
        val words = buffer.getInt(4)
        if (words < MIN_CONTENT_WORDS || offset + RECORD_HEADER + words.toLong() * 2 > source.size) return null
        return buffer.getInt(0) to words
    }

    /** El registro que empieza en [offset] (en bytes), o null si ahí no hay uno entero. [index] es su posición, que sabe quien llama. */
    fun readAt(offset: Long, index: Int): ShpRecord? {
        val head = ByteArray(RECORD_HEADER)
        if (offset < FIRST_RECORD || source.read(offset, head) < RECORD_HEADER) return null
        val buffer = ByteBuffer.wrap(head)
        val number = buffer.getInt(0)
        val words = buffer.getInt(4)
        if (words < MIN_CONTENT_WORDS) return null
        val bytes = words.toLong() * 2
        if (offset + RECORD_HEADER + bytes > source.size) return null
        val content = ByteArray(bytes.toInt())
        if (source.read(offset + RECORD_HEADER, content) < content.size) return null
        return ShpRecord(index, number, offset, content.size, parse(ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN)))
    }

    private fun parse(content: ByteBuffer): ShpShape {
        if (content.remaining() < 4) return ShpShape.Invalid
        return when (val type = content.getInt(0)) {
            0 -> ShpShape.Null
            POINT, POINT_M, POINT_Z -> point(content, type)
            MULTIPOINT, MULTIPOINT_M, MULTIPOINT_Z -> multipoint(content, type)
            POLYLINE, POLYLINE_M, POLYLINE_Z, POLYGON, POLYGON_M, POLYGON_Z -> poly(content, type)
            MULTIPATCH -> ShpShape.MultiPatch
            else -> ShpShape.Invalid
        }
    }

    private fun hasZ(type: Int) = type in Z_TYPES

    private fun point(c: ByteBuffer, type: Int): ShpShape {
        if (c.remaining() < 20) return ShpShape.Invalid
        val x = c.getDouble(4)
        val y = c.getDouble(12)
        if (!x.isFinite() || !y.isFinite()) return ShpShape.Null
        val z = if (hasZ(type)) (if (c.remaining() >= 28) c.getDouble(20) else return ShpShape.Invalid) else null
        val part = GpkgPart(GpkgPartKind.POINT, doubleArrayOf(x), doubleArrayOf(y), z?.let { doubleArrayOf(it) })
        return ShpShape.Parts(GpkgEnvelope(x, x, y, y), listOf(part))
    }

    private fun multipoint(c: ByteBuffer, type: Int): ShpShape {
        if (c.remaining() < 40) return ShpShape.Invalid
        val n = c.getInt(36)
        if (n < 0 || 40L + 16L * n > c.remaining()) return ShpShape.Invalid
        if (n == 0) return ShpShape.Null
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        for (i in 0 until n) {
            xs[i] = c.getDouble(40 + 16 * i)
            ys[i] = c.getDouble(48 + 16 * i)
        }
        val zs = zValues(c, type, 40 + 16 * n, n) ?: if (hasZ(type)) return ShpShape.Invalid else null
        val parts = (0 until n).filter { xs[it].isFinite() && ys[it].isFinite() }.map { i ->
            GpkgPart(GpkgPartKind.POINT, doubleArrayOf(xs[i]), doubleArrayOf(ys[i]), zs?.let { doubleArrayOf(it[i]) })
        }
        return if (parts.isEmpty()) ShpShape.Null else ShpShape.Parts(envelope(parts), parts)
    }

    private fun poly(c: ByteBuffer, type: Int): ShpShape {
        if (c.remaining() < 44) return ShpShape.Invalid
        val numParts = c.getInt(36)
        val numPoints = c.getInt(40)
        if (numParts < 0 || numPoints < 0 || numParts > numPoints) return ShpShape.Invalid
        val pointsAt = 44L + 4L * numParts
        if (pointsAt + 16L * numPoints > c.remaining()) return ShpShape.Invalid
        if (numParts == 0 || numPoints == 0) return ShpShape.Null
        val starts = IntArray(numParts) { c.getInt(44 + 4 * it) }
        for (i in starts.indices) {
            val end = if (i + 1 < numParts) starts[i + 1] else numPoints
            if (starts[i] < 0 || starts[i] > end || end > numPoints) return ShpShape.Invalid
        }
        val base = pointsAt.toInt()
        val zs = zValues(c, type, base + 16 * numPoints, numPoints) ?: if (hasZ(type)) return ShpShape.Invalid else null
        val polygon = type == POLYGON || type == POLYGON_M || type == POLYGON_Z
        val parts = ArrayList<GpkgPart>(numParts)
        for (i in starts.indices) {
            val from = starts[i]
            val to = if (i + 1 < numParts) starts[i + 1] else numPoints
            if (to == from) continue
            val x = DoubleArray(to - from) { c.getDouble(base + 16 * (from + it)) }
            val y = DoubleArray(to - from) { c.getDouble(base + 16 * (from + it) + 8) }
            parts += GpkgPart(GpkgPartKind.LINE, x, y, zs?.copyOfRange(from, to), if (polygon) i else null)
        }
        return if (parts.isEmpty()) ShpShape.Null else ShpShape.Parts(envelope(parts), parts)
    }

    /** Los [n] valores de Z que empiezan tras su recuadro de 16 bytes en [at], o null si el tipo no tiene Z o no caben. */
    private fun zValues(c: ByteBuffer, type: Int, at: Int, n: Int): DoubleArray? {
        if (!hasZ(type) || at + 16L + 8L * n > c.remaining()) return null
        return DoubleArray(n) { c.getDouble(at + 16 + 8 * it) }
    }

    private fun envelope(parts: List<GpkgPart>): GpkgEnvelope {
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (part in parts) {
            for (i in 0 until part.vertexCount) {
                minX = minOf(minX, part.x[i])
                maxX = maxOf(maxX, part.x[i])
                minY = minOf(minY, part.y[i])
                maxY = maxOf(maxY, part.y[i])
            }
        }
        return GpkgEnvelope(minX, maxX, minY, maxY)
    }

    companion object {
        private const val FIRST_RECORD = 100L
        private const val RECORD_HEADER = ShpRecord.RECORD_HEADER_BYTES
        private const val MIN_CONTENT_WORDS = 2 // el tipo de forma, de 4 bytes
        private const val ENVELOPE_BUFFER_BYTES = 1 shl 20
        private const val ENVELOPE_CONTENT_BYTES = 36 // el tipo de forma y el recuadro

        private const val POINT = 1
        private const val POLYLINE = 3
        private const val POLYGON = 5
        private const val MULTIPOINT = 8
        private const val POINT_Z = 11
        private const val POLYLINE_Z = 13
        private const val POLYGON_Z = 15
        private const val MULTIPOINT_Z = 18
        private const val POINT_M = 21
        private const val POLYLINE_M = 23
        private const val POLYGON_M = 25
        private const val MULTIPOINT_M = 28
        private const val MULTIPATCH = 31
        private val Z_TYPES = setOf(POINT_Z, POLYLINE_Z, POLYGON_Z, MULTIPOINT_Z)
        private val POINT_TYPES = setOf(POINT, POINT_M, POINT_Z)

        /** Los tipos con un recuadro en su cabecera: líneas, polígonos y multipuntos. */
        private val BOXED_TYPES = setOf(
            POLYLINE, POLYLINE_M, POLYLINE_Z, POLYGON, POLYGON_M, POLYGON_Z, MULTIPOINT, MULTIPOINT_M, MULTIPOINT_Z,
        )

        /** Abre el `.shp` de [source], o devuelve null si su cabecera no es la de un Shapefile. */
        fun open(source: ShpByteSource): ShpReader? {
            val head = ByteArray(ShpFileKind.HEAD_BYTES)
            if (source.read(0, head) < head.size || !ShpFileKind.isShapeHeader(head, source.size)) return null
            val declared = ByteBuffer.wrap(head).getInt(24).toLong() * 2
            return ShpReader(source, ShpHeader(ShpFileKind.shapeType(head)!!, declared))
        }
    }
}
