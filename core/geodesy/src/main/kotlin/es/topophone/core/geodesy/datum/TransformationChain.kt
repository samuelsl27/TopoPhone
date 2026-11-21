package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ecef
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodeticPoint

/** Un paso de una cadena ya aplanado: o se calcula en geocéntricas (y se puede componer con el vecino) o es opaco (una rejilla). */
internal sealed interface ChainLink {
    class Geocentric(val from: Ellipsoid, val to: Ellipsoid, val apply: (Ecef) -> Ecef) : ChainLink

    class Opaque(val apply: (GeodeticPoint) -> TransformResult) : ChainLink
}

/**
 * Sucesión ordenada de transformaciones (datum local → WGS84 → ETRS89 → datum del SC; S033/RF-11). Se calcula en una pasada por punto, sin
 * redondeos intermedios (RF-13): los pasos geocéntricos consecutivos que comparten elipsoide se componen en cartesianas sin volver a
 * geográficas, y solo se pasa a geográficas donde lo pide una rejilla o al final. El inverso recorre los pasos al revés, cada uno con su
 * inverso. Si un paso rechaza el punto, la cadena devuelve ese rechazo (S033/RF-8, RF-9, RF-10).
 */
class TransformationChain(private val steps: List<DatumTransformation>, override val info: TransformationInfo) : DatumTransformation {

    private val forwardLinks: List<ChainLink> = steps.flatMap { linksOf(it, forward = true) }
    private val inverseLinks: List<ChainLink> = steps.asReversed().flatMap { linksOf(it, forward = false) }

    init {
        require(steps.isNotEmpty()) { "Una cadena necesita al menos una transformación" }
    }

    override fun forward(point: GeodeticPoint): TransformResult = run(point, forwardLinks)

    override fun inverse(point: GeodeticPoint): TransformResult = run(point, inverseLinks)

    override fun missingGrid(): String? = steps.firstNotNullOfOrNull { it.missingGrid() }

    private fun run(point: GeodeticPoint, links: List<ChainLink>): TransformResult {
        // Antes de calcular nada: con una rejilla ausente en cualquier paso no se da coordenada y se dice cuál falta (S033/RF-12).
        missingGrid()?.let { return TransformResult.GridUnavailable(it) }
        if (!point.isFinite()) return TransformResult.NotConverged(null)
        var current = point
        var ecef: Ecef? = null
        var ecefEllipsoid: Ellipsoid? = null
        for (link in links) {
            when (link) {
                is ChainLink.Geocentric -> {
                    if (ecef != null && ecefEllipsoid != link.from) {
                        current = ecef.toGeodetic(checkNotNull(ecefEllipsoid))
                        ecef = null
                    }
                    val start = ecef ?: Ecef.fromGeodetic(current.latitudeDeg, current.longitudeDeg, current.ellipsoidalHeightM, link.from)
                    ecef = link.apply(start)
                    ecefEllipsoid = link.to
                }
                is ChainLink.Opaque -> {
                    if (ecef != null) {
                        current = ecef.toGeodetic(checkNotNull(ecefEllipsoid))
                        ecef = null
                    }
                    when (val result = link.apply(current)) {
                        is TransformResult.Ok -> current = result.point
                        else -> return result
                    }
                }
            }
        }
        if (ecef != null) current = ecef.toGeodetic(checkNotNull(ecefEllipsoid))
        return TransformResult.Ok(current)
    }

    private companion object {
        fun linksOf(step: DatumTransformation, forward: Boolean): List<ChainLink> = when (step) {
            is TransformationChain -> if (forward) step.forwardLinks else step.inverseLinks
            is HelmertTransformation -> listOf(geocentricLink(step.geocentricStep, forward))
            is MolodenskyBadekas -> listOf(geocentricLink(step.geocentricStep, forward))
            else -> listOf(ChainLink.Opaque(if (forward) step::forward else step::inverse))
        }

        fun geocentricLink(step: GeocentricStep, forward: Boolean): ChainLink = if (forward) {
            ChainLink.Geocentric(step.source, step.target, step.kernel::forward)
        } else {
            ChainLink.Geocentric(step.target, step.source, step.kernel::inverse)
        }
    }
}
