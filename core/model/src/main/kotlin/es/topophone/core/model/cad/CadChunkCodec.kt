package es.topophone.core.model.cad

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Un elemento del dibujo con sus vértices en el sistema del proyecto, sin redondear (S040/RF-19). [height] es null si el
 * elemento no trae Z (RF-23); [color] es null si es el de su capa (RF-24).
 */
class CadEntity(
    val kind: CadEntityKind,
    val easting: DoubleArray,
    val northing: DoubleArray,
    val height: DoubleArray?,
    val color: CadColor?,
) {
    init {
        require(easting.size == northing.size && easting.isNotEmpty()) { "Los vértices necesitan Este y Norte" }
        require(height == null || height.size == easting.size) { "Una altura por vértice, o ninguna" }
    }

    val vertexCount: Int get() = easting.size
    val hasZ: Boolean get() = height != null

    /** El recuadro del elemento (RF-29). */
    val bounds: CadExtent
        get() = CadExtent(easting.min(), easting.max(), northing.min(), northing.max())

    override fun equals(other: Any?): Boolean = other is CadEntity &&
        kind == other.kind &&
        color == other.color &&
        easting.contentEquals(other.easting) &&
        northing.contentEquals(other.northing) &&
        height.contentEquals(other.height)

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + easting.contentHashCode()
        result = 31 * result + northing.contentHashCode()
        result = 31 * result + height.contentHashCode()
        return 31 * result + (color?.hashCode() ?: 0)
    }

    override fun toString(): String = "CadEntity($kind, $vertexCount vertices, z=$hasZ, color=$color)"
}

/**
 * El bloque binario de un trozo del dibujo (S040/RF-19, RF-22, RF-23). Por elemento: tipo (1 byte), marcas (1 byte: con altura,
 * con color propio, color de contraste), el color ARGB si lo trae (4 bytes), el número de vértices (entero de longitud variable)
 * y los `Double` de Este y Norte de cada vértice, y los de la altura si la lleva. Un trozo no pasa de [MAX_CHUNK_BYTES].
 */
object CadChunkCodec {
    /** Lo que puede pesar un trozo: el `CursorWindow` de Android no admite filas de más de 2 MB. */
    const val MAX_CHUNK_BYTES = 256 * 1024

    private const val HAS_Z = 1
    private const val HAS_COLOR = 2
    private const val AUTO_CONTRAST = 4

    fun encode(entities: List<CadEntity>): ByteArray {
        val out = ByteArrayOutputStream(encodedSize(entities))
        writeVarint(out, entities.size)
        for (e in entities) {
            out.write(e.kind.ordinal)
            val color = e.color
            var flags = 0
            if (e.hasZ) flags = flags or HAS_Z
            if (color != null) flags = flags or HAS_COLOR
            if (color?.autoContrast == true) flags = flags or AUTO_CONTRAST
            out.write(flags)
            if (color != null && !color.autoContrast) out.write(ByteBuffer.allocate(4).putInt(color.argb).array())
            writeVarint(out, e.vertexCount)
            writeDoubles(out, e.easting)
            writeDoubles(out, e.northing)
            e.height?.let { writeDoubles(out, it) }
        }
        return out.toByteArray()
    }

    /** Lo que ocupa [encode] de esta lista, sin construirlo: sirve para decidir cuándo se cierra un trozo. */
    fun encodedSize(entities: List<CadEntity>): Int = varintSize(entities.size) + entities.sumOf { entitySize(it) }

    /** Lo que ocupa un elemento en el bloque. */
    fun entitySize(e: CadEntity): Int {
        val color = e.color
        val colorBytes = if (color != null && !color.autoContrast) 4 else 0
        val doubles = e.vertexCount * (if (e.hasZ) 3 else 2) * Double.SIZE_BYTES
        return 2 + colorBytes + varintSize(e.vertexCount) + doubles
    }

    /** Los elementos de un bloque; [IllegalArgumentException] si está cortado o no es de este formato (RF-13). */
    fun decode(bytes: ByteArray): List<CadEntity> {
        val buffer = ByteBuffer.wrap(bytes)
        try {
            val count = readVarint(buffer)
            require(count >= 0 && count <= bytes.size) { "Número de elementos imposible" }
            val out = ArrayList<CadEntity>(count)
            repeat(count) {
                val kind = CadEntityKind.entries.getOrNull(buffer.get().toInt()) ?: throw IllegalArgumentException("Tipo desconocido")
                val flags = buffer.get().toInt()
                val color = when {
                    flags and AUTO_CONTRAST != 0 -> CadColor.AUTO_CONTRAST
                    flags and HAS_COLOR != 0 -> CadColor(buffer.getInt())
                    else -> null
                }
                val n = readVarint(buffer)
                require(n > 0 && n.toLong() * Double.SIZE_BYTES <= buffer.remaining()) { "Vértices imposibles" }
                val easting = readDoubles(buffer, n)
                val northing = readDoubles(buffer, n)
                val height = if (flags and HAS_Z != 0) readDoubles(buffer, n) else null
                out += CadEntity(kind, easting, northing, height, color)
            }
            require(!buffer.hasRemaining()) { "Sobran bytes al final del bloque" }
            return out
        } catch (e: java.nio.BufferUnderflowException) {
            throw IllegalArgumentException("El bloque está cortado", e)
        }
    }

    private fun writeDoubles(out: ByteArrayOutputStream, values: DoubleArray) {
        val buffer = ByteBuffer.allocate(values.size * Double.SIZE_BYTES)
        values.forEach { buffer.putDouble(it) }
        out.write(buffer.array())
    }

    private fun readDoubles(buffer: ByteBuffer, n: Int) = DoubleArray(n) { buffer.getDouble() }

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
            require(shift < 35) { "Entero demasiado largo" }
        }
    }

    private fun varintSize(value: Int): Int {
        var size = 1
        var v = value
        while (v and 0x7F.inv() != 0) {
            size++
            v = v ushr 7
        }
        return size
    }
}
