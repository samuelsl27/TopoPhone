package es.topophone.core.survey

import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.TiltMode

/**
 * La punta que se muestra en directo (S016/RF-16, RF-17, RF-21; plan A4).
 *
 * @property ground la punta, o el punto bajo la antena con el jalón vertical cuando no se puede compensar.
 * @property uncompensated el modo usa la inclinación pero la época no está en estado 30: "Punta sin compensar".
 * @property extraHorizontalM y [extraVerticalM] la precisión de la inclinación que se suma a la de la GST (solo APP).
 */
data class LiveTipResult(
    val ground: GeodeticPosition,
    val uncompensated: Boolean,
    val extraHorizontalM: Double = 0.0,
    val extraVerticalM: Double = 0.0,
)

/** Del fix GGA, el ETC de su época y el modo del receptor, a la punta del jalón que se muestra. */
object LiveTip {

    fun of(fix: GgaFix, tilt: TiltData?, mode: TiltMode?, poleHeightM: Double, model: ReceiverModel = ReceiverModel.RS3): LiveTipResult? {
        if (!fix.hasPosition) return null
        val altitude = fix.altitudeM
        val separation = fix.geoidSeparationM
        val antenna = GeodeticPosition(
            fix.latitudeDeg,
            fix.longitudeDeg,
            if (altitude != null &&
                separation != null
            ) {
                altitude + separation
            } else {
                null
            },
        )
        val antennaHeight = VerticalPole.antennaHeightM(poleHeightM, model)
        val compensating = TiltEpoch.isCompensating(tilt)
        return when (mode) {
            TiltMode.APP -> {
                val pole = TiltEpoch.poleVector(tilt)
                val accuracy = TiltEpoch.accuracy(tilt, antennaHeight)
                if (compensating && pole != null && accuracy != null) {
                    LiveTipResult(PoleTip.fromAntenna(antenna, pole, antennaHeight), false, accuracy.horizontalM, accuracy.verticalM)
                } else {
                    vertical(antenna, antennaHeight, uncompensated = true)
                }
            }
            // La GGA ya es la punta; fuera del estado 30 no se sabe qué da el receptor (S016, suposición de RF-17).
            TiltMode.RECEIVER -> LiveTipResult(antenna, uncompensated = !compensating)
            TiltMode.VERTICAL, null -> vertical(antenna, antennaHeight, uncompensated = false)
        }
    }

    /** Jalón vertical (S002/RF-32): la misma φ, λ y la altura de antena menos. */
    private fun vertical(antenna: GeodeticPosition, antennaHeightM: Double, uncompensated: Boolean) = LiveTipResult(
        GeodeticPosition(antenna.latitudeDeg, antenna.longitudeDeg, antenna.ellipsoidalHeightM?.minus(antennaHeightM)),
        uncompensated,
    )
}
