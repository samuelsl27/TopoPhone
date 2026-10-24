package es.topophone.core.survey

/** Dónde está la punta respecto al punto de diseño (S018/RF-19): lejos, en la zona de ayuda o dentro de la tolerancia. */
enum class StakeZone { FAR, AID, INSIDE }

/** El aviso que da un cambio de zona (S018/RF-20, RF-21). */
enum class StakeAlert {
    /** Ha entrado en la zona de ayuda viniendo de fuera. */
    APPROACH,

    /** Ha entrado en la tolerancia. */
    ARRIVED,

    /** Ha salido de la tolerancia. */
    LEFT,
}

/** La zona de después de una época y el aviso que hay que dar, si lo hay. */
data class ZoneStep(val zone: StakeZone, val alert: StakeAlert?)

/**
 * Las zonas de la guía y sus avisos como una función pura (S018/A3). Con el ruido del GNSS, un borde sin margen repetiría
 * el aviso a cada época: se sale de la tolerancia con 5 mm más de su límite (RF-20) y se vuelve a armar la zona de ayuda
 * con 5 mm más de sus 40 cm.
 */
object StakeoutZones {
    /** S018/RF-19: la zona de ayuda, a 40 cm o menos del punto de diseño. */
    const val AID_RADIUS_M = 0.40

    /** S018/RF-20: lo que se pasa del borde antes de dar el cambio por hecho. */
    const val MARGIN_M = 0.005

    /** La zona en la que queda la punta a [distanceM] estando antes en [current], con la tolerancia [toleranceM]. */
    fun next(current: StakeZone, distanceM: Double, toleranceM: Double): ZoneStep {
        val hasAid = toleranceM < AID_RADIUS_M // con una tolerancia de 40 cm o más no hay zona de ayuda
        return when (current) {
            StakeZone.FAR -> when {
                distanceM <= toleranceM -> ZoneStep(StakeZone.INSIDE, StakeAlert.ARRIVED)
                hasAid && distanceM <= AID_RADIUS_M -> ZoneStep(StakeZone.AID, StakeAlert.APPROACH)
                else -> ZoneStep(StakeZone.FAR, null)
            }
            StakeZone.AID -> when {
                distanceM <= toleranceM -> ZoneStep(StakeZone.INSIDE, StakeAlert.ARRIVED)
                distanceM > AID_RADIUS_M + MARGIN_M -> ZoneStep(StakeZone.FAR, null)
                else -> ZoneStep(StakeZone.AID, null)
            }
            StakeZone.INSIDE -> when {
                distanceM > toleranceM + MARGIN_M ->
                    ZoneStep(if (hasAid && distanceM <= AID_RADIUS_M) StakeZone.AID else StakeZone.FAR, StakeAlert.LEFT)
                else -> ZoneStep(StakeZone.INSIDE, null)
            }
        }
    }
}
