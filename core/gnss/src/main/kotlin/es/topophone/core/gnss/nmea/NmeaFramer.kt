package es.topophone.core.gnss.nmea

/**
 * Frase NMEA 0183 válida.
 *
 * @property talker identificador de constelación o de fabricante (`GN`, `GP`, `GL`…).
 * @property type tipo de frase (`GGA`, `GST`…).
 * @property fields campos tras la dirección, vacíos incluidos, sin la suma de control.
 * @property raw frase completa desde `$` hasta la suma de control, sin fin de línea.
 */
data class NmeaSentence(val talker: String, val type: String, val fields: List<String>, val raw: String)

/** Suma de control NMEA: XOR de los bytes entre `$` y `*`. */
object NmeaChecksum {

    fun compute(body: String): Int = body.fold(0) { acc, c -> acc xor c.code }

    /** `true` si [line] es `$cuerpo*HH` y HH (en cualquier capitalización) es la suma de control del cuerpo. */
    fun isValid(line: String): Boolean {
        if (!line.startsWith("$")) return false
        val star = line.lastIndexOf('*')
        if (star < 1 || star != line.length - 3) return false
        val expected = line.substring(star + 1).toIntOrNull(16) ?: return false
        return compute(line.substring(1, star)) == expected
    }
}

/**
 * Separa las frases NMEA válidas del flujo de bytes del receptor (S002/RF-11).
 *
 * Tolera frases partidas entre bloques, fines de línea `\r\n` o `\n` y datos binarios
 * intercalados (RTCM u otros), que se descartan sin error. No es seguro entre hilos:
 * lo usa un único lector.
 */
class NmeaFramer(private val maxLength: Int = DEFAULT_MAX_LENGTH) {

    private val buffer = StringBuilder()
    private var collecting = false

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<NmeaSentence> {
        val sentences = mutableListOf<NmeaSentence>()
        for (index in offset until offset + length) {
            val value = bytes[index].toInt() and 0xFF
            when {
                value == '$'.code -> start()
                !collecting -> Unit
                value == '\n'.code -> finish()?.let(sentences::add)
                value == '\r'.code -> Unit
                value < PRINTABLE_MIN || value > PRINTABLE_MAX -> discard()
                buffer.length >= maxLength -> discard()
                else -> buffer.append(value.toChar())
            }
        }
        return sentences
    }

    private fun start() {
        buffer.setLength(0)
        buffer.append('$')
        collecting = true
    }

    private fun discard() {
        buffer.setLength(0)
        collecting = false
    }

    private fun finish(): NmeaSentence? {
        val line = buffer.toString()
        discard()
        if (!NmeaChecksum.isValid(line)) return null
        val parts = line.substring(1, line.lastIndexOf('*')).split(',')
        val address = parts.first()
        if (address.length < 3) return null
        val talker = if (address.startsWith("P")) "P" else address.take(2)
        return NmeaSentence(talker = talker, type = address.removePrefix(talker), fields = parts.drop(1), raw = line)
    }

    companion object {
        /** El estándar limita a 82 caracteres; Emlid y otros fabricantes los superan con más decimales. */
        const val DEFAULT_MAX_LENGTH = 256
        private const val PRINTABLE_MIN = 0x20
        private const val PRINTABLE_MAX = 0x7E
    }
}
