package es.topophone.core.formats.csv

import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportSeparator

/** El signo decimal más probable de un fichero (S028/RF-7, RF-8). */
object DecimalDetector {
    /** Las filas que se miran. */
    private const val ROWS = 200

    private val commaNumber = Regex("""[+-]?\d+,\d+""")
    private val pointNumber = Regex("""[+-]?\d+\.\d+""")

    /**
     * Mira las celdas que son un número con decimales en las columnas que no son la primera (la del nombre): si alguna lleva coma y
     * ninguna punto, es la coma; si no, el punto. Con la coma de separador de columnas, siempre el punto (RF-8). Las celdas con
     * punto y coma a la vez ("440.290,458") no cuentan: no son un número.
     */
    fun detect(rows: Iterable<DelimitedRow>, separator: ImportSeparator): ImportDecimal {
        if (separator == ImportSeparator.COMMA) return ImportDecimal.POINT
        var comma = false
        var point = false
        rows.asSequence().take(ROWS).forEach { row ->
            row.cells.drop(1).forEach { cell ->
                val text = cell.trim()
                if (commaNumber.matches(text)) comma = true
                if (pointNumber.matches(text)) point = true
            }
        }
        return if (comma && !point) ImportDecimal.COMMA else ImportDecimal.POINT
    }
}
