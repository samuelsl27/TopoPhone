package es.topophone.core.survey

/** El punto de diseño de un replanteo por PK, o por qué no lo hay (S054/RF-1, RF-7, RF-9). */
sealed interface AxisStakeResult {
    /**
     * @property point el punto del eje o a su lado, con el azimut del eje en ese PK.
     * @property profileElevationM la cota de la rasante en el PK, o null si el eje no tiene rasante o el PK queda fuera de ella.
     * @property elevationM la cota de diseño: la de la rasante más el [thicknessM]; null si no hay rasante (RF-7).
     */
    data class Target(
        val chainageM: Double,
        val offsetM: Double,
        val thicknessM: Double,
        val point: AxisPoint,
        val profileElevationM: Double?,
        val elevationM: Double?,
    ) : AxisStakeResult

    /** El PK no cabe en el eje: se dice el PK inicial y el final (RF-9). */
    data class OutsideAxis(val startM: Double, val endM: Double) : AxisStakeResult
}

/** El punto de diseño de un PK, un desplazamiento y un espesor sobre un eje (S054/RF-1, RF-7, RF-9, plan A1). */
object AxisStakeTarget {
    /** Positivo a la derecha en el sentido de los PK crecientes; [thicknessM] se suma a la cota de la rasante. */
    fun of(
        geometry: AlignmentGeometry,
        profile: VerticalProfileEval?,
        chainageM: Double,
        offsetM: Double,
        thicknessM: Double = 0.0,
    ): AxisStakeResult {
        val point = geometry.pointAt(chainageM, offsetM) ?: return AxisStakeResult.OutsideAxis(geometry.startChainage, geometry.endChainage)
        val ground = profile?.at(chainageM)?.elevation
        return AxisStakeResult.Target(chainageM, offsetM, thicknessM, point, ground, ground?.plus(thicknessM))
    }
}
