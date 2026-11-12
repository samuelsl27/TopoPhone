package es.topophone.core.formats.shp.export

import java.io.OutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** El tipo de una columna del `.dbf` que escribe la exportación: texto (`C`), entero (`N(w,0)`) o decimal (`N(20,9)`) (S055/RF-6, RF-7). */
enum class DbfType { TEXT, INTEGER, DECIMAL }

/** Una columna del `.dbf`: su [name], de 1 a 10 caracteres ASCII (S055/RF-8), y su [type]. */
data class DbfColumn(val name: String, val type: DbfType) {
    init {
        val validLength = name.length in 1..ShpColumnNames.MAX_LENGTH
        require(validLength && name.all { it.code in PRINTABLE_ASCII }) { "Nombre de columna no válido: $name" }
    }

    private companion object {
        val PRINTABLE_ASCII = 0x21..0x7E
    }
}

/** Lo que dice la escritura de un `.dbf`: los registros escritos y los textos que no cabían en 254 bytes y se han recortado (S055/RF-10). */
data class DbfWriteResult(val records: Int, val truncatedTexts: Int)

/**
 * Escribe un `.dbf` (dBase III) de unas [DbfColumn] y sus filas (S055/RF-6, RF-7, RF-10, RF-11, plan A2). El texto va en UTF-8 y sin
 * acentos rotos porque ningún carácter se parte al recortar; un valor vacío (`null`) es el campo entero de espacios; los decimales llevan
 * 9 decimales con el valor exacto del `double`, sin la configuración regional ni notación científica.
 */
object DbfWriter {
    /** Lo más largo que cabe en una columna de texto del formato. */
    const val MAX_TEXT_BYTES = 254

    /** Ancho de una columna decimal: 9 decimales dan 0,1 mm en grados y 1e-9 m en metros dentro de los 20 caracteres del formato (plan). */
    const val DECIMAL_WIDTH = 20
    const val DECIMAL_SCALE = 9

    /** El mayor entero con signo (`Long.MIN_VALUE`) ocupa 20 caracteres. */
    const val MAX_INTEGER_WIDTH = 20

    /** El contenido del `.cpg` que acompaña al `.dbf`: la codificación de los textos (S055/RF-11). */
    val CPG: ByteArray = "UTF-8".toByteArray(Charsets.US_ASCII)

    private const val VERSION: Byte = 0x03
    private const val DESCRIPTOR_BYTES = 32
    private const val HEADER_BYTES = 32
    private const val FIELD_TERMINATOR: Byte = 0x0D
    private const val FILE_TERMINATOR: Byte = 0x1A
    private const val NOT_DELETED: Byte = 0x20
    private const val SPACE: Byte = 0x20
    private const val MAX_FIELD_BYTES = 0xFFFF
    private const val CONTINUATION_MASK = 0xC0
    private const val CONTINUATION = 0x80
    private const val DECIMAL_TYPE = 'N'.code.toByte()
    private const val TEXT_TYPE = 'C'.code.toByte()

    /**
     * Escribe en [out] el `.dbf` de [rows] (una fila por objeto, un valor por columna en su orden: [String], [Number] o `null`) con [date]
     * como fecha de la cabecera. [out] no se cierra. Lanza [IllegalArgumentException] si un valor no es del tipo de su columna.
     */
    fun write(columns: List<DbfColumn>, rows: List<Array<Any?>>, out: OutputStream, date: LocalDate): DbfWriteResult {
        val (widths, truncated) = measure(columns, rows)
        val headerLength = HEADER_BYTES + DESCRIPTOR_BYTES * columns.size + 1
        val recordLength = 1 + widths.sum()
        require(headerLength <= MAX_FIELD_BYTES && recordLength <= MAX_FIELD_BYTES) { "Demasiadas columnas para un .dbf" }
        out.write(header(columns, widths, rows.size, headerLength, recordLength, date))
        val record = ByteArray(recordLength)
        val offsets = IntArray(columns.size)
        var at = 1
        for (i in columns.indices) {
            offsets[i] = at
            at += widths[i]
        }
        for (row in rows) {
            record.fill(SPACE)
            record[0] = NOT_DELETED
            for (i in columns.indices) put(record, offsets[i], widths[i], columns[i], row[i])
            out.write(record)
        }
        out.write(FILE_TERMINATOR.toInt())
        return DbfWriteResult(rows.size, truncated)
    }

