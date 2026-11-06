package es.topophone.core.formats.dxf.export

/**
 * Los 255 colores indexados estándar de los CAD (ACI) y el más cercano a un color (S029/RF-6, plan D7). El 0 («por bloque») y el 256
 * («por capa») no se usan. La tabla sale de la regla de la tabla publicada con aritmética entera: 1 a 9 fijos, 10 a 249 en 24 tonos de 15°
 * por 10 variantes de brillo y saturación y 250 a 255 grises.
 */
object AciColor {
    /** El color de una capa sin color de código. */
    const val DEFAULT = 7

    private val FIXED = intArrayOf(0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFFFFFF, 0x808080, 0xC0C0C0)
    private val GRAYS = intArrayOf(51, 91, 132, 173, 214, 255)
    private val BRIGHTNESS = intArrayOf(255, 204, 153, 127, 76)
    private const val COLORS = 255
    private const val FIRST_HUE = 10
    private const val FIRST_GRAY = 250
    private const val VARIANTS = 10
    private const val STEPS_PER_SECTOR = 4

    private val table: IntArray = IntArray(COLORS + 1).also { colors ->
        for (index in 1..COLORS) colors[index] = compute(index)
    }

    /** El RGB (0xRRGGBB) del color ACI [index], de 1 a 255. */
    fun rgb(index: Int): Int {
        require(index in 1..COLORS) { "Índice ACI fuera de 1..255: $index" }
        return table[index]
    }

    /** El índice del color ACI más cercano a [rgb] (0xRRGGBB): distancia euclídea al cuadrado y, en un empate, el índice menor. */
    fun nearest(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        var best = DEFAULT
        var bestDistance = Int.MAX_VALUE
        for (index in 1..COLORS) {
            val color = table[index]
            val dr = ((color shr 16) and 0xFF) - r
            val dg = ((color shr 8) and 0xFF) - g
            val db = (color and 0xFF) - b
            val distance = dr * dr + dg * dg + db * db
            if (distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    /** El más cercano a [rgb] o, sin color, el [DEFAULT] (RF-6). */
    fun nearestOrDefault(rgb: Int?): Int = if (rgb == null) DEFAULT else nearest(rgb)

    private fun compute(index: Int): Int {
        if (index <= FIXED.size) return FIXED[index - 1]
        if (index >= FIRST_GRAY) return GRAYS[index - FIRST_GRAY].let { (it shl 16) or (it shl 8) or it }
        val hueStep = (index - FIRST_HUE) / VARIANTS
        val variant = (index - FIRST_HUE) % VARIANTS
        val value = BRIGHTNESS[variant / 2]
        val low = if (variant % 2 == 0) 0 else value / 2
        val sector = hueStep / STEPS_PER_SECTOR
        val position = hueStep % STEPS_PER_SECTOR
        val rising = low + (value - low) * position / STEPS_PER_SECTOR
        val falling = low + (value - low) * (STEPS_PER_SECTOR - position) / STEPS_PER_SECTOR
        val (r, g, b) = when (sector) {
            0 -> Triple(value, rising, low)
            1 -> Triple(falling, value, low)
            2 -> Triple(low, value, rising)
            3 -> Triple(low, falling, value)
            4 -> Triple(rising, low, value)
            else -> Triple(value, low, falling)
        }
        return (r shl 16) or (g shl 8) or b
    }
}
