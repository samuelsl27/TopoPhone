package es.topophone.core.survey

import es.topophone.core.model.point.PoleKind
import kotlin.math.sqrt

/** Por qué el equipo (jalón y perfil de antena) de un receptor no permite medir (S046/RF-18, RF-28). */
enum class GearProblem { NO_POLE, NO_RADIUS, SLANT_SHORTER_THAN_RADIUS }

/** La altura vertical de un jalón, o por qué no se puede calcular. */
sealed interface VerticalHeight {
    data class Valid(val metres: Double) : VerticalHeight

    data class Invalid(val problem: GearProblem) : VerticalHeight
}

/** Alturas del jalón y de la antena (S046, plan A1 y A2). */
object PoleGeometry {

    /**
     * La altura vertical del jalón: la escrita si es vertical y, si es inclinada, √(inclinada² − radio²) con el radio de
     * la base del perfil. Sin radio, o con la inclinada menor que el radio, no es válida (S046/RF-17, RF-18).
     */
    fun vertical(kind: PoleKind, enteredM: Double, radiusM: Double?): VerticalHeight = when {
        kind == PoleKind.VERTICAL -> VerticalHeight.Valid(enteredM)
        radiusM == null -> VerticalHeight.Invalid(GearProblem.NO_RADIUS)
        enteredM < radiusM -> VerticalHeight.Invalid(GearProblem.SLANT_SHORTER_THAN_RADIUS)
        else -> VerticalHeight.Valid(sqrt(enteredM * enteredM - radiusM * radiusM))
    }

    /** Altura de antena: la vertical del jalón más el desplazamiento al centro de fase del perfil (S046/RF-20). */
    fun antennaHeightM(verticalPoleM: Double, offsetM: Double): Double = verticalPoleM + offsetM
}
