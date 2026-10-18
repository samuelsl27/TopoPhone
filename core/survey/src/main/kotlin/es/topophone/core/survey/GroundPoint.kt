package es.topophone.core.survey

import es.topophone.core.geodesy.GeoidModel
import es.topophone.core.geodesy.GeoidModels
import es.topophone.core.geodesy.Utm
import es.topophone.core.geodesy.UtmCoordinate
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.project.CrsArea

/** Modelos de receptor con la distancia de su base al centro de referencia de la posición. */
enum class ReceiverModel(val baseToReferenceM: Double) {
    /** Reach RS3: 0,134 m según la especificación ETC_rev_3 de Emlid (se valida en G0). */
    RS3(0.134),
}

/** Jalón vertical: altura de antena = jalón + desplazamiento del modelo (S002/RF-32). */
object VerticalPole {
    fun antennaHeightM(poleHeightM: Double, model: ReceiverModel): Double = poleHeightM + model.baseToReferenceM
}

/** Altura del jalón introducida por el usuario (S002/RF-29, S002/RF-31). */
object PoleHeight {
    const val DEFAULT_M = 1.8
    const val MAX_M = 10.0
    private val NUMBER = Regex("""^\d+([.,]\d+)?$""")

    /** Metros entre 0 y 10, con punto o coma decimal; `null` si el texto no es válido. */
    fun parse(text: String): Double? {
        val trimmed = text.trim()
        if (!NUMBER.matches(trimmed)) return null
        return trimmed.replace(',', '.').toDouble().takeIf { it in 0.0..MAX_M }
    }
}

/** Motivos por los que una parte del resultado no está disponible (S002/RF-37, S002/RF-39). */
enum class Unavailable { NO_HEIGHT, OUTSIDE_UTM_ZONES, OUTSIDE_GEOID }

/** Sistema de referencia de las coordenadas (S002/RF-41): REGCAN95 en Canarias (S008/RF-20). */
enum class CoordinateReference { CORRECTIONS_ASSUMED_ETRS89, CORRECTIONS_ASSUMED_REGCAN95 }

/** Punto del suelo bajo la punta del jalón. */
data class GroundPoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val ellipsoidalHeightM: Double?,
    val utm: UtmCoordinate?,
    val orthometricHeightM: Double?,
    val unavailable: Set<Unavailable>,
    val solution: SolutionStatus,
    val reference: CoordinateReference = CoordinateReference.CORRECTIONS_ASSUMED_ETRS89,
) {
    /** Solo la solución fija tiene precisión centimétrica (S002/RF-40). */
    val isCentimetric: Boolean get() = solution == SolutionStatus.FIX
}

/**
 * Del fix GGA del receptor al punto del suelo en UTM del huso de su longitud con altitud EGM08-REDNAP:
 * ETRS89 en la Península y Baleares, REGCAN95 y su geoide en Canarias (S002/RF-32, RF-33, RF-36 a
 * RF-39; S008/RF-20, RF-21).
 */
class GroundPointCalculator(
    private val peninsulaGeoid: () -> GeoidModel = { GeoidModels.egm08RednapPeninsula },
    private val canariasGeoid: () -> GeoidModel = { GeoidModels.egm08RednapCanarias },
    private val model: ReceiverModel = ReceiverModel.RS3,
    private val utmZones: IntRange = SUPPORTED_UTM_ZONES,
) {
    fun compute(fix: GgaFix, poleHeightM: Double): GroundPoint? {
        if (!fix.hasPosition) return null
        val unavailable = mutableSetOf<Unavailable>()

        // Jalón vertical: la punta tiene la misma φ, λ y está bajo el receptor sobre la normal (S002/RF-32).
        val altitude = fix.altitudeM
        val separation = fix.geoidSeparationM
        val receiverHeight = if (altitude != null && separation != null) altitude + separation else null
        val groundHeight = receiverHeight?.minus(VerticalPole.antennaHeightM(poleHeightM, model))
        if (groundHeight == null) unavailable += Unavailable.NO_HEIGHT

        val zone = Utm.zoneFor(fix.longitudeDeg)
        val utm = if (zone in utmZones) Utm.forward(fix.latitudeDeg, fix.longitudeDeg, zone) else null
        if (utm == null) unavailable += Unavailable.OUTSIDE_UTM_ZONES

        // S008/RF-21: en Canarias, su geoide y REGCAN95.
        val canarias = CrsArea.CANARIAS.contains(fix.latitudeDeg, fix.longitudeDeg)
        val geoid = if (canarias) canariasGeoid() else peninsulaGeoid()
        val undulation = geoid.undulationM(fix.latitudeDeg, fix.longitudeDeg)
        if (undulation == null) unavailable += Unavailable.OUTSIDE_GEOID
        val orthometric = if (groundHeight != null && undulation != null) groundHeight - undulation else null
        val reference = if (canarias) CoordinateReference.CORRECTIONS_ASSUMED_REGCAN95 else CoordinateReference.CORRECTIONS_ASSUMED_ETRS89

        return GroundPoint(fix.latitudeDeg, fix.longitudeDeg, groundHeight, utm, orthometric, unavailable, fix.status, reference)
    }

    companion object {
        /** Husos de España: 27 y 28 en Canarias, 29 a 31 en la Península y Baleares (S002/RF-37, S008/RF-20). */
        val SUPPORTED_UTM_ZONES = 27..31
    }
}
