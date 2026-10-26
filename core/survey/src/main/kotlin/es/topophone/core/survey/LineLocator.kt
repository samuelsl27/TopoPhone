package es.topophone.core.survey

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Dónde cae la punta respecto a los extremos de una línea abierta (S041/RF-14). */
enum class LinePosition { ON_LINE, BEFORE_START, AFTER_END }

/**
 * Dónde está la punta respecto a la línea (S041/RF-10, RF-14, RF-15), todo en metros de cuadrícula.
 *
 * @property nearest el punto más cercano de la línea; con la punta más allá de un extremo, ese extremo.
 * @property chainageM el PK del punto más cercano.
 * @property distanceM la distancia de la punta al punto más cercano.
 * @property lateralM la distancia lateral con signo, positiva a la derecha del sentido de la línea; 0 si [position] no es
 *   [LinePosition.ON_LINE], porque más allá de un extremo no hay lado (RF-14).
 * @property remainingM lo que falta del PK hasta el final de la línea (RF-10).
 * @property segment el tramo del punto más cercano.
 */
data class LineLocation(
    val nearest: PlanePoint,
    val chainageM: Double,
    val distanceM: Double,
    val lateralM: Double,
    val position: LinePosition,
    val remainingM: Double,
    val segment: Int,
)

/**
 * El punto más cercano de una [StakeLine] a la punta, con una rejilla de tramos para no recorrerlos todos con cada época
 * (S041/RF-10, RF-14, RF-15, RNF-T13). La rejilla solo acelera: el resultado es el de recorrer todos los tramos
 * ([locateExhaustive]). La rejilla se construye al crear el localizador, una sola vez por línea.
 *
 * Con dos tramos a la misma distancia (hasta [TIE_M]) gana el de menor número. El lado de la distancia lateral es el del tramo
 * elegido, también cuando el punto más cercano es un vértice.
 */
class LineLocator(private val line: StakeLine) {
    private val cell: Double
    private val originE: Double
    private val originN: Double
    private val cells = HashMap<Long, IntList>()
    private val longSegments = IntList()
    private var maxCellX = 0L
    private var maxCellY = 0L
    private var minE = Double.MAX_VALUE
    private var maxE = -Double.MAX_VALUE
    private var minN = Double.MAX_VALUE
    private var maxN = -Double.MAX_VALUE

    init {
        for (i in 0 until line.vertexCount) {
            minE = min(minE, line.easting(i))
            maxE = max(maxE, line.easting(i))
            minN = min(minN, line.northing(i))
            maxN = max(maxN, line.northing(i))
        }
        originE = minE
        originN = minN
        cell = max(MIN_CELL_M, max(maxE - minE, maxN - minN) / MAX_CELLS_PER_SIDE)
        maxCellX = cellOf(maxE, originE)
        maxCellY = cellOf(maxN, originN)
        for (k in 0 until line.segmentCount) register(k)
    }

    private fun cellOf(value: Double, origin: Double): Long = floor((value - origin) / cell).toLong()

    private fun key(cx: Long, cy: Long): Long = (cx shl 32) xor (cy and 0xFFFFFFFFL)

