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
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
 * De los trozos que cortan la vista a lo que se dibuja (S040/RF-24, RF-25, RNF): recorta a la vista; no dibuja lo que mide menos de
 * un píxel y aclara los puntos que caen en el mismo píxel; simplifica los vértices que quedan a menos de un píxel del anterior; y,
 * si aun así hay más de [MAX_VERTICES], sube el umbral de píxel en píxel hasta que caben. Pasa el blanco y el negro del DXF al color
 * de contraste del mapa. Recorre los trozos dos veces, decodificándolos uno a uno, para no tener a la vez en memoria todos los
 * elementos de una vista enorme.
 */
object DesignSceneBuilder {
    /** Como mucho este número de vértices y puntos por escena, para no frenar el mapa (RNF-T13). */
    const val MAX_VERTICES = 60_000

    /** La mitad del lado mayor de una pantalla, en píxeles, de sobra: la vista que se carga es 1,5 veces mayor (A10). */
    private const val HALF_SCREEN_PX = 1_200
    private const val MARGIN = 1.5
    private const val METRES_PER_DEGREE = 111_320.0
    private const val MAX_PX_THRESHOLD = 400
    private const val MIN_LINE_BUDGET = 1_000

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

    fun build(crs: ProjectCrs, layers: List<DesignLayer>, chunks: List<CadChunkContent>, metresPerPixel: Double, contrastArgb: Int) =
        build(crs, layers, chunks.asSequence(), metresPerPixel, contrastArgb)

    /**
     * La escena de los [chunks] de las capas visibles [layers] con [metresPerPixel] metros por píxel; el blanco y el negro del DXF
     * se pintan de [contrastArgb]. [chunks] se recorre dos veces.
     */
    fun build(
        crs: ProjectCrs,
        layers: List<DesignLayer>,
        chunks: Sequence<CadChunkContent>,
        metresPerPixel: Double,
        contrastArgb: Int,
    ): DesignScene {
        val byId = layers.associateBy { it.id }
        val unit = metresPerPixel * if (crs.utmZone == null) 1.0 / METRES_PER_DEGREE else 1.0

        // Primera pasada: los puntos (uno por píxel) y, de cada línea, cuánto mide, cuánto recorre y cuántos vértices tiene.
        val points = ArrayList<DesignPoint>()
        val cells = HashSet<Long>()
        val stats = LineStats()
        for (chunk in chunks) {
            val layer = byId[chunk.layerId] ?: continue
            for (entity in chunk.entities) {
                if (isPoint(entity)) {
                    val key = cellKey(entity.easting[0], entity.northing[0], unit)
                    if (cells.add(key)) addPoint(crs, entity, layer, contrastArgb, points)
                } else if (entity.vertexCount >= 2) {
                    stats.add(entity)
                }
            }
        }
        val capped = cap(points)

        // El menor umbral de píxeles con el que las líneas que se ven caben en lo que queda del límite.
        val budget = max(MAX_VERTICES - capped.size, MIN_LINE_BUDGET)
        var low = 1
        var high = MAX_PX_THRESHOLD
        while (low < high) {
            val mid = (low + high) / 2
            if (stats.estimate(mid * unit) <= budget) high = mid else low = mid + 1
        }
        val threshold = low * unit

        // Segunda pasada: solo las líneas que se ven, con sus vértices simplificados.
        val lines = ArrayList<DesignLine>()
        for (chunk in chunks) {
            val layer = byId[chunk.layerId] ?: continue
            for (entity in chunk.entities) {
                if (isPoint(entity) || entity.vertexCount < 2 || diagonal(entity) < threshold) continue
                val xy = decimate(entity, threshold)
                val argb = argbOf(entity.color ?: layer.color, contrastArgb)
                toGeographic(crs, xy)?.let { lines += DesignLine(it, argb) }
            }
        }
        return DesignScene(lines, capped)
    }

    private fun isPoint(entity: CadEntity) = entity.kind == CadEntityKind.POINT || entity.kind == CadEntityKind.BLOCK_INSERT

    private fun addPoint(crs: ProjectCrs, entity: CadEntity, layer: DesignLayer, contrastArgb: Int, out: MutableList<DesignPoint>) {
        val g = CadPlacement.geographic(crs, entity.easting[0], entity.northing[0]) ?: return
        out += DesignPoint(g.first, g.second, argbOf(entity.color ?: layer.color, contrastArgb))
    }

    private fun cellKey(east: Double, north: Double, unit: Double): Long =
        (floor(east / unit).toLong() shl 32) xor (floor(north / unit).toLong() and 0xFFFFFFFFL)

    private fun cap(points: List<DesignPoint>): List<DesignPoint> {
        if (points.size <= MAX_VERTICES) return points
        val step = (points.size + MAX_VERTICES - 1) / MAX_VERTICES
        return points.filterIndexed { i, _ -> i % step == 0 }
    }

    private fun argbOf(color: CadColor, contrastArgb: Int) = if (color.autoContrast) contrastArgb else color.argb

    /** Lo que mide el recuadro del elemento en diagonal. */
    private fun diagonal(entity: CadEntity): Double {
        var minE = Double.MAX_VALUE
        var maxE = -Double.MAX_VALUE
        var minN = Double.MAX_VALUE
        var maxN = -Double.MAX_VALUE
        for (i in 0 until entity.vertexCount) {
            minE = min(minE, entity.easting[i])
            maxE = max(maxE, entity.easting[i])
            minN = min(minN, entity.northing[i])
            maxN = max(maxN, entity.northing[i])
        }
        return hypot(maxE - minE, maxN - minN)
    }

    private fun pathLength(entity: CadEntity): Double {
        var total = 0.0
        for (i in 0 until entity.vertexCount - 1) {
            total += hypot(entity.easting[i + 1] - entity.easting[i], entity.northing[i + 1] - entity.northing[i])
        }
        return total
    }

    /** De cada línea, lo justo para saber cuántos vértices quedarían con un umbral dado, sin guardar las líneas. */
    private class LineStats {
        private var diagonals = DoubleArray(INITIAL)
        private var lengths = DoubleArray(INITIAL)
        private var vertices = IntArray(INITIAL)
        private var count = 0

        fun add(entity: CadEntity) {
            if (count == diagonals.size) {
                diagonals = diagonals.copyOf(count * 2)
                lengths = lengths.copyOf(count * 2)
                vertices = vertices.copyOf(count * 2)
            }
            diagonals[count] = diagonal(entity)
            lengths[count] = pathLength(entity)
            vertices[count] = entity.vertexCount
            count++
        }

        /** Los vértices que como mucho quedan con [threshold]: las líneas menores se quitan y las otras se simplifican. */
        fun estimate(threshold: Double): Long {
            var total = 0L
            for (i in 0 until count) {
                if (diagonals[i] < threshold) continue
                total += min(vertices[i].toLong(), 2L + ceil(lengths[i] / threshold).toLong())
            }
            return total
        }

        private companion object {
            const val INITIAL = 1_024
        }
    }

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
