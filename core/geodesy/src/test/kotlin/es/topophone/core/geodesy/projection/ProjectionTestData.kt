package es.topophone.core.geodesy.projection

/** Una instancia de cada variante de proyección con falsos Este y Norte reconocibles, para probar el contrato sin calcular nada (S032 T1). */
internal object ProjectionTestData {
    const val FALSE_EASTING = 1_000.0
    const val FALSE_NORTHING = 2_000.0

    val all: List<ProjectionParameters> = listOf(
        LambertConic1Sp(40.0, -3.0, 0.9996, FALSE_EASTING, FALSE_NORTHING),
        LambertConic2Sp(40.0, -3.0, 38.0, 42.0, FALSE_EASTING, FALSE_NORTHING),
        MercatorA(-3.0, 0.9996, FALSE_EASTING, FALSE_NORTHING),
        MercatorB(-3.0, 42.0, FALSE_EASTING, FALSE_NORTHING),
        ObliqueMercatorA(4.0, 115.0, 53.0, 53.0, 0.99984, FALSE_EASTING, FALSE_NORTHING),
        ObliqueMercatorB(4.0, 115.0, 53.0, 53.0, 0.99984, FALSE_EASTING, FALSE_NORTHING),
        ObliqueStereographic(52.0, 5.0, 0.9999, FALSE_EASTING, FALSE_NORTHING),
        PolarStereographicA(90.0, 0.0, 0.994, FALSE_EASTING, FALSE_NORTHING),
        PolarStereographicB(-71.0, 0.0, FALSE_EASTING, FALSE_NORTHING),
        PolarStereographicC(-67.0, 140.0, FALSE_EASTING, FALSE_NORTHING),
    )

    /** Los falsos Este y Norte de una variante, tal como se escribieron. */
    fun falseOrigin(parameters: ProjectionParameters): Pair<Double, Double> = when (parameters) {
        is LambertConic1Sp -> parameters.falseEasting to parameters.falseNorthing
        is LambertConic2Sp -> parameters.eastingAtFalseOrigin to parameters.northingAtFalseOrigin
        is MercatorA -> parameters.falseEasting to parameters.falseNorthing
        is MercatorB -> parameters.falseEasting to parameters.falseNorthing
        is ObliqueMercatorA -> parameters.falseEasting to parameters.falseNorthing
        is ObliqueMercatorB -> parameters.eastingAtCentre to parameters.northingAtCentre
        is ObliqueStereographic -> parameters.falseEasting to parameters.falseNorthing
        is PolarStereographicA -> parameters.falseEasting to parameters.falseNorthing
        is PolarStereographicB -> parameters.falseEasting to parameters.falseNorthing
        is PolarStereographicC -> parameters.eastingAtFalseOrigin to parameters.northingAtFalseOrigin
    }

    /** La misma variante con otros falsos y todo lo demás igual: sirve para comparar lo que NO es un falso. */
    fun withFalseOrigin(parameters: ProjectionParameters, easting: Double, northing: Double): ProjectionParameters = when (parameters) {
        is LambertConic1Sp -> parameters.copy(falseEasting = easting, falseNorthing = northing)
        is LambertConic2Sp -> parameters.copy(eastingAtFalseOrigin = easting, northingAtFalseOrigin = northing)
        is MercatorA -> parameters.copy(falseEasting = easting, falseNorthing = northing)
        is MercatorB -> parameters.copy(falseEasting = easting, falseNorthing = northing)
        is ObliqueMercatorA -> parameters.copy(falseEasting = easting, falseNorthing = northing)
        is ObliqueMercatorB -> parameters.copy(eastingAtCentre = easting, northingAtCentre = northing)
        is ObliqueStereographic -> parameters.copy(falseEasting = easting, falseNorthing = northing)
        is PolarStereographicA -> parameters.copy(falseEasting = easting, falseNorthing = northing)
        is PolarStereographicB -> parameters.copy(falseEasting = easting, falseNorthing = northing)
        is PolarStereographicC -> parameters.copy(eastingAtFalseOrigin = easting, northingAtFalseOrigin = northing)
    }
}
