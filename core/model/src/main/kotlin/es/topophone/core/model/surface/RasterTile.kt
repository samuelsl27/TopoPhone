package es.topophone.core.model.surface

import java.nio.ByteBuffer

/**
 * Un bloque de un ráster de cotas: [EDGE] × [EDGE] celdas en orden de filas, de norte a sur y de oeste a este (S045/RF-14). Una celda
 * sin dato es `NaN`; las celdas del bloque que quedan fuera del ráster (en el borde derecho o inferior) también. Los valores van en la
 * unidad del fichero y sin redondear: float32 para enteros y reales de 32 bits, y float64 solo si el fichero trae reales de 64.
 */
class RasterTile private constructor(val valueType: RasterValueType, private val floats: FloatArray?, private val doubles: DoubleArray?) {

    /** El valor de la celda en la columna [col] y la fila [row] del bloque; `NaN` si no tiene dato. */
    fun valueAt(col: Int, row: Int): Double {
        val k = row * EDGE + col
        return if (floats != null) floats[k].toDouble() else doubles!![k]
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RasterTile || valueType != other.valueType) return false
        return if (floats != null) {
            other.floats != null && (0 until CELLS).all { floats[it].toRawBits() == other.floats[it].toRawBits() }
        } else {
            other.doubles != null && (0 until CELLS).all { doubles!![it].toRawBits() == other.doubles[it].toRawBits() }
        }
    }

    override fun hashCode(): Int = if (floats != null) floats.contentHashCode() else doubles.contentHashCode()

    override fun toString(): String = "RasterTile($valueType)"

    internal fun writeTo(buffer: ByteBuffer) {
        if (floats != null) floats.forEach { buffer.putFloat(it) } else doubles!!.forEach { buffer.putDouble(it) }
    }

    companion object {
        /** El lado de un bloque en celdas: de 128 × 128 celdas son 64 kB en float32 y 128 kB en float64. */
        const val EDGE = 128

        const val CELLS = EDGE * EDGE

        fun ofFloats(values: FloatArray): RasterTile {
            require(values.size == CELLS) { "Un bloque tiene $CELLS celdas y no ${values.size}" }
            return RasterTile(RasterValueType.FLOAT32, values, null)
        }

        fun ofDoubles(values: DoubleArray): RasterTile {
            require(values.size == CELLS) { "Un bloque tiene $CELLS celdas y no ${values.size}" }
            return RasterTile(RasterValueType.FLOAT64, null, values)
        }
    }
}

/**
 * El bloque binario de un ráster (S045/RF-14, plan «Bloque»): un byte de versión del formato, uno con el tipo de valor y las
 * [RasterTile.CELLS] celdas en orden de filas, como `Float` o `Double`, sin redondear.
 */
object RasterTileCodec {
    /** Lo que puede pesar un bloque: el `CursorWindow` de Android no admite filas de más de 2 MB. */
    const val MAX_TILE_BYTES = 2 + RasterTile.CELLS * Double.SIZE_BYTES

    private const val FORMAT = 1
    private const val FLOAT32 = 0
    private const val FLOAT64 = 1

    fun encode(tile: RasterTile): ByteArray {
        val width = if (tile.valueType == RasterValueType.FLOAT32) Float.SIZE_BYTES else Double.SIZE_BYTES
        val buffer = ByteBuffer.allocate(2 + RasterTile.CELLS * width)
        buffer.put(FORMAT.toByte())
        buffer.put((if (tile.valueType == RasterValueType.FLOAT32) FLOAT32 else FLOAT64).toByte())
        tile.writeTo(buffer)
        return buffer.array()
    }

    /** El bloque de unos bytes; [IllegalArgumentException] si están cortados, sobran o no son de este formato. */
    fun decode(bytes: ByteArray): RasterTile {
        require(bytes.size >= 2) { "El bloque está cortado" }
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.get().toInt() == FORMAT) { "Formato de bloque desconocido" }
        val type = buffer.get().toInt()
        val width = when (type) {
            FLOAT32 -> Float.SIZE_BYTES
            FLOAT64 -> Double.SIZE_BYTES
            else -> throw IllegalArgumentException("Tipo de valor desconocido")
        }
        require(buffer.remaining() == RasterTile.CELLS * width) {
            "El bloque tiene ${buffer.remaining()} bytes y debería tener ${RasterTile.CELLS * width}"
        }
        return if (type == FLOAT32) {
            RasterTile.ofFloats(FloatArray(RasterTile.CELLS) { buffer.getFloat() })
        } else {
            RasterTile.ofDoubles(DoubleArray(RasterTile.CELLS) { buffer.getDouble() })
        }
    }
}
