package es.topophone.core.survey

import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.LocalTangentPlane
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PoleVector

/**
 * La punta del jalón con la inclinación (S016/RF-13; plan A3): la antena desplazada −H·u en su plano tangente local,
 * con H la altura de antena y u el vector del jalón (ETC_rev_3), antes de pasar al SC del proyecto.
 */
object PoleTip {

    /** La punta bajo [antenna]. Sin altura elipsoidal se calcula con h = 0 y sale solo en planta (S016/RF-27). */
    fun fromAntenna(antenna: GeodeticPosition, pole: PoleVector, antennaHeightM: Double): GeodeticPosition =
        alongPole(antenna, pole, -antennaHeightM)

    /** [position] movida [distanceM] metros a lo largo del jalón: hacia la antena si es positiva (S016/RF-25). */
    fun alongPole(position: GeodeticPosition, pole: PoleVector, distanceM: Double): GeodeticPosition {
        val height = position.ellipsoidalHeightM
        val moved = LocalTangentPlane.offset(
            GeodeticPoint(position.latitudeDeg, position.longitudeDeg, height ?: 0.0),
            east = distanceM * pole.east,
            north = distanceM * pole.north,
            up = distanceM * pole.up,
        )
        return GeodeticPosition(moved.latitudeDeg, moved.longitudeDeg, if (height == null) null else moved.ellipsoidalHeightM)
    }
}
