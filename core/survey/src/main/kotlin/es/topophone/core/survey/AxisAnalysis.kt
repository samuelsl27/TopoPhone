package es.topophone.core.survey

import es.topophone.core.model.point.AxisHeightRef
import es.topophone.core.model.surface.SurfaceHeight

/** La diferencia de cota de la punta con la referencia elegida, o por qué no hay (S054/RF-12). */
sealed interface AxisHeightDiff {
    /** No hay referencia: no se eligió, el eje no tiene rasante o no hay superficie activa. */
    data object None : AxisHeightDiff

    /** La rasante no llega a este PK. */
    data object OutsideProfile : AxisHeightDiff

    /** La punta está fuera de la superficie activa. */
    data object OutsideSurface : AxisHeightDiff

    /** La superficie aún se está cargando en este lugar. */
    data object LoadingSurface : AxisHeightDiff

    /** La cota de la referencia y la diferencia: la de la punta menos la de la referencia; null si la punta no tiene altura. */
    data class Diff(val referenceM: Double, val dHeightM: Double?) : AxisHeightDiff
}

/** Lo que dice el análisis de una posición respecto al eje (S054/RF-12, RF-14). */
sealed interface AxisAnalysisValue {
    /** Sin proyección sobre el eje: antes de su inicio o después de su final. No hay PK ni desplazamiento (RF-14). */
    data object NoProjection : AxisAnalysisValue

    /**
     * @property offsetM negativo a la izquierda, positivo a la derecha del eje.
     * @property heightRef contra qué se ha pedido la diferencia de cota; null si no se pidió ninguna.
     */
    data class Projected(val chainageM: Double, val offsetM: Double, val heightRef: AxisHeightRef?, val height: AxisHeightDiff) :
        AxisAnalysisValue
}

/** El análisis en vivo de la punta del jalón respecto a un eje (S054/RF-12, RF-14, plan A6). */
object AxisAnalysis {
    /**
     * @param ref la referencia de cota elegida: la rasante, la superficie o ninguna.
     * @param surfaceHeight la cota de la superficie activa en la posición (con su desplazamiento vertical), o null si no hay superficie.
     * @param tipHeightM la cota de la punta en el sistema de alturas del proyecto, o null si no tiene.
     */
    fun of(
        geometry: AlignmentGeometry,
        profile: VerticalProfileEval?,
        ref: AxisHeightRef?,
        surfaceHeight: SurfaceHeight?,
        easting: Double,
        northing: Double,
        tipHeightM: Double?,
    ): AxisAnalysisValue {
        val location = geometry.locate(easting, northing) ?: return AxisAnalysisValue.NoProjection
        val height = when (ref) {
            AxisHeightRef.PROFILE -> profile?.let { eval ->
                eval.at(location.chainage)?.let { diff(it.elevation, tipHeightM) } ?: AxisHeightDiff.OutsideProfile
            } ?: AxisHeightDiff.None
            AxisHeightRef.SURFACE -> when (surfaceHeight) {
                null -> AxisHeightDiff.None
                SurfaceHeight.Outside -> AxisHeightDiff.OutsideSurface
                SurfaceHeight.Loading -> AxisHeightDiff.LoadingSurface
                is SurfaceHeight.Value -> diff(surfaceHeight.metres, tipHeightM)
            }
            null -> AxisHeightDiff.None
        }
        return AxisAnalysisValue.Projected(location.chainage, location.offset, ref, height)
    }

    private fun diff(referenceM: Double, tipHeightM: Double?) = AxisHeightDiff.Diff(referenceM, tipHeightM?.minus(referenceM))
}
