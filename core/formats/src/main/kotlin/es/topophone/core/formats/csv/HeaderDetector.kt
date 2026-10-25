package es.topophone.core.formats.csv

import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportNumber

/** Si la primera fila de un fichero es una cabecera (S028/RF-9). */
object HeaderDetector {
    /** Las columnas que serían coordenadas si no hay otra asignación: la segunda y la tercera. */
    val DEFAULT_COORDINATE_COLUMNS = listOf(1, 2)

    /**
     * Es cabecera si ninguna de las celdas que serían coordenadas es un número, con cualquiera de los dos signos decimales. Una fila
     * que no llega a esas columnas no se toma por cabecera.
     */
    fun isHeader(firstRow: List<String>, coordinateColumns: List<Int> = DEFAULT_COORDINATE_COLUMNS): Boolean {
        val cells = coordinateColumns.mapNotNull { firstRow.getOrNull(it) }
        if (cells.isEmpty()) return false
        return cells.none { ImportNumber.parse(it, ImportDecimal.POINT) != null || ImportNumber.parse(it, ImportDecimal.COMMA) != null }
    }
}
