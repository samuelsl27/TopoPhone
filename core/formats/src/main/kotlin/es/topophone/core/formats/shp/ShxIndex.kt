package es.topophone.core.formats.shp

import java.nio.ByteBuffer

/**
 * Dónde empieza cada registro de un `.shp`, en bytes y por su posición desde 1 (S030/RF-19), para leer uno suelto sin recorrer los
 * anteriores. Sale del `.shx` si cuadra con el `.shp` ([fromShx]) y, si falta o no cuadra, de una pasada por las cabeceras del `.shp`.
 * [complete] es false si el `.shp` está cortado: solo están los registros que caben enteros.
 */
class ShxIndex(val offsets: LongArray, val complete: Boolean = true, val fromShx: Boolean = false) {
    val size: Int get() = offsets.size

    /** Dónde empieza el registro [index], desde 1. */
    fun offsetOf(index: Int): Long = offsets[index - 1]

    companion object {
        private const val FIRST_RECORD_WORDS = 50L
        private const val ENTRY_BYTES = 8
        private const val CHUNK_ENTRIES = 8192

        /** El índice del `.shx` si lo hay y cuadra con el `.shp`, y si no, el de una pasada por el `.shp`. */
        fun of(shx: ShpByteSource?, shp: ShpReader): ShxIndex = shx?.let { read(it, shp) } ?: scan(shp)

        /** Recorre las cabeceras de los registros del `.shp`, sin leer sus formas. */
        fun scan(shp: ShpReader): ShxIndex {
            var offsets = LongArray(1024)
            var count = 0
            var position = 100L
            while (position < shp.sizeBytes) {
                val (_, words) = shp.recordHeaderAt(position) ?: return ShxIndex(offsets.copyOf(count), complete = false)
                if (count == offsets.size) offsets = offsets.copyOf(count * 2)
                offsets[count++] = position
                position += ShpRecord.RECORD_HEADER_BYTES + words.toLong() * 2
            }
            return ShxIndex(offsets.copyOf(count), complete = true)
        }

        /**
         * El índice de [shx] si cuadra con [shp], o null: la misma cabecera y el mismo tipo, un número entero de entradas, el primer
         * registro donde debe, cada uno justo tras el anterior, el último acabando donde acaba el `.shp`, y las longitudes de la primera
         * y de la última entrada iguales a las del `.shp`.
         */
        fun read(shx: ShpByteSource, shp: ShpReader): ShxIndex? {
            val size = shx.size
            if (size < ShpFileKind.HEAD_BYTES || (size - ShpFileKind.HEAD_BYTES) % ENTRY_BYTES != 0L) return null
            val head = ByteArray(ShpFileKind.HEAD_BYTES)
            if (shx.read(0, head) < head.size || !ShpFileKind.isShapeHeader(head, size)) return null
            if (ShpFileKind.shapeType(head) != shp.header.shapeType) return null
            val total = (size - ShpFileKind.HEAD_BYTES) / ENTRY_BYTES
            if (total > Int.MAX_VALUE) return null
            val offsets = LongArray(total.toInt())
            val lengths = IntArray(total.toInt())
            var expected = FIRST_RECORD_WORDS
            var done = 0
            val chunk = ByteArray(CHUNK_ENTRIES * ENTRY_BYTES)
            while (done < offsets.size) {
                val entries = minOf(CHUNK_ENTRIES, offsets.size - done)
                if (shx.read(ShpFileKind.HEAD_BYTES.toLong() + done.toLong() * ENTRY_BYTES, chunk, 0, entries * ENTRY_BYTES) <
                    entries * ENTRY_BYTES
                ) {
                    return null
                }
                val buffer = ByteBuffer.wrap(chunk)
                for (i in 0 until entries) {
                    val offsetWords = buffer.getInt(i * ENTRY_BYTES).toLong()
                    val lengthWords = buffer.getInt(i * ENTRY_BYTES + 4)
                    if (offsetWords != expected || lengthWords < 2) return null
                    offsets[done + i] = offsetWords * 2
                    lengths[done + i] = lengthWords
                    expected += 4 + lengthWords // la cabecera del registro (4 palabras de 16 bits) y su contenido
                }
                done += entries
            }
            if (expected * 2 != shp.sizeBytes) return null
            if (offsets.isNotEmpty()) {
                for (i in listOf(0, offsets.size - 1)) if (shp.recordHeaderAt(offsets[i])?.second != lengths[i]) return null
            }
            return ShxIndex(offsets, complete = true, fromShx = true)
        }
    }
}
