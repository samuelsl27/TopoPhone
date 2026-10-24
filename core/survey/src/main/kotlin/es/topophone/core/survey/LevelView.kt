package es.topophone.core.survey

/**
 * Lo que dibuja el nivel vertical del replanteo, puro y sin pantalla (S019/RF-7): dónde cae la burbuja y qué parte del radio es
 * la banda de la tolerancia.
 */
object LevelView {
    /**
     * La burbuja para una diferencia de cota [dzM] (punta menos cota de diseño) en un tubo de radio [radiusM], de -1 a 1 con el
     * centro en 0: arriba (positivo) es desmonte, abajo, terraplén. Fuera del radio se queda en el borde; una diferencia no
     * válida, en el centro.
     */
    fun bubble(dzM: Double, radiusM: Double): Double {
        if (dzM.isNaN()) return 0.0
        return 0.0 + (dzM / radiusM).coerceIn(-1.0, 1.0) // el 0.0 evita el cero negativo
    }

    /** El semiancho de la banda de la tolerancia como fracción del radio, sin pasar del borde (S019/RF-7). */
    fun bandFraction(toleranceM: Double, radiusM: Double): Double = (toleranceM / radiusM).coerceIn(0.0, 1.0)
}
