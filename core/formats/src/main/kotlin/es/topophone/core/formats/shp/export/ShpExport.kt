package es.topophone.core.formats.shp.export

import es.topophone.core.model.project.HeightSystem

/** En qué sistema se escriben las coordenadas: el del proyecto o las geográficas de su datum (S055/RF-12, RF-13, RF-23). */
enum class ShpCoordinates { PROJECTED, GEOGRAPHIC }

/** Lo que elige el usuario al exportar a Shapefile (S055/RF-12, RF-13). */
data class ShpExportOptions(val coordinates: ShpCoordinates = ShpCoordinates.PROJECTED)

/** Qué hay en una capa de la exportación, y de ahí el sufijo de su nombre (S055/RF-3, RF-4). */
enum class ShpLayerKind(val suffix: String, val type: ShpType) {
    POINTS("_puntos", ShpType.POINT_Z),
    LINES("_lineas", ShpType.POLYLINE_Z),
    POLYGONS("_poligonos", ShpType.POLYGON_Z),
}

/**
 * Una capa lista para escribir: [columns] y una fila en [rows] por cada objeto de [geometries], en el mismo orden, y el contenido de su
 * `.prj` ([prj], `null` si el sistema no tiene definición: S055/RF-16).
 */
class ShpLayer(
    val kind: ShpLayerKind,
    val columns: List<DbfColumn>,
    val rows: List<Array<Any?>>,
    val geometries: List<ShpGeometry>,
    val prj: String?,
)

/**
 * Lo que dice el resumen al terminar (S055/RF-15, RF-22): los objetos escritos, los puntos sin posición, los vértices sin altura (con Z = 0),
 * los textos recortados a 254 bytes, los atributos de código que han cambiado de nombre, si falta el `.prj`, el sistema declarado
 * ([epsg], de las coordenadas escritas) y el sistema de alturas de la Z, que el `.prj` no puede declarar ([heightSystem]).
 */
data class ShpExportSummary(
    val points: Int = 0,
    val lines: Int = 0,
    val polygons: Int = 0,
    val pointsWithoutPosition: Int = 0,
    val verticesWithoutHeight: Int = 0,
    val truncatedTexts: Int = 0,
    val renamedAttributes: List<ShpRenamedColumn> = emptyList(),
    val prjMissing: Boolean = false,
    val geographic: Boolean = false,
    val epsg: Int = 0,
    val heightSystem: HeightSystem = HeightSystem.ORTHOMETRIC,
)

/** Las capas que se escriben (ninguna vacía) y el [summary] que sale de prepararlas; los textos recortados los cuenta la escritura. */
class ShpExport(val layers: List<ShpLayer>, val summary: ShpExportSummary) {
    /** No hay nada que escribir (S055/RF-19). */
    val isEmpty: Boolean get() = layers.isEmpty()
}
