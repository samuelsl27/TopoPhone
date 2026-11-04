package es.topophone.app.ui.map

import es.topophone.core.model.codes.CodeSymbol
import kotlin.math.cos
import kotlin.math.sin

/**
 * Las formas con que el mapa dibuja los puntos de cada código (S024/RF-17): polígonos en un cuadro unidad, con el origen arriba a la
 * izquierda. El círculo no tiene forma aquí: lo dibuja la capa de círculos, que ya existía.
 */
object SymbolShapes {
    /** El color de un punto fuera de tolerancia (S010/RF-20) y el de un punto sin código, en 0xRRGGBB. */
    const val OUT_OF_TOLERANCE_RGB = 0xE65100
    const val DEFAULT_RGB = 0x1565C0

    private const val CROSS_ARM = 0.18f
    private const val STAR_INNER = 0.22f
    private const val STAR_POINTS = 5

    /** El color con que se dibuja un punto: el de su aviso si está fuera de tolerancia, si no el de su código o el de los demás puntos. */
    fun effectiveColor(codeRgb: Int?, outOfTolerance: Boolean): Int = if (outOfTolerance) OUT_OF_TOLERANCE_RGB else codeRgb ?: DEFAULT_RGB

    /** El nombre del icono de [symbol] en [rgb] (0xRRGGBB); cada pareja se crea una vez. */
    fun iconId(symbol: CodeSymbol, rgb: Int): String = "topophone-code-${symbol.name}-%06X".format(java.util.Locale.ROOT, rgb and 0xFFFFFF)

    fun polygon(symbol: CodeSymbol): List<Pair<Float, Float>> = when (symbol) {
        CodeSymbol.CIRCLE -> emptyList()
        CodeSymbol.SQUARE -> listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.9f to 0.9f, 0.1f to 0.9f)
        CodeSymbol.TRIANGLE -> listOf(0.5f to 0.08f, 0.94f to 0.9f, 0.06f to 0.9f)
        CodeSymbol.DIAMOND -> listOf(0.5f to 0.04f, 0.96f to 0.5f, 0.5f to 0.96f, 0.04f to 0.5f)
        CodeSymbol.CROSS -> cross()
        CodeSymbol.STAR -> star()
    }

    private fun cross(): List<Pair<Float, Float>> {
        val a = 0.5f - CROSS_ARM
        val b = 0.5f + CROSS_ARM
        return listOf(
            a to 0.04f, b to 0.04f, b to a, 0.96f to a, 0.96f to b, b to b,
            b to 0.96f, a to 0.96f, a to b, 0.04f to b, 0.04f to a, a to a,
        )
    }

    /** Estrella de cinco puntas: diez vértices alternando el radio exterior y el interior, con una punta hacia arriba. */
    private fun star(): List<Pair<Float, Float>> = List(STAR_POINTS * 2) { i ->
        val radius = if (i % 2 == 0) 0.46f else STAR_INNER
        val angle = Math.PI * i / STAR_POINTS - Math.PI / 2
        (0.5f + radius * cos(angle).toFloat()) to (0.52f + radius * sin(angle).toFloat())
    }
}
