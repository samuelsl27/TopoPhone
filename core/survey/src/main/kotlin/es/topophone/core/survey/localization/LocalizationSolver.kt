package es.topophone.core.survey.localization

import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.VerticalKind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** El residuo de un par tras el ajuste, en metros: conocido menos transformado; null en la componente que no entra (S039/RF-9, RF-10). */
data class PairResidual(val pairId: String, val east: Double?, val north: Double?, val vertical: Double?) {
    /** El residuo horizontal (módulo de Este y Norte), o null si el par no tiene componente horizontal activa. */
    val horizontal: Double? get() = if (east != null && north != null) hypot(east, north) else null
}

/**
 * Una localización calculada (S039/RF-7 a RF-10): sus [params], el residuo de cada par, el RMS horizontal y vertical (null si no hay pares de esa
 * componente), el par con mayor residuo y si la parte vertical tenía 3 o más pares alineados y se quedó en un desplazamiento (RF-9).
 */
data class LocalizationSolution(
    val params: LocalizationParams,
    val residuals: List<PairResidual>,
    val rmsHorizontal: Double?,
    val rmsVertical: Double?,
    val worstPairId: String?,
    val verticalAligned: Boolean,
)

/** El resultado de calcular el borrador: la solución o el motivo por el que no se puede (S039/RF-12). */
sealed interface LocalizationOutcome {
    data class Solved(val solution: LocalizationSolution) : LocalizationOutcome

    data class Failed(val reason: LocalizationFailure) : LocalizationOutcome
}

/**
 * Calcula la localización de un borrador (S039/RF-7 a RF-12): la parte horizontal con los pares de componente horizontal activa (traslación con uno,
 * semejanza o movimiento rígido con 2 o más) y la vertical con los de componente vertical (desplazamiento con 1 o 2, plano con 3 o más no alineados).
 */
object LocalizationSolver {
    /** Cociente mínimo del determinante de las ecuaciones normales del plano respecto a su escala: por debajo, los puntos están alineados. */
    private const val ALIGNED_RATIO = 1.0e-9

    fun solve(pairs: List<LocalizationPair>, fixScale: Boolean): LocalizationOutcome {
        val horizontalPairs = pairs.filter { it.countsHorizontal }
        val verticalPairs = pairs.filter { it.countsVertical }
        if (horizontalPairs.isEmpty() && verticalPairs.isEmpty()) return LocalizationOutcome.Failed(LocalizationFailure.NO_PAIRS)

        var params = LocalizationParams()
        if (horizontalPairs.isNotEmpty()) {
            val fit = HorizontalSolver.fit(horizontalPairs, fixScale) ?: return LocalizationOutcome.Failed(LocalizationFailure.DEGENERATE)
            params = params.copy(
                centreE = fit.centreE,
                centreN = fit.centreN,
                knownCentreE = fit.knownCentreE,
                knownCentreN = fit.knownCentreN,
                rotationRad = fit.rotationRad,
                scale = fit.scale,
                hasHorizontal = true,
            )
        }
        var aligned = false
        if (verticalPairs.isNotEmpty()) {
            val vertical = fitVertical(verticalPairs)
            aligned = vertical.aligned
            params = params.copy(
                verticalKind = vertical.kind,
                dh = vertical.dh,
                slopeE = vertical.slopeE,
                slopeN = vertical.slopeN,
                verticalE = vertical.centreE,
                verticalN = vertical.centreN,
            )
        }

        val residuals = pairs.map { residual(it, params) }
        val horizontals = residuals.mapNotNull { r -> r.horizontal?.let { r.pairId to it } }
        val verticals = residuals.mapNotNull { r -> r.vertical?.let { r.pairId to abs(it) } }
        val rmsH = if (horizontals.isEmpty()) null else sqrt(horizontals.sumOf { it.second * it.second } / horizontals.size)
        val rmsV = if (verticals.isEmpty()) null else sqrt(verticals.sumOf { it.second * it.second } / verticals.size)
        val worst = residuals
            .mapNotNull { r ->
                val size = max(r.horizontal ?: 0.0, abs(r.vertical ?: 0.0))
                if (r.horizontal == null && r.vertical == null) null else r.pairId to size
            }
            .maxByOrNull { it.second }?.first
        return LocalizationOutcome.Solved(LocalizationSolution(params, residuals, rmsH, rmsV, worst, aligned))
    }

    private class VerticalFit(
        val kind: VerticalKind,
        val dh: Double,
        val slopeE: Double,
        val slopeN: Double,
        val centreE: Double,
        val centreN: Double,
        val aligned: Boolean,
    )

    private fun fitVertical(pairs: List<LocalizationPair>): VerticalFit {
        val m = pairs.size
        val ce = pairs.sumOf { it.scE } / m
        val cn = pairs.sumOf { it.scN } / m
        val dh = pairs.sumOf { it.knownH!! - it.scH!! } / m
        if (m < 3) return VerticalFit(VerticalKind.OFFSET, dh, 0.0, 0.0, ce, cn, aligned = false)
        var suu = 0.0
        var suv = 0.0
        var svv = 0.0
        var sud = 0.0
        var svd = 0.0
        for (p in pairs) {
            val u = p.scE - ce
            val v = p.scN - cn
            val d = p.knownH!! - p.scH!! - dh
            suu += u * u
            suv += u * v
            svv += v * v
            sud += u * d
            svd += v * d
        }
        val det = suu * svv - suv * suv
        val scale = suu * svv
        if (scale <= 0.0 || det <= ALIGNED_RATIO * scale) return VerticalFit(VerticalKind.OFFSET, dh, 0.0, 0.0, ce, cn, aligned = true)
        val px = (sud * svv - svd * suv) / det
        val py = (svd * suu - sud * suv) / det
        return VerticalFit(VerticalKind.PLANE, dh, px, py, ce, cn, aligned = false)
    }

    private fun residual(pair: LocalizationPair, params: LocalizationParams): PairResidual {
        var east: Double? = null
        var north: Double? = null
        if (pair.countsHorizontal) {
            val (e, n) = LocalizationApplier.horizontal(params, pair.scE, pair.scN)
            east = pair.knownE - e
            north = pair.knownN - n
        }
        val vertical = if (pair.countsVertical) pair.knownH!! - LocalizationApplier.height(params, pair.scE, pair.scN, pair.scH!!) else null
        return PairResidual(pair.id, east, north, vertical)
    }
}

/** Qué avisos de umbral tiene una localización calculada: los pares con residuo alto y si la escala se aleja de 1 (S039/RF-13). */
data class LocalizationAssessment(val highPairIds: Set<String>, val scaleOutOfLimit: Boolean) {
    val hasWarnings: Boolean get() = highPairIds.isNotEmpty() || scaleOutOfLimit
}

/** Compara los residuos (horizontal y vertical) con [residualLimitM] y la escala con [scaleLimit] (S039/RF-13, RF-15). */
fun LocalizationSolution.assess(residualLimitM: Double, scaleLimit: Double): LocalizationAssessment = LocalizationAssessment(
    highPairIds = residuals.filter {
        (it.horizontal ?: 0.0) > residualLimitM || abs(it.vertical ?: 0.0) > residualLimitM
    }.map { it.pairId }.toSet(),
    scaleOutOfLimit = abs(params.scale - 1.0) > scaleLimit,
)
