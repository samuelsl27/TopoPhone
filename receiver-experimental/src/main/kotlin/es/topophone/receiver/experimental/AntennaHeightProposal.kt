package es.topophone.receiver.experimental

import es.topophone.core.model.point.TiltMode

/**
 * La altura de antena que se propone enviar al receptor (S044/RF-18; plan D10): con la posición compensada por el receptor, la altura del
 * jalón activo (S046); con cualquier otro modo, 0, porque la app ya resta la altura y enviar otro valor la contaría dos veces
 * ([warnDoubleCount]). La propuesta siempre se puede editar.
 */
data class AntennaHeightProposal(val valueM: Double, val warnDoubleCount: Boolean) {
    companion object {
        /** [poleHeightM]: la altura introducida del jalón activo, o `null` si no hay jalón activo. */
        fun of(mode: TiltMode, poleHeightM: Double?): AntennaHeightProposal = when (mode) {
            TiltMode.RECEIVER -> AntennaHeightProposal(poleHeightM?.takeIf { it.isFinite() } ?: 0.0, warnDoubleCount = false)
            TiltMode.APP, TiltMode.VERTICAL -> AntennaHeightProposal(0.0, warnDoubleCount = true)
        }
    }
}
