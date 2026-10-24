package es.topophone.data.point

/**
 * Las frases NMEA de un punto partidas en tramos (S017/RF-12; plan A6). Un promediado de 60 min a 10 Hz son unos 12 MB, y
 * el cursor de Android lee unos 2 MB por fila: pasado [MAX_CHARS] caracteres, las frases van en varias filas.
 */
object NmeaChunks {
    /** Lo que cabe en la fila del punto y en cada tramo: mucho menos que la ventana del cursor. */
    const val MAX_CHARS = 256_000

    /**
     * El texto en tramos de hasta [maxChars] caracteres, cada uno acabado en su salto de línea (salvo el último); una
     * frase más larga que el máximo se parte donde haga falta. Sin texto, un tramo vacío.
     */
    fun split(text: String, maxChars: Int = MAX_CHARS): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + maxChars, text.length)
            if (end == text.length) {
                chunks += text.substring(start)
                break
            }
            val newline = text.lastIndexOf('\n', end - 1)
            val stop = if (newline >= start) newline + 1 else end
            chunks += text.substring(start, stop)
            start = stop
        }
        return chunks
    }

    /** El texto de los [chunks], tal como era antes de partirlo. */
    fun join(chunks: List<String>): String = chunks.joinToString("")
}
