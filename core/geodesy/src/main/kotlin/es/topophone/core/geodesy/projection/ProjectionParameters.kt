package es.topophone.core.geodesy.projection

import es.topophone.core.model.project.LinearUnit

/**
 * Los parámetros de cada variante, con los nombres de EPSG (GN 7-2). Los ángulos van en grados; el falso Este y el falso Norte, en la unidad
 * lineal del SC (S032/RF-16): [ProjectionFactory] los pasa a metros.
 */
sealed interface ProjectionParameters {
    val method: ProjectionMethod
}

/** Lambert Conformal Conic con un paralelo estándar: el de origen, con su factor de escala (EPSG 9801). */
data class LambertConic1Sp(
    val latitudeOfOriginDeg: Double,
    val longitudeOfOriginDeg: Double,
    val scaleFactor: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
) : ProjectionParameters {
    override val method get() = ProjectionMethod.LAMBERT_CONIC_1SP
}

/** Lambert Conformal Conic con dos paralelos estándar; el Este y el Norte se dan en el origen falso (EPSG 9802). */
data class LambertConic2Sp(
    val latitudeOfFalseOriginDeg: Double,
    val longitudeOfFalseOriginDeg: Double,
    val standardParallel1Deg: Double,
    val standardParallel2Deg: Double,
    val eastingAtFalseOrigin: Double,
    val northingAtFalseOrigin: Double,
) : ProjectionParameters {
    override val method get() = ProjectionMethod.LAMBERT_CONIC_2SP
}

/** Mercator con factor de escala; el origen natural está en el ecuador (EPSG 9804). */
data class MercatorA(val longitudeOfOriginDeg: Double, val scaleFactor: Double, val falseEasting: Double, val falseNorthing: Double) :
    ProjectionParameters {
    override val method get() = ProjectionMethod.MERCATOR_A
}

/** Mercator con un paralelo estándar de escala verdadera (EPSG 9805). */
data class MercatorB(
    val longitudeOfOriginDeg: Double,
    val standardParallelDeg: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
) : ProjectionParameters {
    override val method get() = ProjectionMethod.MERCATOR_B
}

/** Hotine Oblique Mercator, variante A: el falso Este y Norte se dan en el origen natural (EPSG 9812). */
data class ObliqueMercatorA(
    val latitudeOfCentreDeg: Double,
    val longitudeOfCentreDeg: Double,
    val azimuthDeg: Double,
    val rectifiedGridAngleDeg: Double,
    val scaleFactor: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
) : ProjectionParameters {
    override val method get() = ProjectionMethod.OBLIQUE_MERCATOR_A
}

/** Hotine Oblique Mercator, variante B: el Este y el Norte se dan en el centro de la proyección (EPSG 9815). */
data class ObliqueMercatorB(
    val latitudeOfCentreDeg: Double,
    val longitudeOfCentreDeg: Double,
    val azimuthDeg: Double,
    val rectifiedGridAngleDeg: Double,
    val scaleFactor: Double,
    val eastingAtCentre: Double,
    val northingAtCentre: Double,
) : ProjectionParameters {
    override val method get() = ProjectionMethod.OBLIQUE_MERCATOR_B
}

/** Estereográfica oblicua (doble estereográfica) (EPSG 9809). */
data class ObliqueStereographic(
    val latitudeOfOriginDeg: Double,
    val longitudeOfOriginDeg: Double,
    val scaleFactor: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
) : ProjectionParameters {
    override val method get() = ProjectionMethod.OBLIQUE_STEREOGRAPHIC
}

/** Estereográfica polar, variante A: el origen es un polo y se da el factor de escala en él (EPSG 9810). */
data class PolarStereographicA(
    val latitudeOfOriginDeg: Double,
    val longitudeOfOriginDeg: Double,
    val scaleFactor: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
) : ProjectionParameters {
    override val method get() = ProjectionMethod.POLAR_STEREOGRAPHIC_A
}

/** Estereográfica polar, variante B: se da el paralelo estándar de escala verdadera; su signo dice el polo (EPSG 9829). */
data class PolarStereographicB(
    val latitudeOfStandardParallelDeg: Double,
    val longitudeOfOriginDeg: Double,
    val falseEasting: Double,
    val falseNorthing: Double,
) : ProjectionParameters {
    override val method get() = ProjectionMethod.POLAR_STEREOGRAPHIC_B
}

/**
 * Estereográfica polar, variante C: paralelo estándar y falsos Este y Norte en el origen falso, que es donde el paralelo estándar corta la
 * longitud de origen (EPSG 9830).
 */
data class PolarStereographicC(
    val latitudeOfStandardParallelDeg: Double,
    val longitudeOfOriginDeg: Double,
    val eastingAtFalseOrigin: Double,
    val northingAtFalseOrigin: Double,
) : ProjectionParameters {
    override val method get() = ProjectionMethod.POLAR_STEREOGRAPHIC_C
}

/** El falso Este y el falso Norte de la variante, en la unidad con que se escribieron (en el origen natural, falso o centro, según el método). */
internal fun ProjectionParameters.falseOrigin(): Pair<Double, Double> = when (this) {
    is LambertConic1Sp -> falseEasting to falseNorthing
    is LambertConic2Sp -> eastingAtFalseOrigin to northingAtFalseOrigin
    is MercatorA -> falseEasting to falseNorthing
    is MercatorB -> falseEasting to falseNorthing
    is ObliqueMercatorA -> falseEasting to falseNorthing
    is ObliqueMercatorB -> eastingAtCentre to northingAtCentre
    is ObliqueStereographic -> falseEasting to falseNorthing
    is PolarStereographicA -> falseEasting to falseNorthing
    is PolarStereographicB -> falseEasting to falseNorthing
    is PolarStereographicC -> eastingAtFalseOrigin to northingAtFalseOrigin
}

/** Los mismos parámetros con el falso Este y el falso Norte pasados de la unidad del SC a metros; nada más cambia (S032/RF-16, RNF-T9). */
fun ProjectionParameters.inMetres(unit: LinearUnit): ProjectionParameters {
    fun m(value: Double) = unit.toMetres(value)
    return when (this) {
        is LambertConic1Sp -> copy(falseEasting = m(falseEasting), falseNorthing = m(falseNorthing))
        is LambertConic2Sp -> copy(eastingAtFalseOrigin = m(eastingAtFalseOrigin), northingAtFalseOrigin = m(northingAtFalseOrigin))
        is MercatorA -> copy(falseEasting = m(falseEasting), falseNorthing = m(falseNorthing))
        is MercatorB -> copy(falseEasting = m(falseEasting), falseNorthing = m(falseNorthing))
        is ObliqueMercatorA -> copy(falseEasting = m(falseEasting), falseNorthing = m(falseNorthing))
        is ObliqueMercatorB -> copy(eastingAtCentre = m(eastingAtCentre), northingAtCentre = m(northingAtCentre))
        is ObliqueStereographic -> copy(falseEasting = m(falseEasting), falseNorthing = m(falseNorthing))
        is PolarStereographicA -> copy(falseEasting = m(falseEasting), falseNorthing = m(falseNorthing))
        is PolarStereographicB -> copy(falseEasting = m(falseEasting), falseNorthing = m(falseNorthing))
        is PolarStereographicC -> copy(eastingAtFalseOrigin = m(eastingAtFalseOrigin), northingAtFalseOrigin = m(northingAtFalseOrigin))
    }
}
