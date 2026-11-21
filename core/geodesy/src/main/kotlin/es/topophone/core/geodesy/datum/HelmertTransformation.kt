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
    internal val geocentricStep = GeocentricStep(source, target, geocentric)

    override fun forward(point: GeodeticPoint): TransformResult = geocentricResult(point, source, target, geocentric::forward)

    override fun inverse(point: GeodeticPoint): TransformResult = geocentricResult(point, target, source, geocentric::inverse)
}

/** Un paso que se calcula en cartesianas geocéntricas: sus elipsoides de origen y de destino y el cálculo. */
internal class GeocentricStep(val source: Ellipsoid, val target: Ellipsoid, val kernel: GeocentricSevenParameter)

/** Geográficas de [from] → geocéntricas → [step] → geográficas de [to]; una entrada no finita se rechaza sin lanzar excepción. */
internal fun geocentricResult(point: GeodeticPoint, from: Ellipsoid, to: Ellipsoid, step: (Ecef) -> Ecef): TransformResult {
    if (!point.isFinite()) return TransformResult.NotConverged(null)
    val x = Ecef.fromGeodetic(point.latitudeDeg, point.longitudeDeg, point.ellipsoidalHeightM, from)
    return TransformResult.Ok(step(x).toGeodetic(to))
}

internal fun GeodeticPoint.isFinite(): Boolean = latitudeDeg.isFinite() && longitudeDeg.isFinite() && ellipsoidalHeightM.isFinite()
