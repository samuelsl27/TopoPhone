package es.topophone.app.ui.cad

import es.topophone.app.ui.map.MapCamera
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.cad.CadChunkContent
import kotlin.math.cos
import kotlin.math.hypot

/** Una línea del dibujo lista para el mapa: longitud y latitud de cada vértice, una tras otra, y su color ARGB (S040/RF-24). */
class DesignLine(val lonLat: DoubleArray, val argb: Int) {
    val vertexCount: Int get() = lonLat.size / 2
}

/** Un punto del dibujo (un `POINT` o el punto de inserción de un bloque) listo para el mapa (S040/RF-24). */
data class DesignPoint(val latitudeDeg: Double, val longitudeDeg: Double, val argb: Int)

/** Lo que el mapa dibuja del dibujo importado en la vista de ahora (S040/RF-24). */
class DesignScene(val lines: List<DesignLine>, val points: List<DesignPoint>) {
    val vertexCount: Int get() = lines.sumOf { it.vertexCount } + points.size
    val isEmpty: Boolean get() = lines.isEmpty() && points.isEmpty()
}

/**
 * De los trozos que cortan la vista a lo que se dibuja (S040/RF-24, RF-25, RNF): recorta a la vista, simplifica los vértices que
 * quedan a menos de un píxel del anterior —y sube el umbral de píxel en píxel hasta que caben— y pasa el blanco y el negro del DXF
 * al color de contraste del mapa.
 */
object DesignSceneBuilder {
    /** Como mucho este número de vértices y puntos por escena, para no frenar el mapa (RNF-T13). */
    const val MAX_VERTICES = 60_000

    /** La mitad del lado mayor de una pantalla, en píxeles, de sobra: la vista que se carga es 1,5 veces mayor (A10). */
    private const val HALF_SCREEN_PX = 1_200
    private const val MARGIN = 1.5
    private const val METRES_PER_DEGREE = 111_320.0
    private const val MAX_PX_THRESHOLD = 400

    /**
     * El recuadro, en el sistema del proyecto, de lo que se carga con la cámara [camera]: 1,5 veces la pantalla. null si el centro
     * de la cámara cae fuera del ámbito del sistema.
     */
    fun viewport(camera: MapCamera, crs: ProjectCrs, calculator: ProjectPositionCalculator = ProjectPositionCalculator()): CadExtent? {
        val half = WebMercator.metresPerPixel(camera.latitudeDeg, camera.zoom) * HALF_SCREEN_PX * MARGIN
        if (crs.utmZone == null) {
            val dLat = half / METRES_PER_DEGREE
            val dLon = dLat / cos(Math.toRadians(camera.latitudeDeg)).coerceAtLeast(0.01)
            return CadExtent(camera.longitudeDeg - dLon, camera.longitudeDeg + dLon, camera.latitudeDeg - dLat, camera.latitudeDeg + dLat)
        }
        val position = calculator.fromGround(GeodeticPosition(camera.latitudeDeg, camera.longitudeDeg, null), crs)
        val east = position.easting ?: return null
        val north = position.northing ?: return null
        return CadExtent(east - half, east + half, north - half, north + half)
    }

    /**
     * La escena de los [chunks] de las capas visibles [layers] con [metresPerPixel] metros por píxel; el blanco y el negro del DXF
     * se pintan de [contrastArgb].
     */
    fun build(
        crs: ProjectCrs,
        layers: List<DesignLayer>,
        chunks: List<CadChunkContent>,
        metresPerPixel: Double,
        contrastArgb: Int,
    ): DesignScene {
        val byId = layers.associateBy { it.id }
        val lines = ArrayList<Pair<CadEntity, Int>>()
        val points = ArrayList<DesignPoint>()
        for (chunk in chunks) {
            val layer = byId[chunk.layerId] ?: continue
            for (entity in chunk.entities) {
                val argb = argbOf(entity.color ?: layer.color, contrastArgb)
                if (entity.kind == CadEntityKind.POINT || entity.kind == CadEntityKind.BLOCK_INSERT) {
                    CadPlacement.geographic(crs, entity.easting[0], entity.northing[0])?.let {
                        points +=
                            DesignPoint(it.first, it.second, argb)
                    }
                } else if (entity.vertexCount >= 2) {
                    lines += entity to argb
                }
            }
        }
        val unitsPerMetre = if (crs.utmZone == null) 1.0 / METRES_PER_DEGREE else 1.0
        var kept = decimateAll(lines, metresPerPixel * unitsPerMetre, 1)
        var px = 1
        while (kept.sumOf { it.first.size } + points.size > MAX_VERTICES && px < MAX_PX_THRESHOLD) {
            px++
            kept = decimateAll(lines, metresPerPixel * unitsPerMetre, px)
        }
        val drawn = kept.mapNotNull { (xy, argb) -> toGeographic(crs, xy)?.let { DesignLine(it, argb) } }
        return DesignScene(drawn, cap(points))
    }

    private fun cap(points: List<DesignPoint>): List<DesignPoint> {
        if (points.size <= MAX_VERTICES) return points
        val step = (points.size + MAX_VERTICES - 1) / MAX_VERTICES
        return points.filterIndexed { i, _ -> i % step == 0 }
    }

    private fun argbOf(color: CadColor, contrastArgb: Int) = if (color.autoContrast) contrastArgb else color.argb

    private fun decimateAll(lines: List<Pair<CadEntity, Int>>, unit: Double, px: Int): List<Pair<DoubleArray, Int>> =
        lines.map { (entity, argb) -> decimate(entity, unit * px) to argb }

    /** Los vértices separados al menos [threshold] del anterior que se quedó, más siempre el primero y el último; este/norte seguidos. */
    private fun decimate(entity: CadEntity, threshold: Double): DoubleArray {
        val n = entity.vertexCount
        val out = DoubleArray(n * 2)
        var count = 0
        var lastE = entity.easting[0]
        var lastN = entity.northing[0]
        out[count++] = lastE
        out[count++] = lastN
        for (i in 1 until n - 1) {
            val e = entity.easting[i]
            val nn = entity.northing[i]
            if (hypot(e - lastE, nn - lastN) >= threshold) {
                out[count++] = e
                out[count++] = nn
                lastE = e
                lastN = nn
            }
        }
        out[count++] = entity.easting[n - 1]
        out[count++] = entity.northing[n - 1]
        return out.copyOf(count)
    }

    private fun toGeographic(crs: ProjectCrs, xy: DoubleArray): DoubleArray? {
        val out = DoubleArray(xy.size)
        for (i in 0 until xy.size / 2) {
            val g = CadPlacement.geographic(crs, xy[i * 2], xy[i * 2 + 1]) ?: return null
            out[i * 2] = g.second
            out[i * 2 + 1] = g.first
        }
        return out
    }
}
