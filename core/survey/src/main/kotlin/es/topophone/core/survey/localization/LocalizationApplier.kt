package es.topophone.core.survey.localization

import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.VerticalKind
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pasa una posición del SC sin localizar al sistema de la obra y de vuelta (S039/RF-17, RF-18; D5). La parte horizontal es
 * `[E' N'] = [knownCentre] + scale · R(θ) · ([E N] − [centre])`; la vertical, `h' = h + dh + slopeE·(E − verticalE) + slopeN·(N − verticalN)`.
 */
object LocalizationApplier {
    /** Este y Norte localizados de una posición del SC; sin parte horizontal, los mismos. */
    fun horizontal(params: LocalizationParams, easting: Double, northing: Double): Pair<Double, Double> {
        if (!params.hasHorizontal) return easting to northing
        val c = cos(params.rotationRad)
        val s = sin(params.rotationRad)
        val u = easting - params.centreE
        val v = northing - params.centreN
        return (params.knownCentreE + params.scale * (c * u - s * v)) to (params.knownCentreN + params.scale * (s * u + c * v))
    }

    /** La altura localizada de una posición del SC (con su Este y Norte del SC); sin parte vertical, la misma. */
    fun height(params: LocalizationParams, easting: Double, northing: Double, height: Double): Double = when (params.verticalKind) {
        VerticalKind.NONE -> height
        VerticalKind.OFFSET -> height + params.dh
        VerticalKind.PLANE -> height + params.dh + params.slopeE * (easting - params.verticalE) +
            params.slopeN * (northing - params.verticalN)
    }

    /** Este y Norte del SC sin localizar de una posición localizada: la inversa de [horizontal]. */
    fun delocalizeHorizontal(params: LocalizationParams, easting: Double, northing: Double): Pair<Double, Double> {
        if (!params.hasHorizontal) return easting to northing
        val c = cos(-params.rotationRad)
        val s = sin(-params.rotationRad)
        val u = (easting - params.knownCentreE) / params.scale
        val v = (northing - params.knownCentreN) / params.scale
        return (params.centreE + c * u - s * v) to (params.centreN + s * u + c * v)
    }

    /** La altura del SC sin localizar de una posición localizada, con su Este y Norte ya sin localizar. */
    fun delocalizeHeight(params: LocalizationParams, gridEasting: Double, gridNorthing: Double, height: Double): Double =
        when (params.verticalKind) {
            VerticalKind.NONE -> height
            VerticalKind.OFFSET -> height - params.dh
            VerticalKind.PLANE -> height - params.dh - params.slopeE * (gridEasting - params.verticalE) -
                params.slopeN * (gridNorthing - params.verticalN)
        }
}
