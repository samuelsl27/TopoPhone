package es.topophone.core.survey.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Longitudes, azimut, desnivel y pendiente de una línea o un polígono (S026/RF-1 a RF-3, RF-7). Solo calcula: no conoce líneas,
 * puntos ni dibujos, y no escribe nada.
 */
object GeometryCalculator {

    fun of(vertices: List<GeometryVertex>, closed: Boolean, scale: SegmentScale): GeometryResult {
        if (vertices.size < (if (closed) 3 else 2)) return GeometryResult.TooFewVertices
        val plane = PlanarFrame.of(vertices) ?: return GeometryResult.TooFewVertices
        val count = if (closed) vertices.size else vertices.size - 1
        val segments = ArrayList<SegmentInfo>(count)
        val missing = ArrayList<Int>()
        var chainage = 0.0
        var total3D = 0.0
        for (i in 0 until count) {
            val j = if (i + 1 < vertices.size) i + 1 else 0
            val a = vertices[i]
            val b = vertices[j]
            val de = plane.east[j] - plane.east[i]
            val dn = plane.north[j] - plane.north[i]
            val length2D = hypot(de, dn) / scale.segment(a, b)
            val ha = a.height
            val hb = b.height
            var length3D: Double? = null
            var dh: Double? = null
            var slope: Double? = null
            if (ha != null && hb != null) {
                dh = hb - ha
                length3D = hypot(length2D, dh)
                slope = if (length2D > 0.0) dh / length2D * 100.0 else null
                total3D += length3D
            } else {
                missing += i
            }
            segments += SegmentInfo(i, i, j, chainage, length2D, length3D, azimuth(de, dn), dh, slope)
            chainage += length2D
        }
        return GeometryResult.Info(
            GeometryInfo(
                mode = scale.mode,
                closed = closed,
                vertexCount = vertices.size,
                segmentCount = count,
                length2D = chainage,
                length3D = if (missing.isEmpty()) total3D else null,
                perimeter = if (closed) chainage else null,
                area = if (closed) area(plane, scale.polygon(vertices)) else null,
                crosses = closed && CrossingFinder.crosses(plane.east, plane.north, true),
                missingHeightSegments = missing,
                notices = scale.notices,
                segments = segments,
            ),
        )
    }

    /**
     * El valor absoluto de la fórmula de Gauss, con las coordenadas relativas al primer vértice para no perder dígitos, entre el
     * cuadrado del factor (S026/RF-1). Con cruces es el área con signo: las zonas de sentidos opuestos se restan (RF-6).
     */
    private fun area(plane: PlanarFrame, factor: Double): Double {
        val n = plane.east.size
        val e0 = plane.east[0]
        val n0 = plane.north[0]
        var sum = 0.0
        for (i in 0 until n) {
            val j = if (i + 1 < n) i + 1 else 0
            sum += (plane.east[i] - e0) * (plane.north[j] - n0) - (plane.east[j] - e0) * (plane.north[i] - n0)
        }
        return abs(sum / 2.0) / (factor * factor)
    }

    /** Azimut horario desde el norte, en [0, 2π) (RNF-T9). */
    internal fun azimuth(de: Double, dn: Double): Double {
        val a = atan2(de, dn)
        return if (a < 0.0) a + 2 * PI else a
    }
}
