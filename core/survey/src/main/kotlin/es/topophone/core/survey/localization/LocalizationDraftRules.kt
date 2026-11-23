package es.topophone.core.survey.localization

import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.point.hasMeasurement

/** Por qué no se puede añadir un par al borrador (S039/RF-3, RF-4). */
enum class PairRejection {
    /** El punto no tiene observación bruta: es manual, importado o calculado. */
    NO_MEASUREMENT,

    /** El punto ya está en otro par del borrador. */
    ALREADY_IN_DRAFT,

    /** El borrador ya tiene 50 pares. */
    LIMIT_REACHED,

    /** El punto medido no tiene Este y Norte en el SC del proyecto (por ejemplo, fuera de su ámbito). */
    NO_POSITION,
}

/** Las coordenadas conocidas de un par, en el sistema de la obra y en metros; la cota puede faltar. */
data class KnownCoordinates(val easting: Double, val northing: Double, val height: Double?)

/** El resultado de formar un par con un punto GNSS (S039/RF-2 a RF-4, RF-6). */
sealed interface PairResult {
    /** El par se puede añadir; si [needsConfirmation], el punto no es FIX o no cumplió el control de precisión y el par queda sin confirmar. */
    data class Added(val pair: LocalizationPair, val needsConfirmation: Boolean) : PairResult

    data class Rejected(val reason: PairRejection) : PairResult
}

/** Las reglas para formar los pares de un borrador (S039/RF-2 a RF-6). */
object LocalizationDraftRules {
    /** ¿Es un punto de calidad dudosa? No se midió en FIX o no cumplió el control de precisión del proyecto al medirse (RF-6). */
    fun isDoubtful(point: Point): Boolean {
        val measurement = point.measurement ?: return false
        return measurement.quality.solution != SolutionType.FIX.name || point.outOfTolerance
    }

    /**
     * Forma el par de [point] con las coordenadas [known] sin salirse de las reglas: el punto debe tener observación bruta (RF-3), no estar ya en
     * [existing] (RF-3), y el borrador no puede tener ya 50 pares (RF-4). La posición del par es la del punto en el SC sin localizar.
     */
    fun formPair(existing: List<LocalizationPair>, point: Point, known: KnownCoordinates, newId: String): PairResult {
        if (!point.origin.hasMeasurement || point.measurement == null) return PairResult.Rejected(PairRejection.NO_MEASUREMENT)
        if (existing.any { it.pointId == point.id }) return PairResult.Rejected(PairRejection.ALREADY_IN_DRAFT)
        if (existing.size >= LocalizationLimits.MAX_PAIRS) return PairResult.Rejected(PairRejection.LIMIT_REACHED)
        val sc = point.measurement!!.coordinates
        val e = sc.easting
        val n = sc.northing
        if (e == null || n == null) return PairResult.Rejected(PairRejection.NO_POSITION)
        val doubtful = isDoubtful(point)
        val pair = LocalizationPair(
            id = newId,
            pointId = point.id,
            pointName = point.name,
            scE = e,
            scN = n,
            scH = sc.height,
            knownE = known.easting,
            knownN = known.northing,
            knownH = known.height,
            confirmed = !doubtful,
        )
        return PairResult.Added(pair, needsConfirmation = doubtful)
    }
}
