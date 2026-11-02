package es.topophone.core.model.alignment.slope

import es.topophone.core.model.alignment.section.HeightKind

/** El lado del eje de un talud: el desplazamiento es negativo a la izquierda y positivo a la derecha (S049, S051). */
enum class SlopeSide(val sign: Int) {
    LEFT(-1),
    RIGHT(1),
}

/** Desmonte (la cabeza del talud, con el terreno por encima del borde) o terraplén (el pie, con el terreno por debajo) (S051/RF-5). */
enum class SlopeKind { CUT, FILL }

/**
 * El borde de explanación escrito a mano para un rango de PK cuando no hay sección de S050 (S051/RF-2): su desplazamiento con signo,
 * y su cota, absoluta o la diferencia con la rasante según [heightKind].
 */
data class ManualEdge(val offsetM: Double, val heightM: Double, val heightKind: HeightKind)

/**
 * Los taludes de un lado del eje entre [fromChainage] y [toChainage] (extremos incluidos): las relaciones de desmonte y de terraplén en
 * metros horizontales por cada metro vertical (1,5 es 3:2), el código del borde de explanación (null: el exterior de la sección) y,
 * sin sección, el borde escrito a mano (S051/RF-1, RF-2).
 */
data class SlopeRule(
    val side: SlopeSide,
    val fromChainage: Double,
    val toChainage: Double,
    val cutRatio: Double,
    val fillRatio: Double,
    val edgeCode: String? = null,
    val manualEdge: ManualEdge? = null,
) {
    fun contains(chainage: Double): Boolean = chainage >= fromChainage && chainage <= toChainage

    /** La relación del talud de ese tipo. */
    fun ratioOf(kind: SlopeKind): Double = if (kind == SlopeKind.CUT) cutRatio else fillRatio
}

/** Por qué no se guarda un talud (S051/RF-3). */
sealed interface SlopeIssue {
    /** Una relación que es cero, negativa o no es un número. */
    data class InvalidRatio(val side: SlopeSide, val value: Double) : SlopeIssue

    /** El final del rango es anterior a su inicio. */
    data class ReversedRange(val fromChainage: Double, val toChainage: Double) : SlopeIssue

    /** Un rango se pisa con otro del mismo lado. */
    data class OverlappingRanges(val side: SlopeSide, val fromChainage: Double, val toChainage: Double) : SlopeIssue

    /** Un PK antes del inicio del eje o después de su final. */
    data class ChainageOutsideAxis(val chainage: Double) : SlopeIssue

    /** El borde manual no es un número válido. */
    data object InvalidManualEdge : SlopeIssue
}

/**
 * Lo propio del talud de un punto replanteado (S051/RF-15): el tipo, el lado, la relación, la altura de talud, la diferencia de cota con
 * el talud de diseño (medida menos diseño) y el desplazamiento de estaca (0 si no se usó).
 */
data class SlopeStakeInfo(
    val kind: SlopeKind,
    val side: SlopeSide,
    val ratio: Double,
    val heightM: Double,
    val dHeightM: Double?,
    val stakeOffsetM: Double = 0.0,
)
