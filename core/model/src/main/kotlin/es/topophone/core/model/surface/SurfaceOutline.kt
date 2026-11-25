package es.topophone.core.model.surface

import java.io.ByteArrayOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Un anillo del contorno de una superficie (S061/RF-6, plan A2): sus vértices en Este y Norte del sistema del proyecto, en metros y
 * sin redondear, cerrado implícitamente (el último vértice no repite el primero). Un [exterior] va en sentido antihorario y un
 * hueco en sentido horario.
 */
class SurfaceRing(val exterior: Boolean, val easting: DoubleArray, val northing: DoubleArray) {
    init {
        require(easting.size == northing.size) { "Un Norte por cada Este" }
    }

    val vertexCount: Int get() = easting.size

    override fun equals(other: Any?): Boolean = other is SurfaceRing &&
        exterior == other.exterior &&
        easting.contentEquals(other.easting) &&
        northing.contentEquals(other.northing)

    override fun hashCode(): Int = 31 * (31 * exterior.hashCode() + easting.contentHashCode()) + northing.contentHashCode()

    override fun toString(): String = "SurfaceRing(${if (exterior) "exterior" else "hueco"}, $vertexCount vértices)"
}

/** El contorno de una superficie: sus anillos exteriores y sus huecos (S061/RF-6, RF-13). */
data class SurfaceOutline(val rings: List<SurfaceRing>)

/**
 * Los bloques binarios de un contorno (S061, plan «Formato del contorno»). Cada bloque: un byte de versión, el número de tramos y,
 * por tramo, un byte de marcas (1 = exterior, 2 = el anillo sigue en el tramo siguiente), el número de vértices y sus pares de
 * `Double` (Este, Norte). Un anillo que no cabe en un bloque se parte en tramos consecutivos; ningún bloque pasa de [MAX_PART_BYTES].
 */
object SurfaceOutlineCodec {
    /** Lo que puede pesar un bloque, como una tesela. */
    const val MAX_PART_BYTES = SurfaceTileCodec.MAX_TILE_BYTES

    private const val FORMAT = 1
    private const val EXTERIOR = 1
    private const val CONTINUES = 2
    private const val VERTEX_BYTES = 2 * Double.SIZE_BYTES

    /** Lo que ocupan como mucho la cabecera de un bloque y la de un tramo (un byte y un entero de hasta 5 bytes cada una). */
    private const val PART_HEADER = 1 + 5
    private const val PIECE_HEADER = 1 + 5

    /** Los bloques de [outline], en orden; al menos uno, aunque no tenga anillos. */
    fun encode(outline: SurfaceOutline, maxPartBytes: Int = MAX_PART_BYTES): List<ByteArray> {
        require((maxPartBytes - PART_HEADER - PIECE_HEADER) / VERTEX_BYTES >= 1) {
            "En un bloque de $maxPartBytes bytes no cabe un vértice"
        }
        val parts = ArrayList<ByteArray>()
        val pieces = ArrayList<Piece>()
        var used = PART_HEADER

        fun flush() {
            val out = ByteArrayOutputStream(used)
            out.write(FORMAT)
            writeVarint(out, pieces.size)
            for (p in pieces) {
                out.write((if (p.ring.exterior) EXTERIOR else 0) or (if (p.continues) CONTINUES else 0))
                writeVarint(out, p.count)
                for (k in p.from until p.from + p.count) {
                    writeDouble(out, p.ring.easting[k])
                    writeDouble(out, p.ring.northing[k])
                }
            }
            parts += out.toByteArray()
            pieces.clear()
            used = PART_HEADER
        }

        for (ring in outline.rings) {
            var from = 0
            while (true) {
                val room = (maxPartBytes - used - PIECE_HEADER) / VERTEX_BYTES
                val left = ring.vertexCount - from
                // Un anillo que no cabe entero en lo que queda empieza en un bloque nuevo, para partirlo lo menos posible.
                if (room < 1 || (from == 0 && room < left && pieces.isNotEmpty())) {
                    flush()
                    continue
                }
                val count = minOf(room, left)
                val continues = count < left
                pieces += Piece(ring, from, count, continues)
                used += PIECE_HEADER + count * VERTEX_BYTES
                from += count
                if (!continues) break
                flush()
            }
        }
        if (pieces.isNotEmpty() || parts.isEmpty()) flush()
        return parts
    }

    /** El contorno de sus bloques en orden; [IllegalArgumentException] si alguno está cortado o no es de este formato. */
    fun decode(parts: List<ByteArray>): SurfaceOutline {
        val rings = ArrayList<SurfaceRing>()
        var east = Doubles()
        var north = Doubles()
        var pending = false
        var pendingExterior = false
        for (bytes in parts) {
            val buffer = ByteBuffer.wrap(bytes)
            try {
                require(buffer.get().toInt() == FORMAT) { "Formato de contorno desconocido" }
                repeat(readVarint(buffer)) {
                    val flags = buffer.get().toInt()
                    val exterior = flags and EXTERIOR != 0
                    require(!pending || exterior == pendingExterior) { "Un anillo partido cambia de tipo" }
                    repeat(readVarint(buffer)) {
                        east.add(buffer.getDouble())
                        north.add(buffer.getDouble())
                    }
                    if (flags and CONTINUES != 0) {
                        pending = true
                        pendingExterior = exterior
                    } else {
                        rings += SurfaceRing(exterior, east.toArray(), north.toArray())
                        east = Doubles()
                        north = Doubles()
                        pending = false
                    }
                }
                require(!buffer.hasRemaining()) { "Sobran bytes en el bloque del contorno" }
            } catch (e: BufferUnderflowException) {
                throw IllegalArgumentException("Bloque del contorno cortado", e)
            }
        }
        require(!pending) { "Falta el final de un anillo del contorno" }
        return SurfaceOutline(rings)
    }

    private class Piece(val ring: SurfaceRing, val from: Int, val count: Int, val continues: Boolean)

    private class Doubles {
        private var data = DoubleArray(64)
        private var size = 0

        fun add(value: Double) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toArray(): DoubleArray = data.copyOf(size)
    }

    private fun writeDouble(out: ByteArrayOutputStream, value: Double) {
        val bits = value.toRawBits()
        for (shift in 56 downTo 0 step 8) out.write((bits ushr shift).toInt() and 0xFF)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    private fun readVarint(buffer: ByteBuffer): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buffer.get().toInt()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 35) { "Entero del contorno demasiado largo" }
        }
    }
}
