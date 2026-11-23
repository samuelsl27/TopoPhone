package es.topophone.core.survey.localization

import es.topophone.core.model.localization.LocalizationPair
import kotlin.math.atan2
import kotlin.math.hypot

/** Por qué no se puede calcular una localización (S039/RF-12). */
enum class LocalizationFailure {
    /** Ningún par tiene una componente activa. */
    NO_PAIRS,

    /** Con 2 o más pares horizontales, los puntos GNSS coinciden en planta. */
    DEGENERATE,
}

/** El ajuste horizontal: los dos centroides, el giro y la escala (S039/RF-7, RF-8). */
internal data class HorizontalFit(
    val centreE: Double,
    val centreN: Double,
    val knownCentreE: Double,
    val knownCentreN: Double,
    val rotationRad: Double,
    val scale: Double,
)

/**
 * El ajuste horizontal por mínimos cuadrados (S039/RF-7, RF-8, RF-12) sobre coordenadas centradas en el centroide de los puntos GNSS: con ellas las
 * ecuaciones normales se resuelven con fórmulas cerradas y no se pierde precisión aunque Este y Norte valgan millones de metros.
 */
internal object HorizontalSolver {
    /** Por debajo de esta suma de cuadrados de distancias al centroide (en m²) los puntos GNSS coinciden en planta. */
    private const val MIN_SPREAD_M2 = 1.0e-9

    /** `null` si el ajuste es degenerado; con un par, una traslación; con 2 o más, una semejanza o, con [fixScale], un movimiento rígido. */
    fun fit(pairs: List<LocalizationPair>, fixScale: Boolean): HorizontalFit? {
        val n = pairs.size
        require(n > 0) { "Sin pares no hay ajuste" }
        val ce = pairs.sumOf { it.scE } / n
        val cn = pairs.sumOf { it.scN } / n
        val ke = pairs.sumOf { it.knownE } / n
        val kn = pairs.sumOf { it.knownN } / n
        if (n == 1) return HorizontalFit(ce, cn, ke, kn, rotationRad = 0.0, scale = 1.0)
        var spread = 0.0
        var dot = 0.0
        var cross = 0.0
        for (p in pairs) {
            val u = p.scE - ce
            val v = p.scN - cn
            val up = p.knownE - ke
            val vp = p.knownN - kn
            spread += u * u + v * v
            dot += u * up + v * vp
            cross += u * vp - v * up
        }
        if (spread < MIN_SPREAD_M2) return null
        val rotation = atan2(cross, dot)
        val scale = if (fixScale) 1.0 else hypot(dot, cross) / spread
        return HorizontalFit(ce, cn, ke, kn, rotation, scale)
    }
}