    private fun register(k: Int) {
        val a = line.segmentStart(k)
        val b = line.segmentEnd(k)
        val x0 = cellOf(min(line.easting(a), line.easting(b)), originE)
        val x1 = cellOf(max(line.easting(a), line.easting(b)), originE)
        val y0 = cellOf(min(line.northing(a), line.northing(b)), originN)
        val y1 = cellOf(max(line.northing(a), line.northing(b)), originN)
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > MAX_CELLS_PER_SEGMENT) {
            longSegments.add(k)
            return
        }
        for (cx in x0..x1) for (cy in y0..y1) cells.getOrPut(key(cx, cy)) { IntList() }.add(k)
    }

    /**
     * El punto más cercano de la línea a (`easting`, `northing`). [hint] es el tramo de la época anterior: la punta se mueve poco
     * entre épocas, así que empezar por él acota la búsqueda desde el principio.
     */
    fun locate(easting: Double, northing: Double, hint: Int? = null): LineLocation {
        val best = Best()
        longSegments.forEach { consider(it, easting, northing, best) }
        if (hint != null && hint in 0 until line.segmentCount) {
            for (k in max(0, hint - 1)..min(line.segmentCount - 1, hint + 1)) consider(k, easting, northing, best)
        }
        val inside = easting >= minE && easting <= maxE && northing >= minN && northing <= maxN
        if (!inside) return locateExhaustive(easting, northing)
        val cx = cellOf(easting, originE)
        val cy = cellOf(northing, originN)
        // Tras recorrer el anillo r, lo que queda sin ver está a r celdas o más de la punta: con un mejor menor, ya no mejora.
        val limit = max(maxCellX, maxCellY) + 1
        var ring = 0L
        while (ring <= limit) {
            visitRing(cx, cy, ring) { k -> consider(k, easting, northing, best) }
            if (best.k >= 0 && best.distance <= ring * cell) break
            if (ring >= MAX_RINGS) return locateExhaustive(easting, northing)
            ring++
        }
        if (best.k < 0) return locateExhaustive(easting, northing)
        return best.toLocation(line, easting, northing)
    }

    /** El mismo resultado recorriendo todos los tramos: la referencia de las pruebas y el último recurso. */
    fun locateExhaustive(easting: Double, northing: Double): LineLocation {
        val best = Best()
        for (k in 0 until line.segmentCount) consider(k, easting, northing, best)
        return best.toLocation(line, easting, northing)
    }

    private fun visitRing(cx: Long, cy: Long, ring: Long, visit: (Int) -> Unit) {
        fun cellAt(x: Long, y: Long) {
            cells[key(x, y)]?.forEach(visit)
        }
        if (ring == 0L) {
            cellAt(cx, cy)
            return
        }
        for (x in cx - ring..cx + ring) {
            cellAt(x, cy - ring)
            cellAt(x, cy + ring)
        }
        for (y in cy - ring + 1..cy + ring - 1) {
            cellAt(cx - ring, y)
            cellAt(cx + ring, y)
        }
    }

    private class Best {
        var k = -1
        var distance = Double.MAX_VALUE
        var raw = 0.0

        fun toLocation(line: StakeLine, tipE: Double, tipN: Double): LineLocation {
            val a = line.segmentStart(k)
            val b = line.segmentEnd(k)
            val ax = line.easting(a)
            val ay = line.northing(a)
            val dx = line.easting(b) - ax
            val dy = line.northing(b) - ay
            val length = hypot(dx, dy)
            val t = raw.coerceIn(0.0, 1.0)
            val position = when {
                line.closed -> LinePosition.ON_LINE
                k == 0 && raw < 0.0 -> LinePosition.BEFORE_START
                k == line.segmentCount - 1 && raw > 1.0 -> LinePosition.AFTER_END
                else -> LinePosition.ON_LINE
            }
            // cruz > 0: la punta queda a la izquierda del sentido del tramo; el lado positivo es el derecho
            val cross = dx * (tipN - ay) - dy * (tipE - ax)
            // la magnitud es la distancia al punto más cercano (también en un vértice); el lado, el del tramo elegido
            val lateral = if (position != LinePosition.ON_LINE || distance == 0.0) {
                0.0
            } else if (cross <= 0.0) {
                distance
            } else {
                -distance
            }
            val chainage = line.chainageOf(k) + t * length
            return LineLocation(
                PlanePoint(ax + t * dx, ay + t * dy),
                chainage,
                distance,
                lateral,
                position,
                line.lengthM - chainage,
                k,
            )
        }
    }

    private fun consider(k: Int, tipE: Double, tipN: Double, best: Best) {
        val a = line.segmentStart(k)
        val b = line.segmentEnd(k)
        val ax = line.easting(a)
        val ay = line.northing(a)
        val dx = line.easting(b) - ax
        val dy = line.northing(b) - ay
        val raw = ((tipE - ax) * dx + (tipN - ay) * dy) / (dx * dx + dy * dy)
        val t = raw.coerceIn(0.0, 1.0)
        val distance = hypot(tipE - (ax + t * dx), tipN - (ay + t * dy))
        if (best.k < 0 || distance < best.distance - TIE_M || (abs(distance - best.distance) <= TIE_M && k < best.k)) {
            best.k = k
            best.distance = distance
            best.raw = raw
        }
    }

    /** Una lista de enteros que crece, sin empaquetar cada número. */
    private class IntList {
        private var data = IntArray(4)
        private var size = 0

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        inline fun forEach(action: (Int) -> Unit) {
            for (i in 0 until size) action(get(i))
        }

        fun get(i: Int): Int = data[i]
    }

    companion object {
        /** Dos tramos a menos de 1 nm de la misma distancia empatan y gana el primero. */
        const val TIE_M = 1e-9

        private const val MIN_CELL_M = 25.0
        private const val MAX_CELLS_PER_SIDE = 2048.0
        private const val MAX_CELLS_PER_SEGMENT = 64L
        private const val MAX_RINGS = 400L
    }
}
