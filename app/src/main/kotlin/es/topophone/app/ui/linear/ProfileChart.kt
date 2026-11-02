package es.topophone.app.ui.linear

import es.topophone.core.model.survey.linear.SurveyFlag
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Qué va en el eje horizontal: el PK de un perfil longitudinal o el desplazamiento de un perfil transversal (S052/RF-13, RF-19). */
enum class ProfileAxis { CHAINAGE, OFFSET }

/**
 * Un punto del gráfico: [x] es el PK o el desplazamiento (positivo a la derecha), [groundM] la cota medida, [referenceM] la de la rasante
 * o la sección de diseño en ese sitio y [dHeightM] la diferencia. Los puntos con marca no entran en el perfil ([inProfile] falso): se
 * dibujan aparte con su [flag] (S052/RF-12, RF-18).
 */
data class ProfileChartPoint(
    val label: String,
    val x: Double,
    val groundM: Double,
    val referenceM: Double?,
    val dHeightM: Double?,
    val inProfile: Boolean,
    val flag: SurveyFlag,
)

/**
 * Lo que dibuja el gráfico de un perfil, ya calculado (S052/RF-13, RF-19): los puntos por [ProfileChartPoint.x] creciente y la línea de
 * referencia (la rasante, con sus acuerdos, o la sección de diseño) como pares (x, cota).
 */
data class ProfileChart(val axis: ProfileAxis, val points: List<ProfileChartPoint>, val reference: List<Pair<Double, Double>>) {
    /** Los puntos que forman el perfil, sin los que tienen marca. */
    val profilePoints: List<ProfileChartPoint> get() = points.filter { it.inProfile }

    /**
     * El intervalo horizontal que se ve entero: el de los puntos del perfil, sin los que tienen marca, o null si no hay ninguno. Con uno
     * solo se le da 1 de anchura para poder dibujarlo.
     */
    fun domain(): Pair<Double, Double>? {
        val xs = profilePoints.map { it.x }
        if (xs.isEmpty()) return null
        val low = xs.min()
        val high = xs.max()
        return if (high - low < MIN_WIDTH) (low - MIN_WIDTH / 2) to (high + MIN_WIDTH / 2) else low to high
    }

    /** El intervalo de cotas que cubre lo que cae en [window], con un margen, y nunca de menos de [MIN_HEIGHT]. */
    fun heightRange(window: Pair<Double, Double>): Pair<Double, Double> {
        val inside = (
            points.filter { it.x in window.first..window.second }.flatMap { listOfNotNull(it.groundM, it.referenceM) } +
                reference.filter { it.first in window.first..window.second }.map { it.second }
            )
        if (inside.isEmpty()) return 0.0 to MIN_HEIGHT
        val low = inside.min()
        val high = inside.max()
        val span = max(high - low, MIN_HEIGHT)
        val pad = span * MARGIN
        val extra = (span - (high - low)) / 2
        return (low - extra - pad) to (high + extra + pad)
    }

    /** El índice del punto del perfil más cercano a [x] en el eje horizontal, o null sin puntos en el perfil. */
    fun nearest(x: Double): Int? {
        var best: Int? = null
        var distance = Double.MAX_VALUE
        points.forEachIndexed { i, p ->
            if (p.inProfile && abs(p.x - x) < distance) {
                distance = abs(p.x - x)
                best = i
            }
        }
        return best
    }

    private companion object {
        const val MIN_WIDTH = 1.0
        const val MIN_HEIGHT = 1.0
        const val MARGIN = 0.1
    }
}

/** Si un punto del terreno está por encima de la referencia (desmonte), por debajo (terraplén) o a su cota (S052/RF-13). */
enum class CutFill {
    CUT,
    FILL,
    ON_REFERENCE,
    ;

    companion object {
        /** Por debajo de medio milímetro es la misma cota, que es la resolución con la que se escribe. */
        private const val SAME_M = 0.0005

        fun of(dHeightM: Double): CutFill = when {
            abs(dHeightM) < SAME_M -> ON_REFERENCE
            dHeightM > 0 -> CUT
            else -> FILL
        }
    }
}

/**
 * Cuánto zoom lleva el gráfico y a qué altura del dominio está su centro (S052/RF-13): [zoom] 1 enseña todo el dominio y [centerFraction]
 * va de 0 a 1 sobre él. No guarda el dominio, así que sigue valiendo cuando la toma añade puntos.
 */
data class ProfileViewport(val zoom: Double = 1.0, val centerFraction: Double = 0.5) {
    /** La ventana horizontal visible del dominio [low] a [high]. */
    fun window(low: Double, high: Double): Pair<Double, Double> {
        val span = (high - low) / zoom
        val start = low + (high - low) * centerFraction - span / 2
        return start to start + span
    }

    fun zoomIn(): ProfileViewport = zoomBy(STEP, 0.5)

    fun zoomOut(): ProfileViewport = zoomBy(1 / STEP, 0.5)

    fun reset(): ProfileViewport = ProfileViewport()

    /** Mueve la ventana [fractionOfWindow] veces su anchura (positivo a la derecha), sin salirse del dominio. */
    fun panBy(fractionOfWindow: Double): ProfileViewport =
        copy(centerFraction = clampCenter(zoom, centerFraction + fractionOfWindow / zoom))

    /**
     * Multiplica el zoom por [factor] manteniendo bajo [anchorFraction] de la ventana (0 a la izquierda, 1 a la derecha) el mismo punto
     * del dominio, como un gesto de pellizco.
     */
    fun zoomBy(factor: Double, anchorFraction: Double): ProfileViewport {
        val newZoom = min(MAX_ZOOM, max(1.0, zoom * factor))
        val under = centerFraction + (anchorFraction - 0.5) / zoom
        return ProfileViewport(newZoom, clampCenter(newZoom, under - (anchorFraction - 0.5) / newZoom))
    }

    private fun clampCenter(zoom: Double, center: Double): Double {
        val half = 0.5 / zoom
        return min(1.0 - half, max(half, center))
    }

    companion object {
        const val MAX_ZOOM = 64.0
        const val STEP = 2.0
    }
}
