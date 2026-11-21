package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.geodesy.GeodeticPoint

/** Una fila de `fixtures/geodesia/rejilla_desplazamiento.csv`: `forward` e `inverse` son `null` donde PROJ no da valor (fuera de la rejilla). */
internal data class GridShiftVector(
    val group: String,
    val input: GeodeticPoint,
    val forward: GeodeticPoint?,
    val inverse: GeodeticPoint?,
) {
    companion object {
        const val GRID_NAME = "es_ign_SPED2ETV2.tif"

        fun all(): List<GridShiftVector> = GeodesyFixtures.rows("rejilla_desplazamiento.csv").map(::parse)

        private fun parse(row: List<String>): GridShiftVector {
            fun point(latIndex: Int): GeodeticPoint? =
                if (row[latIndex].isEmpty()) null else GeodeticPoint(row[latIndex].toDouble(), row[latIndex + 1].toDouble(), HEIGHT_M)
            return GridShiftVector(row[0], GeodeticPoint(row[1].toDouble(), row[2].toDouble(), HEIGHT_M), point(3), point(5))
        }

        /** Los vectores se calculan en 2D: la rejilla no toca la altura y se prueba con una cualquiera. */
        private const val HEIGHT_M = 123.456
    }
}
