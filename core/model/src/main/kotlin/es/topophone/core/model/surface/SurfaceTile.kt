package es.topophone.core.model.surface

import java.io.ByteArrayOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Una tesela de una superficie: un trozo de su malla de triángulos con sus propios vértices, de modo que da una cota sin necesitar
 * ninguna otra tesela (S042, plan A3). Los vértices van en Este, Norte y cota del sistema del proyecto, en metros y sin redondear; cada
 * triángulo son tres posiciones de esos vértices.
 */
class SurfaceTile(val easting: DoubleArray, val northing: DoubleArray, val height: DoubleArray, val triangles: IntArray) {
    init {
        require(easting.size == northing.size && easting.size == height.size) { "Una cota y un Norte por cada Este" }
        require(triangles.size % 3 == 0) { "Cada triángulo lleva tres vértices" }
        require(triangles.all { it in easting.indices }) { "Un triángulo cita un vértice que no existe" }
    }

    val vertexCount: Int get() = easting.size
    val triangleCount: Int get() = triangles.size / 3

    /** El recuadro de los vértices de la tesela; null si no tiene ninguno. */
    val bounds: SurfaceExtent?
        get() = if (easting.isEmpty()) null else SurfaceExtent(easting.min(), easting.max(), northing.min(), northing.max())

    override fun equals(other: Any?): Boolean = other is SurfaceTile &&
        easting.contentEquals(other.easting) &&
        northing.contentEquals(other.northing) &&
        height.contentEquals(other.height) &&
        triangles.contentEquals(other.triangles)

    override fun hashCode(): Int {
        var result = easting.contentHashCode()
        result = 31 * result + northing.contentHashCode()
        result = 31 * result + height.contentHashCode()
        return 31 * result + triangles.contentHashCode()
    }

    override fun toString(): String = "SurfaceTile($vertexCount vertices, $triangleCount triangles)"
}

/**
 * El bloque binario de una tesela (S042/RF-14, plan A3): un byte de versión del formato, el número de vértices y el de triángulos
 * (enteros de longitud variable), los `Double` de Este, de Norte y de cota de cada vértice, y por triángulo tres posiciones de
 * vértice como enteros de longitud variable. Una tesela no pasa de [MAX_TILE_BYTES].
 */
object SurfaceTileCodec {
    /** Lo que puede pesar una tesela: el `CursorWindow` de Android no admite filas de más de 2 MB. */
    const val MAX_TILE_BYTES = 256 * 1024

    private const val FORMAT = 1

    /** Los bytes de [tile]; [IllegalArgumentException] si pasa de [MAX_TILE_BYTES]. */
    fun encode(tile: SurfaceTile): ByteArray {
        val size = encodedSize(tile)
        require(size <= MAX_TILE_BYTES) { "La tesela ocupa $size bytes y el límite es $MAX_TILE_BYTES" }
        val out = ByteArrayOutputStream(size)
        out.write(FORMAT)
        writeVarint(out, tile.vertexCount)
        writeVarint(out, tile.triangleCount)
        writeDoubles(out, tile.easting)
        writeDoubles(out, tile.northing)
        writeDoubles(out, tile.height)
        tile.triangles.forEach { writeVarint(out, it) }
        return out.toByteArray()
    }

    /** Lo que ocupa [encode] de una tesela con esos vértices y esos triángulos, sin construirla: decide cuándo el árbol parte una hoja. */
    fun encodedSize(vertexCount: Int, triangles: IntArray): Int =
        1 + varintSize(vertexCount) + varintSize(triangles.size / 3) + vertexCount * 3 * Double.SIZE_BYTES +
            triangles.sumOf { varintSize(it) }

    fun encodedSize(tile: SurfaceTile): Int = encodedSize(tile.vertexCount, tile.triangles)

    /** La tesela de un bloque; [IllegalArgumentException] si está cortado o no es de este formato. */
    fun decode(bytes: ByteArray): SurfaceTile {
        val buffer = ByteBuffer.wrap(bytes)
        try {
            require(buffer.get().toInt() == FORMAT) { "Formato de tesela desconocido" }
            val vertices = readVarint(buffer)
            val triangles = readVarint(buffer)
            require(vertices >= 0 && vertices.toLong() * 3 * Double.SIZE_BYTES <= buffer.remaining()) { "Vértices imposibles" }
            require(triangles >= 0 && triangles.toLong() * 3 <= buffer.remaining()) { "Triángulos imposibles" }
            val easting = readDoubles(buffer, vertices)
            val northing = readDoubles(buffer, vertices)
            val height = readDoubles(buffer, vertices)
            val indices = IntArray(triangles * 3) { readVarint(buffer) }
            require(!buffer.hasRemaining()) { "Sobran bytes al final de la tesela" }
            return SurfaceTile(easting, northing, height, indices)
        } catch (e: BufferUnderflowException) {
            throw IllegalArgumentException("La tesela está cortada", e)
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
