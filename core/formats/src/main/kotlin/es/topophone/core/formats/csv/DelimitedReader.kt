package es.topophone.core.formats.csv

import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.ImportSeparator
import java.io.Reader
import java.io.StringReader

/** Qué límite pasa un fichero y no se abre el asistente (S028/RF-3). */
enum class ImportLimit { TOO_BIG, TOO_MANY_ROWS }

/**
 * La lectura por flujo de las filas de un CSV o TXT (S028/RF-3, RF-5, RF-6): los cuatro separadores, los textos entre comillas
 * dobles con las comillas duplicadas dentro (como S012/RF-15) y CRLF, LF o CR como fin de fila. Las filas vacías se saltan.
 */
object DelimitedReader {
    const val MAX_BYTES: Long = 20L * 1024 * 1024
    const val MAX_ROWS: Int = 50_000

    /** Las filas que se miran para detectar el separador. */
    private const val DETECTION_ROWS = 20

    /** El orden de preferencia en un empate: la coma suele ser decimal, así que va casi la última. */
    private val DETECTION_ORDER = listOf(ImportSeparator.SEMICOLON, ImportSeparator.TAB, ImportSeparator.COMMA, ImportSeparator.SPACES)

    private const val EOF = -1

    /** Las filas no vacías de [reader], leídas a medida que se piden. Una comilla solo abre un texto al principio de la celda. */
    fun rows(reader: Reader, separator: ImportSeparator): Sequence<DelimitedRow> = sequence {
        val sep = separator.char
        val merge = separator.mergesRuns
        var pending = EOF
        var hasPending = false

        fun next(): Int {
            if (hasPending) {
                hasPending = false
                return pending
            }
            return reader.read()
        }

        fun push(c: Int) {
            pending = c
            hasPending = true
        }

        var line = 1
        var rowLine = 1
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false // la celda actual venía entre comillas
        var closed = false // y ya se han cerrado
        var inQuotes = false
        var any = false // la fila lleva algo

        fun endCell() {
            cells += if (quoted) cell.toString() else cell.toString().trim()
            cell.setLength(0)
            quoted = false
            closed = false
        }

        fun endRow(): DelimitedRow? {
            // Con espacios, uno de más al final no hace una celda vacía; con los demás separadores, la coma final sí.
            if (cell.isNotEmpty() || quoted || (!merge && cells.isNotEmpty())) endCell()
            val blank = cells.isEmpty() || (cells.size == 1 && cells[0].isBlank() && !any)
            val row = if (blank) null else DelimitedRow(rowLine, cells.toList())
            cells.clear()
            any = false
            return row
        }

        while (true) {
            val c = next()
            if (c == EOF) break
            val ch = c.toChar()
            if (inQuotes) {
                when {
                    ch == '"' -> {
                        val following = next()
                        if (following == '"'.code) {
                            cell.append('"')
                        } else {
                            inQuotes = false
                            closed = true
                            if (following != EOF) push(following)
                        }
                    }
                    ch == '\r' || ch == '\n' -> {
                        if (ch == '\r') {
                            val following = next()
                            if (following != '\n'.code && following != EOF) push(following)
                        }
                        cell.append('\n')
                        line++
                    }
                    else -> cell.append(ch)
                }
                continue
            }
            when {
                ch == '\r' || ch == '\n' -> {
                    if (ch == '\r') {
                        val following = next()
                        if (following != '\n'.code && following != EOF) push(following)
                    }
                    val row = endRow()
                    if (row != null) yield(row)
                    line++
                    rowLine = line
                }
                ch == '"' && !closed && !quoted && cell.isBlank() -> {
                    cell.setLength(0)
                    quoted = true
                    inQuotes = true
                    any = true
                }
                ch == sep -> {
                    if (merge) {
                        // Varios espacios seguidos, y los del principio y el final, no hacen celdas vacías.
                        if (cell.isNotEmpty() || quoted) endCell()
                    } else {
                        endCell()
                        any = true
                    }
                }
                closed && ch.isWhitespace() -> Unit
                else -> {
                    cell.append(ch)
                    if (!ch.isWhitespace()) any = true
                }
            }
        }
        val last = endRow()
        if (last != null) yield(last)
    }

    /**
     * El separador más probable de un texto (S028/RF-5): sobre sus primeras 20 filas no vacías, el que da el mismo número de columnas
     * (al menos 2) en más filas, sin contar lo de dentro de comillas. Con empate, `;`, tabulador, `,` y espacio, en ese orden.
     */
    fun detectSeparator(sample: String): ImportSeparator {
        var best = ImportSeparator.COMMA
        var bestScore = 0
        DETECTION_ORDER.forEach { candidate ->
            val columns = rows(StringReader(sample), candidate).take(DETECTION_ROWS).map { it.cells.size }.toList()
            val modal = columns.filter { it >= 2 }.groupingBy { it }.eachCount().maxByOrNull { it.value }
            val score = modal?.value ?: 0
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }

    /** Qué límite pasa el fichero: [sizeBytes] si se conoce y [rows] filas (S028/RF-3). */
    fun limitExceeded(sizeBytes: Long?, rows: Int): ImportLimit? = when {
        sizeBytes != null && sizeBytes > MAX_BYTES -> ImportLimit.TOO_BIG
        rows > MAX_ROWS -> ImportLimit.TOO_MANY_ROWS
        else -> null
    }

    /** Cuenta las filas no vacías, y para en cuanto pasan de [MAX_ROWS] (devuelve entonces [MAX_ROWS] + 1) (S028/RF-3). */
    fun countRows(reader: Reader, separator: ImportSeparator): Int = rows(reader, separator).take(MAX_ROWS + 1).count()
}
