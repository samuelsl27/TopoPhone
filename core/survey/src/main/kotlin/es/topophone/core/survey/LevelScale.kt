package es.topophone.core.survey

import kotlin.math.pow

/**
 * La escala del nivel vertical (S019/A2, RF-7): el radio que se ve, en pasos de 1, 2 y 5 por cada potencia de diez y sin límite
 * práctico por arriba, para que estén a la vista la burbuja, con un 25 % de margen, y la banda de la tolerancia, con un 20 %.
 * La cifra del desmonte o del terraplén nunca depende de ella (RF-6): solo cambia el dibujo.
 */
object LevelScale {
    /** De 5 cm a 5.000 km: las diferencias de cota reales caben de sobra. */
    val STEPS: List<Double> = listOf(0.05, 0.1, 0.2, 0.5) +
        (0..MAX_EXPONENT).flatMap { exponent -> listOf(1.0, 2.0, 5.0).map { it * 10.0.pow(exponent) } }

    private const val TIP_MARGIN = 1.25
    private const val TOLERANCE_MARGIN = 1.2

    /** Con la escala nueva más pequeña que la de ahora, la burbuja con su margen tiene que caber en este tanto por uno del paso. */
    private const val SHRINK_FILL = 0.8

    /**
     * El radio visible para una diferencia de cota de valor absoluto [dzAbsM] y una tolerancia vertical [toleranceM].
     * [previous] es el de la época anterior: sube enseguida y solo baja si la burbuja cabe holgada en el paso menor, para que
     * no salte de ida y vuelta en el borde de un paso.
     */
    fun radius(previous: Double?, dzAbsM: Double, toleranceM: Double): Double {
        val dz = if (dzAbsM.isNaN()) 0.0 else dzAbsM
        val floor = maxOf(TOLERANCE_MARGIN * toleranceM, STEPS.first())
        val need = maxOf(TIP_MARGIN * dz, floor)
        val fitting = STEPS.firstOrNull { it >= need } ?: STEPS.last()
        if (previous == null || fitting >= previous) return fitting
        var result = previous
        for (step in STEPS.filter { it < previous }.sortedDescending()) {
            if (step >= floor && TIP_MARGIN * dz <= SHRINK_FILL * step) result = step else break
        }
        return result
    }

    private const val MAX_EXPONENT = 6
}
