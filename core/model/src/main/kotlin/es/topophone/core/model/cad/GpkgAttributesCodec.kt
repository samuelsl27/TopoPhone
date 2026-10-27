package es.topophone.core.model.cad

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Los atributos de los objetos de un trozo, tal como los lee la ficha de un objeto (S048/RF-20). */
class GpkgAttributeBlock(val columns: List<String>, private val rows: List<List<GpkgValue>>) {
    val rowCount: Int get() = rows.size

    /** Los valores del elemento [index] del trozo, por nombre de columna y en el orden de las columnas. */
    fun row(index: Int): Map<String, GpkgValue> = columns.zip(rows[index]).toMap(LinkedHashMap())
}

/**
 * El bloque binario con los atributos de los elementos de un trozo, en el mismo orden que los elementos del trozo (S048/RF-20).
 * Empieza por la tabla de nombres de columna (cuántas y cada una en UTF-8 con su longitud) y sigue, por elemento, un valor por
 * columna: un byte de tipo y el valor. Todos los elementos de una capa comparten las columnas, así que los nombres van una vez por
 * bloque. Un trozo de un DXF no tiene bloque.
 */
object GpkgAttributesCodec {
    private const val NULL = 0
    private const val INTEGER = 1
    private const val REAL = 2
    private const val TEXT = 3
    private const val DATE = 4
    private const val BOOL = 5
    private const val BLOB = 6
    private const val OMITTED = 7

    /** Los valores de un elemento, en el orden de las columnas de su capa. */
    fun encodeRow(values: List<GpkgValue>): ByteArray {
        val out = ByteArrayOutputStream(values.size * 9)
        for (value in values) {
            when (value) {
                GpkgValue.Null -> out.write(NULL)
                is GpkgValue.Integer -> out.write(INTEGER).also { out.write(ByteBuffer.allocate(8).putLong(value.value).array()) }
                is GpkgValue.Real -> out.write(REAL).also { out.write(ByteBuffer.allocate(8).putDouble(value.value).array()) }
                is GpkgValue.Text -> writeText(out, TEXT, value.value)
                is GpkgValue.Date -> writeText(out, DATE, value.value)
                is GpkgValue.Bool -> out.write(BOOL).also { out.write(if (value.value) 1 else 0) }
                is GpkgValue.Blob -> out.write(BLOB).also { writeVarint(out, value.bytes.size) }.also { out.write(value.bytes) }
                is GpkgValue.Omitted -> out.write(OMITTED).also { writeVarint(out, value.bytes) }
            }
        }
        return out.toByteArray()
    }

    /** El bloque de un trozo: los nombres de [columns] y las filas ya codificadas con [encodeRow]. */
    fun encodeBlock(columns: List<String>, rows: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(rows.sumOf { it.size } + columns.sumOf { it.length + 2 } + 8)
        writeVarint(out, columns.size)
        for (column in columns) {
            val bytes = column.toByteArray(Charsets.UTF_8)
            writeVarint(out, bytes.size)
            out.write(bytes)
        }
        writeVarint(out, rows.size)
        rows.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Lo que ocupa [encodeBlock] con esas columnas y filas, sin construirlo. */
    fun blockSize(columns: List<String>, rows: List<ByteArray>): Int =
        varintSize(columns.size) + columns.sumOf { varintSize(it.toByteArray(Charsets.UTF_8).size) + it.toByteArray(Charsets.UTF_8).size } +
            varintSize(rows.size) + rows.sumOf { it.size }

    /** El bloque entero; [IllegalArgumentException] si está cortado o no es de este formato. */
    fun decode(bytes: ByteArray): GpkgAttributeBlock {
        val buffer = ByteBuffer.wrap(bytes)
        try {
            val columnCount = readVarint(buffer)
            require(columnCount in 0..bytes.size) { "Número de columnas imposible" }
            val columns = List(columnCount) { readText(buffer) }
            val rowCount = readVarint(buffer)
            require(rowCount in 0..bytes.size) { "Número de filas imposible" }
            val rows = List(rowCount) { List(columnCount) { readValue(buffer) } }
            require(!buffer.hasRemaining()) { "Sobran bytes al final del bloque" }
            return GpkgAttributeBlock(columns, rows)
        } catch (e: java.nio.BufferUnderflowException) {
            throw IllegalArgumentException("El bloque está cortado", e)
        }
    }

    private fun readValue(buffer: ByteBuffer): GpkgValue = when (val type = buffer.get().toInt()) {
        NULL -> GpkgValue.Null
        INTEGER -> GpkgValue.Integer(buffer.getLong())
        REAL -> GpkgValue.Real(buffer.getDouble())
        TEXT -> GpkgValue.Text(readText(buffer))
        DATE -> GpkgValue.Date(readText(buffer))
        BOOL -> GpkgValue.Bool(buffer.get().toInt() != 0)
        BLOB -> {
            val size = readVarint(buffer)
            require(size in 0..buffer.remaining()) { "Blob imposible" }
            GpkgValue.Blob(ByteArray(size).also { buffer.get(it) })
        }
        OMITTED -> GpkgValue.Omitted(readVarint(buffer))
        else -> throw IllegalArgumentException("Tipo de valor desconocido: $type")
    }

    private fun writeText(out: ByteArrayOutputStream, type: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write(type)
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    private fun readText(buffer: ByteBuffer): String {
        val size = readVarint(buffer)
        require(size in 0..buffer.remaining()) { "Texto imposible" }
        return ByteArray(size).also { buffer.get(it) }.toString(Charsets.UTF_8)
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
