package es.topophone.core.formats.shp

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Las dos codificaciones con que se lee el `.dbf` de un Shapefile (S030/RF-18). */
enum class ShpEncoding(val charset: Charset, val label: String) {
    UTF_8(Charsets.UTF_8, "UTF-8"),
    WINDOWS_1252(Charset.forName("windows-1252"), "Windows-1252"),
}

/** Lo que dice un `.cpg`: la codificación con la que se lee y si es una de las que se conocen (RF-18). */
data class CpgDeclaration(val encoding: ShpEncoding, val known: Boolean)

/**
 * La codificación del `.dbf` (S030/RF-18): la que declara el `.cpg` —UTF-8 y Windows-1252 con sus nombres habituales; cualquier otra se
 * lee como Windows-1252 y se avisa— y, sin `.cpg`, UTF-8 si todo el texto del `.dbf` lo es y Windows-1252 si no.
 */
object ShpEncodings {
    private const val CHUNK = 64 * 1024
    private val UTF8_NAMES = setOf("UTF-8", "UTF8", "UTF_8", "65001")
    private val WINDOWS_1252_NAMES = setOf("1252", "ANSI 1252", "ANSI1252", "WINDOWS-1252", "WINDOWS1252", "WINDOWS_1252", "CP1252")

    /** Lo que declara el texto de un `.cpg`, o null si no hay `.cpg` o está en blanco (y entonces se decide por el contenido). */
    fun fromCpg(text: String?): CpgDeclaration? {
        val name = text?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when (name) {
            in UTF8_NAMES -> CpgDeclaration(ShpEncoding.UTF_8, known = true)
            in WINDOWS_1252_NAMES -> CpgDeclaration(ShpEncoding.WINDOWS_1252, known = true)
            else -> CpgDeclaration(ShpEncoding.WINDOWS_1252, known = false)
        }
    }

    /** Si los bytes de [source] entre [from] y [to] son UTF-8 válido, leídos de [CHUNK] en [CHUNK] sin partir ningún carácter. */
    fun isValidUtf8(source: ShpByteSource, from: Long, to: Long): Boolean {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val bytes = ByteArray(CHUNK)
        val chars = CharBuffer.allocate(CHUNK)
        var position = from
        var carried = 0
        while (true) {
            val want = minOf((CHUNK - carried).toLong(), to - position).toInt()
            val read = if (want > 0) source.read(position, bytes, carried, want) else 0
            position += read
            val last = read == 0 || position >= to
            val input = ByteBuffer.wrap(bytes, 0, carried + read)
            chars.clear()
            if (decoder.decode(input, chars, last).isError) return false
            carried = input.remaining() // los bytes de un carácter que sigue en la lectura siguiente
            System.arraycopy(bytes, input.position(), bytes, 0, carried)
            if (last) return !decoder.flush(chars).isError && carried == 0
        }
    }
}
