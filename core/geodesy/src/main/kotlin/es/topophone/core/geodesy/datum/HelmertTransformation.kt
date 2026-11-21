package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodeticPoint

/**
 * Helmert de 3, 4, 6 o 7 parámetros entre dos datums, en PV o CF (S033/RF-1, RF-2; EPSG GN 7-2, métodos 9603, 9606 y 9607).
 * Pasa las geográficas 3D de [source] a cartesianas geocéntricas, aplica `X′ = T + (1 + s)·R·X` y vuelve a geográficas sobre [target].
 * El inverso es el exacto del directo (S033/RF-5).
 */
class HelmertTransformation(
    parameters: HelmertParameters,
    private val source: Ellipsoid,
    private val target: Ellipsoid,
    override val info: TransformationInfo,
) : DatumTransformation {

    private val geocentric = GeocentricSevenParameter(parameters)

    /** Lo que [TransformationChain] necesita para componer este paso con otros geocéntricos sin volver a geográficas. */
    internal val geocentricStep = GeocentricStep(source, target, info.areaOfUse, geocentric)

    override fun forward(point: GeodeticPoint): TransformResult = geocentricResult(geocentricStep, point, forward = true)

    override fun inverse(point: GeodeticPoint): TransformResult = geocentricResult(geocentricStep, point, forward = false)
}

/**
 * Un paso que se calcula en cartesianas geocéntricas: sus elipsoides de origen y de destino, el ámbito en que declara ser válido
 * (S033/RF-15) y el cálculo.
 */
internal class GeocentricStep(val source: Ellipsoid, val target: Ellipsoid, val area: AreaOfUse?, val kernel: GeocentricSevenParameter)

/**
 * Geográficas del origen (o del destino, en el inverso) → geocéntricas → cálculo → geográficas del otro datum; una entrada no finita se
 * rechaza sin lanzar excepción y una fuera del ámbito se calcula igualmente con el aviso de ámbito (S033/RF-15).
 */
internal fun geocentricResult(step: GeocentricStep, point: GeodeticPoint, forward: Boolean): TransformResult {
    if (!point.isFinite()) return TransformResult.NotConverged(null)
    val from = if (forward) step.source else step.target
    val to = if (forward) step.target else step.source
    val x = Ecef.fromGeodetic(point.latitudeDeg, point.longitudeDeg, point.ellipsoidalHeightM, from)
    val moved = if (forward) step.kernel.forward(x) else step.kernel.inverse(x)
    val outside = step.area?.contains(point.latitudeDeg, point.longitudeDeg) == false
    return TransformResult.Ok(moved.toGeodetic(to), if (outside) listOf(TransformWarning.OUTSIDE_AREA_OF_USE) else emptyList())
}

internal fun GeodeticPoint.isFinite(): Boolean = latitudeDeg.isFinite() && longitudeDeg.isFinite() && ellipsoidalHeightM.isFinite()
