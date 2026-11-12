package es.topophone.core.formats.shp

import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgValue

/** Un campo del `.dbf`: sus bytes de nombre, su tipo (`C`, `N`, `F`, `L`, `D`, `M`…), su ancho, sus decimales y dónde empieza en el registro. */
class DbfField(val rawName: ByteArray, val kind: Char, val length: Int, val decimals: Int, val offset: Int) {
    /** Los campos que se muestran: texto, número, fecha y lógico. Los memo y los binarios de dBase 7 no se listan (S030, fuera de alcance). */
    val supported: Boolean get() = kind in SUPPORTED

    /** El tipo de la columna tal como lo pide la ficha: `TEXT`, `INTEGER`, `REAL`, `DATE` o `BOOLEAN`. */
    val declaredType: String
        get() = when (kind) {
            'C' -> "TEXT"
            'N', 'F' -> if (decimals > 0) "REAL" else "INTEGER"
            'D' -> "DATE"
            else -> "BOOLEAN"
        }

    private companion object {
        const val SUPPORTED = "CNFLD"
    }
}

/** Un registro: su posición desde 1, si está marcado como borrado y el valor de cada columna, en el orden de [DbfReader.columns]. */
class DbfRecord(val index: Int, val deleted: Boolean, val values: List<GpkgValue>)

/**
 * Lee un `.dbf` de [source] (S030/RF-18, RF-20, RF-21): cabecera de 32 bytes, descriptores de 32 bytes hasta `0x0D`, y registros de longitud
 * fija, así que el registro `n` se lee por su posición. Los nombres y los textos se decodifican con [encoding]. Un fichero cortado da solo los
 * registros que caben enteros; uno con más registros que el `.shp` da los suyos, y quien lo usa mira solo los que tienen objeto.
 */
class DbfReader private constructor(
    private val source: ShpByteSource,
    private val headerLength: Int,
    private val recordLength: Int,
    private val allFields: List<DbfField>,
    val recordCount: Int,
    val encoding: ShpEncoding,
) {
    private val shown = allFields.filter { it.supported }

    /** Las columnas que se muestran, con su tipo. */
    val columns: List<GpkgColumn> = shown.map { GpkgColumn(String(it.rawName, encoding.charset).trimEnd('\u0000', ' '), it.declaredType) }

    /** El mismo `.dbf` leído con otra codificación. */
    fun withEncoding(other: ShpEncoding): DbfReader = DbfReader(source, headerLength, recordLength, allFields, recordCount, other)

    /** La codificación que dice el contenido de los registros: UTF-8 si todo es UTF-8 válido y Windows-1252 si no (sin `.cpg`, RF-18). */
    fun detectEncoding(): ShpEncoding {
        val from = headerLength.toLong()
        val to = from + recordCount.toLong() * recordLength
        return if (ShpEncodings.isValidUtf8(source, from, to)) ShpEncoding.UTF_8 else ShpEncoding.WINDOWS_1252
    }

    /** Si el registro [index], desde 1, está marcado como borrado: lee solo su primer byte. */
    fun isDeleted(index: Int): Boolean {
        if (index < 1 || index > recordCount) return false
        val flag = ByteArray(1)
        return source.read(headerLength.toLong() + (index - 1).toLong() * recordLength, flag) == 1 && flag[0] == DELETED
    }

    /** El registro [index], desde 1, o null si no existe (RF-20). */
    fun record(index: Int): DbfRecord? {
        if (index < 1 || index > recordCount) return null
        val bytes = ByteArray(recordLength)
        if (source.read(headerLength.toLong() + (index - 1).toLong() * recordLength, bytes) < recordLength) return null
        return DbfRecord(index, bytes[0] == DELETED, shown.map { value(bytes, it) })
    }

    private fun value(bytes: ByteArray, field: DbfField): GpkgValue {
        val raw = String(bytes, field.offset, field.length, encoding.charset)
        val text = raw.trim(' ', '\u0000')
        if (text.isEmpty()) return GpkgValue.Null
        return when (field.kind) {
            'C' -> GpkgValue.Text(raw.trimEnd(' ', '\u0000'))
            'N', 'F' -> number(text, field)
            'D' -> date(text)
            else -> logical(text)
        }
    }

    private fun number(text: String, field: DbfField): GpkgValue {
        if (field.decimals == 0) text.toLongOrNull()?.let { return GpkgValue.Integer(it) }
        val value = text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return GpkgValue.Null // «****» es un desbordamiento
        return GpkgValue.Real(value)
    }

    private fun date(text: String): GpkgValue {
        if (text.length != 8 || !text.all { it.isDigit() } || text == "00000000") return GpkgValue.Null
        return GpkgValue.Date("${text.substring(0, 4)}-${text.substring(4, 6)}-${text.substring(6, 8)}")
    }

    private fun logical(text: String): GpkgValue = when (text[0]) {
        'Y', 'y', 'T', 't' -> GpkgValue.Bool(true)
        'N', 'n', 'F', 'f' -> GpkgValue.Bool(false)
        else -> GpkgValue.Null
    }

    companion object {
        private const val DELETED = '*'.code.toByte()
        private const val FIELD_TERMINATOR = 0x0D.toByte()
        private const val HEADER_BYTES = 32
        private const val MAX_FIELDS = 2046

        /** Abre el `.dbf` de [source] con [encoding], o devuelve null si su cabecera no es la de un `.dbf`. */
        fun open(source: ShpByteSource, encoding: ShpEncoding = ShpEncoding.UTF_8): DbfReader? {
            val head = ByteArray(HEADER_BYTES)
            if (source.read(0, head) < HEADER_BYTES) return null
            val declared = u32(head, 4)
            val headerLength = u16(head, 8)
            val recordLength = u16(head, 10)
            if (headerLength < HEADER_BYTES + 1 || recordLength < 1 || headerLength > source.size) return null
            val fields = ArrayList<DbfField>()
            var position = HEADER_BYTES
            var offset = 1 // el primer byte del registro es la marca de borrado
            val descriptor = ByteArray(HEADER_BYTES)
            while (position + HEADER_BYTES <= headerLength) {
                if (source.read(position.toLong(), descriptor, 0, 1) < 1) return null
                if (descriptor[0] == FIELD_TERMINATOR) break
                if (source.read(position.toLong(), descriptor) < HEADER_BYTES || fields.size >= MAX_FIELDS) return null
                val kind = descriptor[11].toInt().toChar()
                var length = descriptor[16].toInt() and 0xFF
                var decimals = descriptor[17].toInt() and 0xFF
                if (kind == 'C') { // dBase y Clipper guardan los textos de más de 255 con el byte de decimales como parte alta
                    length += decimals shl 8
                    decimals = 0
                }
                if (offset + length > recordLength) return null
                fields +=
                    DbfField(descriptor.copyOfRange(0, 11).takeWhile { it != 0.toByte() }.toByteArray(), kind, length, decimals, offset)
                offset += length
                position += HEADER_BYTES
            }
            val available = ((source.size - headerLength) / recordLength).coerceAtLeast(0)
            val count = minOf(declared, available).toInt()
            return DbfReader(source, headerLength, recordLength, fields, count, encoding)
        }

        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)
    }
}
