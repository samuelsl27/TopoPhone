package es.topophone.core.formats.csv

import es.topophone.core.model.point.ImportEncoding
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Qué es el principio de un fichero que se quiere importar (S028/RF-2, RF-4). */
sealed interface TextProbe {
    /** Sin ni un byte: no hay puntos que importar. */
    data object Empty : TextProbe

    /** Con bytes nulos (una imagen, un Excel, un UTF-16…): no es un texto de puntos. */
    data object Binary : TextProbe

    /** Un texto: su codificación y si empieza con la marca BOM de UTF-8. */
    data class Text(val encoding: ImportEncoding, val hasBom: Boolean) : TextProbe
}

/** Los primeros bytes de un fichero y si tiene más después de ellos. */
class TextSample(val bytes: ByteArray, val truncated: Boolean)

/**
 * La codificación del texto que se importa (S028/RF-4): UTF-8 (con o sin BOM) si los bytes lo son por completo; si no, Windows-1252,
 * que es la que escriben los programas de topografía antiguos. Un byte inválido leyendo como UTF-8 se sustituye por U+FFFD.
 */
object TextDecoding {
    /** Cuánto se mira del fichero para detectar su codificación. */
    const val SAMPLE_BYTES = 64 * 1024

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val BOM_CODE = 0xFEFF // U+FEFF: la marca BOM ya decodificada

    fun charset(encoding: ImportEncoding): Charset = when (encoding) {
        ImportEncoding.UTF8 -> Charsets.UTF_8
        ImportEncoding.WINDOWS_1252 -> Charset.forName("windows-1252")
    }

    /** Lee la muestra: hasta [SAMPLE_BYTES] bytes y si el fichero seguía. */
    fun readSample(stream: InputStream): TextSample {
        val read = stream.readNBytes(SAMPLE_BYTES + 1)
        val truncated = read.size > SAMPLE_BYTES
        return TextSample(if (truncated) read.copyOf(SAMPLE_BYTES) else read, truncated)
    }

    /**
     * Qué texto es [bytes]. Si la muestra está [truncated], un carácter UTF-8 cortado al final no la descarta como UTF-8; un byte
     * inválido en medio, sí.
     */
    fun probe(bytes: ByteArray, truncated: Boolean = false): TextProbe {
        if (bytes.isEmpty()) return TextProbe.Empty
        if (bytes.any { it == 0.toByte() }) return TextProbe.Binary
        val hasBom = bytes.size >= BOM.size && BOM.indices.all { bytes[it] == BOM[it] }
        val encoding = if (isUtf8(bytes, truncated)) ImportEncoding.UTF8 else ImportEncoding.WINDOWS_1252
        return TextProbe.Text(encoding, hasBom = hasBom && encoding == ImportEncoding.UTF8)
    }

    /** Todo [bytes] como texto, sin el BOM si se lee como UTF-8. */
    fun decode(bytes: ByteArray, encoding: ImportEncoding): String {
        val text = String(bytes, charset(encoding))
        return if (encoding == ImportEncoding.UTF8) text.removePrefix(BOM_CODE.toChar().toString()) else text
    }

    /** Un lector por flujo del fichero, sin el BOM si se lee como UTF-8. */
    fun reader(stream: InputStream, encoding: ImportEncoding): BufferedReader {
        val reader = BufferedReader(InputStreamReader(stream, charset(encoding)))
        if (encoding == ImportEncoding.UTF8) {
            reader.mark(1)
            if (reader.read() != BOM_CODE) reader.reset()
        }
        return reader
    }

    private fun isUtf8(bytes: ByteArray, truncated: Boolean): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(ByteBuffer.wrap(bytes), CharBuffer.allocate(bytes.size), !truncated)
        return !result.isError
    }
}
