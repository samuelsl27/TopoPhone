package es.topophone.core.formats.dxf.export

import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.Point
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project

/** Las etiquetas que se escriben junto a cada punto (S029/RF-7). */
enum class DxfLabel { NAME, ELEVATION, DESCRIPTION }

/**
 * Lo que el usuario elige al exportar (S029/RF-7, RF-8, RF-11, RF-12). [textHeightM] va en metros, de [MIN_TEXT_HEIGHT_M] a
 * [MAX_TEXT_HEIGHT_M].
 */
data class DxfExportOptions(
    val labels: Set<DxfLabel> = setOf(DxfLabel.NAME, DxfLabel.ELEVATION),
    val textHeightM: Double = DEFAULT_TEXT_HEIGHT_M,
    val coordinates: DxfCoordinates = DxfCoordinates.LOCAL,
) {
    companion object {
        const val MIN_TEXT_HEIGHT_M = 0.01
        const val MAX_TEXT_HEIGHT_M = 100.0
        const val DEFAULT_TEXT_HEIGHT_M = 0.5
    }
}

/** La unidad en que van las coordenadas del fichero, que el DXF R12 no puede declarar (S029/RF-14). */
enum class DxfUnit { METRE, INTERNATIONAL_FOOT, US_SURVEY_FOOT, DEGREE }

/**
 * El resumen de lo exportado (S029/RF-20): [layers] cuenta las capas sin la 0; [pointsWithoutHeight] y [verticesWithoutHeight] van con Z = 0
 * (RF-9); [pointsWithoutPosition] no se escriben porque un punto del DXF sin posición no existe (plan D10); [replacedChars] los fija el
 * escritor (RF-15).
 */
data class DxfExportSummary(
    val points: Int,
    val lines: Int,
    val polygons: Int,
    val layers: Int,
    val pointsWithoutHeight: Int,
    val verticesWithoutHeight: Int,
    val pointsWithoutPosition: Int,
    val replacedChars: Int,
    val unit: DxfUnit,
    val coordinates: DxfCoordinates,
)

class DxfBuild(val document: DxfDocument, val summary: DxfExportSummary)

/**
 * Construye el [DxfDocument] y su resumen a partir de los puntos, las líneas y las opciones (S029/RF-1 a RF-14, RF-20). Los vértices de las
 * líneas salen de [allPoints] con sus coordenadas actuales. No modifica nada (RF-21).
 */
object DxfDocumentBuilder {
    private const val METRES_PER_DEGREE = 111_320.0
    private const val NAME_OFFSET = 0.5
    private const val ELEVATION_OFFSET = -1.5
    private const val DESCRIPTION_OFFSET = -3.0
    private const val MIN_OPEN_VERTICES = 2
    private const val MIN_CLOSED_VERTICES = 3

    private class Position(val x: Double, val y: Double, val z: Double, val hasHeight: Boolean)

    fun build(
        points: List<Point>,
        lines: List<ProjectLine>,
        allPoints: List<Point>,
        project: Project,
        options: DxfExportOptions,
    ): DxfBuild {
        // RF-13: un proyecto geográfico solo tiene globales.
        val coordinates = if (project.crs.isUtm) options.coordinates else DxfCoordinates.GLOBAL
        val unit = project.linearUnit
        val names = DxfLayerNames()
        val colors = LinkedHashMap<String, Int>()
        fun layerOf(code: PointCode?): String {
            val layer = names.layerOf(code?.code)
            if (layer !in colors) {
                colors[layer] =
                    if (code == null || layer == DxfLayerNames.NO_CODE) AciColor.DEFAULT else AciColor.nearestOrDefault(code.color)
            }
            return layer
        }

        val pointEntities = ArrayList<DxfEntity>()
        val textEntities = ArrayList<DxfEntity>()
        val heightM = options.textHeightM.coerceIn(DxfExportOptions.MIN_TEXT_HEIGHT_M, DxfExportOptions.MAX_TEXT_HEIGHT_M)
        val textHeight = textHeight(heightM, coordinates, unit)
        var withoutHeight = 0
        var withoutPosition = 0
        for (point in points) {
            val position = position(point, coordinates, unit)
            if (position == null) {
                withoutPosition++
                continue
            }
            if (!position.hasHeight) withoutHeight++
            pointEntities += DxfPointEntity(layerOf(point.code), position.x, position.y, position.z)
            textEntities += labels(point, position, options.labels, textHeight)
        }

        val pointsById = allPoints.associateBy { it.id }
        val polylines = ArrayList<DxfEntity>()
        var lineCount = 0
        var polygonCount = 0
        var verticesWithoutHeight = 0
        for (line in lines) {
            val found = line.vertices.mapNotNull { vertex -> pointsById[vertex.pointId]?.let { position(it, coordinates, unit) } }
            if (found.size < (if (line.closed) MIN_CLOSED_VERTICES else MIN_OPEN_VERTICES)) continue
            verticesWithoutHeight += found.count { !it.hasHeight }
            polylines += DxfPolylineEntity(layerOf(line.code), line.closed, found.map { DxfVertex(it.x, it.y, it.z) })
            if (line.closed) polygonCount++ else lineCount++
        }

        val entities = pointEntities + textEntities + polylines
        val layers = layerDefs(colors, entities)
        val summary = DxfExportSummary(
            points = pointEntities.size,
            lines = lineCount,
            polygons = polygonCount,
            layers = layers.size - 1,
            pointsWithoutHeight = withoutHeight,
            verticesWithoutHeight = verticesWithoutHeight,
            pointsWithoutPosition = withoutPosition,
            replacedChars = 0,
            unit = if (coordinates == DxfCoordinates.GLOBAL) DxfUnit.DEGREE else unitOf(unit),
            coordinates = coordinates,
        )
        return DxfBuild(DxfDocument(coordinates, layers, entities), summary)
    }

