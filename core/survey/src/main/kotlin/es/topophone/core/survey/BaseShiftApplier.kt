package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GeodeticPosition

/**
 * Suma el desplazamiento de base a la punta del suelo, antes de pasar al SC del proyecto (S020/RF-7; orden de
 * `docs/arquitectura.md`: bruto → punta del jalón → desplazamiento → SC → localización): geográficas → ECEF → + Δ →
 * geográficas. Una punta sin altura se desplaza a altura 0 y sigue sin altura (el error de usar 0 es del orden de
 * Δ·h/R, menos de 1 mm con Δ de 10 m y h de 3.000 m).
 */
object BaseShiftApplier {

    fun apply(ground: GeodeticPosition, shift: BaseShiftVector?): GeodeticPosition {
        if (shift == null) return ground
        val height = ground.ellipsoidalHeightM
        val shifted = (Ecef.fromGeodetic(ground.latitudeDeg, ground.longitudeDeg, height ?: 0.0) + Ecef(shift.dx, shift.dy, shift.dz))
            .toGeodetic()
        return GeodeticPosition(shifted.latitudeDeg, shifted.longitudeDeg, if (height == null) null else shifted.ellipsoidalHeightM)
    }
}
