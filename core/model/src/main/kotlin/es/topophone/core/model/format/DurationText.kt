package es.topophone.core.model.format

/** Un tiempo escrito en minutos y segundos, como "12:30", o en segundos, como "90" (S017/RF-1, RF-16). */
object DurationText {
    private const val SECONDS_PER_MINUTE = 60

    /** "m:ss": 5 s son "0:05" y 3600 s, "60:00". */
    fun format(seconds: Int): String = "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"

    /**
     * Los segundos de [text], que puede ser "m:ss" (con dos cifras de segundos, menos de 60) o un entero de segundos;
     * null si está mal escrito o fuera de [range].
     */
    fun parse(text: String, range: IntRange): Int? {
        val compact = text.trim()
        val colon = compact.indexOf(':')
        val value: Long? = if (colon < 0) {
            compact.toDigitsOrNull()?.toLong()
        } else {
            val minutes = compact.substring(0, colon).toDigitsOrNull()
            val seconds = compact.substring(colon + 1).takeIf { it.length == 2 }?.toDigitsOrNull()
            if (minutes == null ||
                seconds == null ||
                seconds >= SECONDS_PER_MINUTE
            ) {
                null
            } else {
                minutes.toLong() * SECONDS_PER_MINUTE + seconds
            }
        }
        return value?.takeIf { it in range }?.toInt()
    }

    /** Solo cifras ASCII; null si no hay ninguna, si hay otra cosa o si no cabe en un entero. */
    private fun String.toDigitsOrNull(): Int? = takeIf { isNotEmpty() && all { it in '0'..'9' } }?.toIntOrNull()
}
