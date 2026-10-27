package es.topophone.core.survey

import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalProfile
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** La cota en metros y la pendiente (metros por metro; ×100 es el porcentaje) de la rasante en un PK. */
data class ProfileValue(val elevation: Double, val slope: Double) {
    val slopePercent: Double get() = slope * 100.0
}

/**
 * La cota y la pendiente de una rasante en un PK (S049/RF-18, RF-20, plan A3). Entre dos vértices la rasante es la recta que los
 * une; en un vértice con acuerdo se sustituye un tramo centrado en él: una parábola simétrica de longitud horizontal `L`
 * (`z = z₀ + g₁·x + (g₂ − g₁)·x²/(2L)`) o un círculo de radio `R` tangente a las dos pendientes (`z = zc ± √(R² − (x − xc)²)`).
 * La rasante tiene que ser válida (`ProfileValidator`): PK crecientes y acuerdos que caben.
 */
class VerticalProfileEval(profile: VerticalProfile) {
    private val vertices = profile.vertices
    private val spans: List<Span>

    init {
        require(vertices.zipWithNext().all { (a, b) -> b.chainage > a.chainage }) { "Los vértices tienen PK crecientes" }
        val grades = vertices.zipWithNext { a, b -> (b.elevation - a.elevation) / (b.chainage - a.chainage) }
        spans = vertices.indices.mapNotNull { i ->
            if (i == 0 || i == vertices.lastIndex) return@mapNotNull null
            val v = vertices[i]
            val g1 = grades[i - 1]
            val g2 = grades[i]
            when (val curve = v.curve) {
                VerticalCurve.None -> null
                is VerticalCurve.Parabola -> {
                    val x1 = v.chainage - curve.length / 2
                    Span.Parabola(x1, v.chainage + curve.length / 2, v.elevation - g1 * curve.length / 2, g1, g2, curve.length)
                }
                is VerticalCurve.Circular -> circular(v.chainage, v.elevation, curve.radius, g1, g2)
            }
        }
    }

    val startChainage: Double = vertices.first().chainage
    val endChainage: Double = vertices.last().chainage

    /** La cota y la pendiente en [chainage]; null si está fuera de la rasante. */
    fun at(chainage: Double): ProfileValue? {
        if (chainage < startChainage - TOLERANCE || chainage > endChainage + TOLERANCE) return null
        val c = chainage.coerceIn(startChainage, endChainage)
        spans.firstOrNull { c >= it.start && c <= it.end }?.let { return it.at(c) }
        var i = vertices.indexOfLast { it.chainage <= c }.coerceAtMost(vertices.lastIndex - 1)
        if (i < 0) i = 0
        val a = vertices[i]
        val b = vertices[i + 1]
        val grade = (b.elevation - a.elevation) / (b.chainage - a.chainage)
        return ProfileValue(a.elevation + grade * (c - a.chainage), grade)
    }

    private fun circular(chainage: Double, elevation: Double, radius: Double, g1: Double, g2: Double): Span {
        val a1 = atan(g1)
        val a2 = atan(g2)
        val tangent = radius * tan(abs(a2 - a1) / 2)
        val x1 = chainage - tangent * cos(a1)
        val x2 = chainage + tangent * cos(a2)
        val z1 = elevation - tangent * sin(a1)
        val crest = a2 < a1
        // Normal hacia el centro: abajo en una cresta y arriba en un valle.
        val nx = if (crest) sin(a1) else -sin(a1)
        val nz = if (crest) -cos(a1) else cos(a1)
        return Span.Circle(x1, x2, x1 + radius * nx, z1 + radius * nz, radius, crest)
    }

    private sealed interface Span {
        val start: Double
        val end: Double

        fun at(x: Double): ProfileValue

        class Parabola(
            override val start: Double,
            override val end: Double,
            private val z0: Double,
            private val g1: Double,
            private val g2: Double,
            private val length: Double,
        ) : Span {
            override fun at(x: Double): ProfileValue {
                val d = x - start
                return ProfileValue(z0 + g1 * d + (g2 - g1) * d * d / (2 * length), g1 + (g2 - g1) * d / length)
            }
        }

        class Circle(
            override val start: Double,
            override val end: Double,
            private val xc: Double,
            private val zc: Double,
            private val radius: Double,
            private val crest: Boolean,
        ) : Span {
            override fun at(x: Double): ProfileValue {
                val root = sqrt(radius * radius - (x - xc) * (x - xc))
                val z = zc + (if (crest) root else -root)
                return ProfileValue(z, -(x - xc) / (z - zc))
            }
        }
    }

    private companion object {
        const val TOLERANCE = 1e-6
    }
}