    /** La capa 0, las de código en orden de aparición, SIN_CODIGO y las de etiquetas que tengan algún texto (RF-4 a RF-7). */
    private fun layerDefs(colors: Map<String, Int>, entities: List<DxfEntity>): List<DxfLayerDef> {
        val defs = ArrayList<DxfLayerDef>()
        defs += DxfLayerDef(DxfLayerNames.ZERO, AciColor.DEFAULT)
        colors.filterKeys { it != DxfLayerNames.NO_CODE }.forEach { (name, color) -> defs += DxfLayerDef(name, color) }
        if (DxfLayerNames.NO_CODE in colors) defs += DxfLayerDef(DxfLayerNames.NO_CODE, AciColor.DEFAULT)
        val textLayers = entities.filterIsInstance<DxfTextEntity>().map { it.layer }.toSet()
        listOf(DxfLayerNames.NAMES, DxfLayerNames.ELEVATIONS, DxfLayerNames.DESCRIPTIONS)
            .filter { it in textLayers }
            .forEach { defs += DxfLayerDef(it, AciColor.DEFAULT) }
        return defs
    }

    private fun textHeight(heightM: Double, coordinates: DxfCoordinates, unit: LinearUnit): Double =
        if (coordinates == DxfCoordinates.GLOBAL) heightM / METRES_PER_DEGREE else unit.fromMetres(heightM)

    private fun unitOf(unit: LinearUnit) = when (unit) {
        LinearUnit.METRE -> DxfUnit.METRE
        LinearUnit.INTERNATIONAL_FOOT -> DxfUnit.INTERNATIONAL_FOOT
        LinearUnit.US_SURVEY_FOOT -> DxfUnit.US_SURVEY_FOOT
    }

    /** La posición del punto en el sistema pedido, o null sin Este y Norte (locales) o sin longitud y latitud (globales). */
    private fun position(point: Point, coordinates: DxfCoordinates, unit: LinearUnit): Position? {
        val c = point.coordinates
        return if (coordinates == DxfCoordinates.GLOBAL) {
            val lon = c.ground.longitudeDeg
            val lat = c.ground.latitudeDeg
            if (!lon.isFinite() || !lat.isFinite()) return null
            val height = c.ground.ellipsoidalHeightM?.takeIf { it.isFinite() }
            Position(lon, lat, height ?: 0.0, height != null)
        } else {
            val east = c.easting?.takeIf { it.isFinite() } ?: return null
            val north = c.northing?.takeIf { it.isFinite() } ?: return null
            val height = c.height?.takeIf { it.isFinite() }
            Position(unit.fromMetres(east), unit.fromMetres(north), height?.let(unit::fromMetres) ?: 0.0, height != null)
        }
    }

    private fun labels(point: Point, position: Position, chosen: Set<DxfLabel>, height: Double): List<DxfTextEntity> {
        val x = position.x + 0.5 * height
        val texts = ArrayList<DxfTextEntity>(chosen.size)
        fun add(layer: String, offset: Double, text: String) {
            texts += DxfTextEntity(layer, x, position.y + offset * height, position.z, height, text)
        }
        if (DxfLabel.NAME in chosen) add(DxfLayerNames.NAMES, NAME_OFFSET, point.name)
        if (DxfLabel.ELEVATION in chosen && position.hasHeight) {
            add(DxfLayerNames.ELEVATIONS, ELEVATION_OFFSET, DxfNumberFormat.elevation(position.z))
        }
        if (DxfLabel.DESCRIPTION in chosen && point.description.isNotBlank()) {
            add(DxfLayerNames.DESCRIPTIONS, DESCRIPTION_OFFSET, point.description)
        }
        return texts
    }
}
