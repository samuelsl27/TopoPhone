package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.GeodeticPoint

/**
 * Una transformación recorrida al revés: su `forward` es el `inverse` de [base] y al contrario (S034). Lo usan los pasos del catálogo que EPSG
 * da en el sentido contrario al que se necesita (`inv` de la tubería de PROJ). Dentro de una [TransformationChain] los pasos geocéntricos
 * siguen componiéndose en cartesianas.
 */
class ReversedTransformation(internal val base: DatumTransformation) : DatumTransformation {
    override val info: TransformationInfo get() = base.info

    override fun forward(point: GeodeticPoint): TransformResult = base.inverse(point)

    override fun inverse(point: GeodeticPoint): TransformResult = base.forward(point)

    override fun missingGrid(): String? = base.missingGrid()
}

/** La identidad: la transformación nula de EPSG entre datums que coinciden (S034). */
class NullTransformation(override val info: TransformationInfo) : DatumTransformation {
    override fun forward(point: GeodeticPoint): TransformResult =
        if (point.isFinite()) TransformResult.Ok(point) else TransformResult.NotConverged(null)

    override fun inverse(point: GeodeticPoint): TransformResult = forward(point)
}