    /** El ancho de cada columna (el del mayor valor, de 1 a 254 en el texto) y cuántos textos se recortan. */
    private fun measure(columns: List<DbfColumn>, rows: List<Array<Any?>>): Pair<IntArray, Int> {
        val widths = IntArray(columns.size) { 1 }
        var truncated = 0
        for (row in rows) {
            require(row.size == columns.size) { "Una fila tiene ${row.size} valores y hay ${columns.size} columnas" }
            for (i in columns.indices) {
                val value = row[i] ?: continue
                when (columns[i].type) {
                    DbfType.TEXT -> {
                        val raw = text(value, columns[i]).toByteArray(Charsets.UTF_8)
                        if (raw.size > MAX_TEXT_BYTES) truncated++
                        widths[i] = maxOf(widths[i], cutLength(raw))
                    }
                    DbfType.INTEGER -> widths[i] = maxOf(widths[i], minOf(integer(value, columns[i]).toString().length, MAX_INTEGER_WIDTH))
                    DbfType.DECIMAL -> Unit
                }
            }
        }
        for (i in columns.indices) if (columns[i].type == DbfType.DECIMAL) widths[i] = DECIMAL_WIDTH
        return widths to truncated
    }

    private fun header(
        columns: List<DbfColumn>,
        widths: IntArray,
        count: Int,
        headerLength: Int,
        recordLength: Int,
        date: LocalDate,
    ): ByteArray {
        val bytes = ByteArray(headerLength)
        bytes[0] = VERSION
        bytes[1] = (date.year - 1900).toByte()
        bytes[2] = date.monthValue.toByte()
        bytes[3] = date.dayOfMonth.toByte()
        littleEndian(bytes, 4, count.toLong(), 4)
        littleEndian(bytes, 8, headerLength.toLong(), 2)
        littleEndian(bytes, 10, recordLength.toLong(), 2)
        columns.forEachIndexed { i, column ->
            val at = HEADER_BYTES + DESCRIPTOR_BYTES * i
            column.name.forEachIndexed { k, c -> bytes[at + k] = c.code.toByte() }
            bytes[at + 11] = if (column.type == DbfType.TEXT) TEXT_TYPE else DECIMAL_TYPE
            bytes[at + 16] = widths[i].toByte()
            bytes[at + 17] = (if (column.type == DbfType.DECIMAL) DECIMAL_SCALE else 0).toByte()
        }
        bytes[headerLength - 1] = FIELD_TERMINATOR
        return bytes
    }

    private fun put(record: ByteArray, offset: Int, width: Int, column: DbfColumn, value: Any?) {
        if (value == null) return
        when (column.type) {
            DbfType.TEXT -> {
                val raw = text(value, column).toByteArray(Charsets.UTF_8)
                System.arraycopy(raw, 0, record, offset, cutLength(raw))
            }
            DbfType.INTEGER -> rightAlign(record, offset, width, integer(value, column).toString())
            DbfType.DECIMAL -> decimalText(decimal(value, column))?.let { rightAlign(record, offset, width, it) }
        }
    }

    private fun rightAlign(record: ByteArray, offset: Int, width: Int, text: String) {
        val start = offset + width - text.length
        text.forEachIndexed { k, c -> record[start + k] = c.code.toByte() }
    }

    /** Cuántos de los bytes de [raw] se escriben: todos hasta 254 y, si no caben, los que no parten un carácter UTF-8. */
    internal fun cutLength(raw: ByteArray): Int {
        if (raw.size <= MAX_TEXT_BYTES) return raw.size
        var cut = MAX_TEXT_BYTES
        // El byte que queda fuera es de continuación: el carácter se partiría, así que se deja entero fuera.
        while (cut > 0 && (raw[cut].toInt() and CONTINUATION_MASK) == CONTINUATION) cut--
        return cut
    }

    /**
     * [value] con 9 decimales, o los que quepan en 20 caracteres, con el valor exacto del `double` y las mitades al par; `null` si no es
     * un número finito o no cabe ni sin decimales. No lleva cero negativo ni notación científica, y no depende de la configuración regional.
     */
    internal fun decimalText(value: Double): String? {
        if (!value.isFinite()) return null
        val exact = BigDecimal(value)
        for (scale in DECIMAL_SCALE downTo 0) {
            val text = exact.setScale(scale, RoundingMode.HALF_EVEN).toPlainString()
            if (text.length <= DECIMAL_WIDTH) return text
        }
        return null
    }

    private fun text(value: Any, column: DbfColumn): String =
        value as? String ?: throw IllegalArgumentException("La columna ${column.name} es de texto y recibe ${value.javaClass.simpleName}")

    private fun integer(value: Any, column: DbfColumn): Long = (value as? Number)?.takeIf { it !is Double && it !is Float }?.toLong()
        ?: throw IllegalArgumentException("La columna ${column.name} es entera y recibe ${value.javaClass.simpleName}")

    private fun decimal(value: Any, column: DbfColumn): Double = (value as? Number)?.toDouble()
        ?: throw IllegalArgumentException("La columna ${column.name} es decimal y recibe ${value.javaClass.simpleName}")

    private fun littleEndian(bytes: ByteArray, at: Int, value: Long, size: Int) {
        for (k in 0 until size) bytes[at + k] = (value shr (8 * k)).toByte()
    }
}
