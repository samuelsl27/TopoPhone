package es.topophone.core.formats.csv

import es.topophone.core.model.point.ImportDecimal

/**
 * Lee un número del fichero con el signo decimal elegido (S028/RF-7, RF-8, RF-23): signo opcional, dígitos y, a lo sumo, un signo
 * decimal. No admite separador de miles, notación científica ni letras: "1.234,56" no es un número con la coma decimal. El valor
 * no se redondea.
 */
object ImportNumber {
    fun parse(text: String, decimal: ImportDecimal): Double? {
        val cell = text.trim()
        var start = 0
        if (cell.isNotEmpty() && (cell[0] == '-' || cell[0] == '+')) start = 1
        var digits = 0
        var separators = 0
        for (i in start until cell.length) {
            val c = cell[i]
            when {
                c in '0'..'9' -> digits++
                c == decimal.char -> separators++
                else -> return null
            }
        }
        if (digits == 0 || separators > 1) return null
        val normalized = cell.replace(decimal.char, '.')
        return normalized.toDoubleOrNull()
    }
}
