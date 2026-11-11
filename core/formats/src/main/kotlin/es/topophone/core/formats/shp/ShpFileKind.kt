package es.topophone.core.formats.shp

/**
 * Qué es un fichero que se quiere importar como Shapefile, por su contenido y no por su extensión ni su tipo MIME (S030/RF-2, RF-3,
 * RNF-T5): la cabecera de 100 bytes de un `.shp` (código 9994, versión 1000 y un tipo de geometría que existe) y la de un ZIP (`PK`).
 * Ojo: el `.shx` tiene la misma cabecera que el `.shp`; quien clasifica ficheros lo distingue por su extensión ([ShpSelection]).
 */
object ShpFileKind {
    /** Lo que mide la cabecera de un `.shp`. */
    const val HEAD_BYTES = 100

    /** Lo máximo que pueden sumar los ficheros elegidos: un ZIP, o los ficheros sueltos (RF-6). */
    const val MAX_PICKED_BYTES = 100L * 1024 * 1024

    /** Lo máximo que puede descomprimir un ZIP (RF-6). */
    const val MAX_UNCOMPRESSED_BYTES = 500L * 1024 * 1024

    private const val FILE_CODE = 9994
    private const val VERSION = 1000

    /** Los tipos de forma que define el formato: nulo, punto, multipunto, línea, polígono, con M o con Z, y `MultiPatch`. */
    private val SHAPE_TYPES = setOf(0, 1, 3, 5, 8, 11, 13, 15, 18, 21, 23, 25, 28, 31)

    /** El tipo de forma de la cabecera, o null si [head] no la tiene completa. */
    fun shapeType(head: ByteArray): Int? = if (head.size < 36) null else littleEndianInt(head, 32)

    /** Si [head] es la cabecera de un `.shp` (o de un `.shx`) de un fichero de [sizeBytes]. */
    fun isShapeHeader(head: ByteArray, sizeBytes: Long): Boolean {
        if (head.size < HEAD_BYTES || sizeBytes < HEAD_BYTES) return false
        return bigEndianInt(head, 0) == FILE_CODE && littleEndianInt(head, 28) == VERSION && littleEndianInt(head, 32) in SHAPE_TYPES
    }

    /** Si [head] empieza como un ZIP: con una entrada (`PK 03 04`), vacío (`PK 05 06`) o partido (`PK 07 08`). */
    fun isZip(head: ByteArray): Boolean {
        if (head.size < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) return false
        val kind = head[2].toInt() to head[3].toInt()
        return kind == (3 to 4) || kind == (5 to 6) || kind == (7 to 8)
    }

    private fun bigEndianInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    private fun littleEndianInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
}
