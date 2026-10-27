package es.topophone.core.model.cad

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Un elemento del dibujo con sus vértices en el sistema del proyecto, sin redondear (S040/RF-19). [height] es null si el
 * elemento no trae Z (RF-23); [color] es null si es el de su capa (RF-24). [original] marca qué vértices están escritos en el
 * DXF y cuáles son de la aproximación de una curva (S040/RF-34); null es que todos son originales. [name] es el nombre del
 * objeto de un GeoPackage (S048/RF-22) y [polygonRing] marca la línea que es el exterior o un hueco de un polígono (S048/RF-17).
 */
class CadEntity(
    val kind: CadEntityKind,
    val easting: DoubleArray,
    val northing: DoubleArray,
    val height: DoubleArray?,
    val color: CadColor?,
    original: BooleanArray? = null,
    val name: String? = null,
    val polygonRing: Boolean = false,
) {
    // Sin marcas es un elemento de antes de S041, o uno sin curvas: todos sus vértices son originales. Un arco, un círculo o una
    // spline siempre las traen, aunque todas valgan "original", para distinguirlos de un dibujo importado antes (S041/RF-34).
    private val marks: BooleanArray? = original

    init {
        require(original == null || original.size == easting.size) { "Una marca por vértice, o ninguna" }
        require(easting.size == northing.size && easting.isNotEmpty()) { "Los vértices necesitan Este y Norte" }
        require(height == null || height.size == easting.size) { "Una altura por vértice, o ninguna" }
    }

    val vertexCount: Int get() = easting.size
    val hasZ: Boolean get() = height != null

    /** El elemento guarda qué vértices son originales (S040/RF-34); si no, todos lo son. */
    val hasOriginalMarks: Boolean get() = marks != null

    /** El vértice [index] está escrito en el DXF (S040/RF-34, S041/RF-33). */
    fun isOriginal(index: Int): Boolean = marks?.get(index) ?: true

    /** El recuadro del elemento (RF-29). */
    val bounds: CadExtent
        get() = CadExtent(easting.min(), easting.max(), northing.min(), northing.max())

    /** El mismo elemento con otros vértices y alturas, con su tipo, su color y las marcas de vértice original. */
    fun withVertices(easting: DoubleArray, northing: DoubleArray, height: DoubleArray?) =
        CadEntity(kind, easting, northing, height, color, marks?.copyOf(), name, polygonRing)

    /** Los vértices de [from] (incluido) a [to] (excluido) como un elemento nuevo, con sus marcas. */
    fun slice(from: Int, to: Int) = CadEntity(
        kind,
        easting.copyOfRange(from, to),
        northing.copyOfRange(from, to),
        height?.copyOfRange(from, to),
        color,
        marks?.copyOfRange(from, to),
        name,
        polygonRing,
    )

    override fun equals(other: Any?): Boolean = other is CadEntity &&
        kind == other.kind &&
        color == other.color &&
        easting.contentEquals(other.easting) &&
        northing.contentEquals(other.northing) &&
        height.contentEquals(other.height) &&
        marks.contentEquals(other.marks) &&
        name == other.name &&
        polygonRing == other.polygonRing

    override fun hashCode(): Int {
        var result = kind.hashCode()
        result = 31 * result + easting.contentHashCode()
        result = 31 * result + northing.contentHashCode()
        result = 31 * result + height.contentHashCode()
        result = 31 * result + marks.contentHashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + polygonRing.hashCode()
        return 31 * result + (color?.hashCode() ?: 0)
    }

    override fun toString(): String = "CadEntity($kind, $vertexCount vertices, z=$hasZ, color=$color)"
}

/**
 * El bloque binario de un trozo del dibujo (S040/RF-19, RF-22, RF-23). Por elemento: tipo (1 byte), marcas (1 byte: con altura,
 * con color propio, color de contraste), el color ARGB si lo trae (4 bytes), el número de vértices (entero de longitud variable)
 * y los `Double` de Este y Norte de cada vértice, los de la altura si la lleva y, si hay vértices de la aproximación de una curva
 * (S040/RF-34), un bit por vértice (1 = original) empaquetado en bytes. Con la marca de nombre (S048/RF-22) el nombre en UTF-8, con su
 * longitud en un entero de longitud variable, tras todo lo anterior; la marca de contorno (S048/RF-17) no añade bytes. Un trozo de antes
 * de las marcas se lee con todo original, sin nombre y sin contorno. Un trozo no pasa de [MAX_CHUNK_BYTES].
 */
object CadChunkCodec {
    /** Lo que puede pesar un trozo: el `CursorWindow` de Android no admite filas de más de 2 MB. */
    const val MAX_CHUNK_BYTES = 256 * 1024

    private const val HAS_Z = 1
    private const val HAS_COLOR = 2
    private const val AUTO_CONTRAST = 4
    private const val ORIGINAL_MARKS = 8
    private const val NAME = 16
    private const val POLYGON_RING = 32

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
            if (e.hasOriginalMarks) flags = flags or ORIGINAL_MARKS
            if (e.name != null) flags = flags or NAME
            if (e.polygonRing) flags = flags or POLYGON_RING
            out.write(flags)
            if (color != null && !color.autoContrast) out.write(ByteBuffer.allocate(4).putInt(color.argb).array())
            writeVarint(out, e.vertexCount)
            writeDoubles(out, e.easting)
            writeDoubles(out, e.northing)
            e.height?.let { writeDoubles(out, it) }
            if (e.hasOriginalMarks) writeMarks(out, e)
            e.name?.let { name ->
                val bytes = name.toByteArray(Charsets.UTF_8)
                writeVarint(out, bytes.size)
                out.write(bytes)
            }
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
        val marksBytes = if (e.hasOriginalMarks) (e.vertexCount + 7) / 8 else 0
        val nameBytes = e.name?.let {
            val size = it.toByteArray(Charsets.UTF_8).size
            varintSize(size) + size
        } ?: 0
        return 2 + colorBytes + varintSize(e.vertexCount) + doubles + marksBytes + nameBytes
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
                val original = if (flags and ORIGINAL_MARKS != 0) readMarks(buffer, n) else null
                val name = if (flags and NAME != 0) readName(buffer) else null
                out += CadEntity(kind, easting, northing, height, color, original, name, flags and POLYGON_RING != 0)
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

    private fun writeMarks(out: ByteArrayOutputStream, e: CadEntity) {
        val bytes = ByteArray((e.vertexCount + 7) / 8)
        for (i in 0 until e.vertexCount) if (e.isOriginal(i)) bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (i % 8))).toByte()
        out.write(bytes)
    }

    private fun readMarks(buffer: ByteBuffer, n: Int): BooleanArray {
        val bytes = ByteArray((n + 7) / 8).also { buffer.get(it) }
        return BooleanArray(n) { bytes[it / 8].toInt() and (1 shl (it % 8)) != 0 }
    }

    private fun readName(buffer: ByteBuffer): String {
        val size = readVarint(buffer)
        require(size >= 0 && size <= buffer.remaining()) { "Nombre imposible" }
        return ByteArray(size).also { buffer.get(it) }.toString(Charsets.UTF_8)
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
