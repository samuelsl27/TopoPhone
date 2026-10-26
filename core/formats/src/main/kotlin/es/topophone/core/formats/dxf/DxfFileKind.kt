package es.topophone.core.formats.dxf

import java.io.InputStream

/** Qué es un fichero que se quiere importar como dibujo (S040/RF-2 a RF-5). */
sealed interface DxfProbe {
    /** Un DXF de texto, que se puede leer. */
    data object Dxf : DxfProbe

    /** Un DWG de la versión [version] (`AC1032`…): no se lee (RNF-T14). */
    data class Dwg(val version: String) : DxfProbe

    /** Un DXF binario: no se lee. */
    data object BinaryDxf : DxfProbe

    /** Sin ni un byte. */
    data object Empty : DxfProbe

    /** Ni un DXF ni un DWG. */
    data object NotDxf : DxfProbe

    /** Un DXF de más de [DxfFileKind.MAX_BYTES]. */
    data object TooLarge : DxfProbe
}

/**
 * Qué es un fichero por su contenido, no por su extensión ni su tipo MIME (S040/RF-2 a RF-5, RNF-T5). Con los primeros
 * [HEAD_BYTES]:
 * - `AC10` y dos cifras más y no es texto de DXF: un DWG (`AC1009`… `AC1032`);
 * - `AutoCAD Binary DXF`: un DXF binario;
 * - un DXF de texto: pares código/valor que, tras un BOM y los comentarios (código 999), empiezan por `0` y `SECTION`.
 */
object DxfFileKind {
    /** Lo que se mira del principio del fichero. */
    const val HEAD_BYTES = 1024

    /** El tamaño máximo de un DXF (RF-5). */
    const val MAX_BYTES = 100L * 1024 * 1024

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val BINARY_SENTINEL = "AutoCAD Binary DXF"
    private val DWG_VERSION = Regex("^AC10\\d\\d")

    /** Los primeros [HEAD_BYTES] de un flujo. */
    fun readHead(stream: InputStream): ByteArray = stream.readNBytes(HEAD_BYTES)

    /** Qué es un fichero de [sizeBytes] cuyo principio es [head]. */
    fun probe(head: ByteArray, sizeBytes: Long): DxfProbe {
        if (sizeBytes == 0L || head.isEmpty()) return DxfProbe.Empty
        val start = String(head, 0, minOf(head.size, 32), Charsets.ISO_8859_1)
        DWG_VERSION.find(start)?.let { return DxfProbe.Dwg(it.value) }
        if (start.startsWith(BINARY_SENTINEL)) return DxfProbe.BinaryDxf
        if (!isTextDxf(head)) return DxfProbe.NotDxf
        return if (sizeBytes > MAX_BYTES) DxfProbe.TooLarge else DxfProbe.Dxf
    }

    private fun isTextDxf(head: ByteArray): Boolean {
        if (head.any { it == 0.toByte() }) return false
        val offset = if (head.size >= BOM.size && BOM.indices.all { head[it] == BOM[it] }) BOM.size else 0
        val lines = String(head, offset, head.size - offset, Charsets.ISO_8859_1).split('\r', '\n').filter { it.isNotEmpty() }
        var i = 0
        while (i + 1 < lines.size) {
            val code = lines[i].trim().toIntOrNull() ?: return false
            if (code == COMMENT) {
                i += 2
                continue
            }
            return code == 0 && lines[i + 1].trim() == "SECTION"
        }
        return false
    }

    private const val COMMENT = 999
}
