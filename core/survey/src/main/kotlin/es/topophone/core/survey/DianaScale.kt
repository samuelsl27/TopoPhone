package es.topophone.core.survey

/**
 * La escala de la diana métrica (S018/A4, RF-16): el radio que se ve, en pasos de 1, 2 y 5, para que estén a la vista la
 * punta, con un 25 % de margen, y el círculo de la tolerancia, con un 20 %. Es un `Canvas` métrico aparte del mapa.
 */
object DianaScale {
    /** De 5 cm a 2 m: la tolerancia llega a 1 m. */
    val STEPS = listOf(0.05, 0.1, 0.2, 0.5, 1.0, 2.0)

    private const val TIP_MARGIN = 1.25
    private const val TOLERANCE_MARGIN = 1.2

    /** Con la escala nueva más pequeña que la de ahora, la punta con su margen tiene que caber en este tanto por uno del paso. */
    private const val SHRINK_FILL = 0.8

    /**
     * El radio visible para una punta a [tipDistanceM] del punto y una tolerancia [toleranceM]. [previous] es el de la
     * época anterior: sube enseguida y solo baja si la punta cabe holgada en el paso menor, para que no salte de ida y
     * vuelta en el borde de un paso.
     */
    fun radius(previous: Double?, tipDistanceM: Double, toleranceM: Double): Double {
        val floor = maxOf(TOLERANCE_MARGIN * toleranceM, STEPS.first())
        val need = maxOf(TIP_MARGIN * tipDistanceM, floor)
        val fitting = STEPS.firstOrNull { it >= need } ?: STEPS.last()
        if (previous == null || fitting >= previous) return fitting
        var result = previous
        for (step in STEPS.filter { it < previous }.sortedDescending()) {
            if (step >= floor && TIP_MARGIN * tipDistanceM <= SHRINK_FILL * step) result = step else break
        }
        return result
    }
}
