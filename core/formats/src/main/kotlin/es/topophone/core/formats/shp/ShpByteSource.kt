package es.topophone.core.formats.shp

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * De dónde se leen los bytes de un `.shp`, un `.shx` o un `.dbf` (S030/RF-15, RF-19): con acceso por posición, sin cargar el fichero entero
 * en memoria (RNF). [read] copia hasta [length] bytes desde [position] y dice cuántos ha copiado (menos si el fichero se acaba).
 */
interface ShpByteSource : Closeable {
    val size: Long

    fun read(position: Long, into: ByteArray, offset: Int = 0, length: Int = into.size - offset): Int

    override fun close() = Unit

    companion object {
        /** Un fichero de disco, leído con `RandomAccessFile`. Hay que cerrarlo. */
        fun of(file: File): ShpByteSource = FileSource(RandomAccessFile(file, "r"))

        /** Unos bytes en memoria, para los ficheros pequeños y los tests. */
        fun of(bytes: ByteArray): ShpByteSource = ArraySource(bytes)
    }
}

private class FileSource(private val file: RandomAccessFile) : ShpByteSource {
    override val size: Long get() = file.length()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= size || length <= 0) return 0
        file.seek(position)
        var total = 0
        while (total < length) {
            val n = file.read(into, offset + total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    override fun close() = file.close()
}

private class ArraySource(private val bytes: ByteArray) : ShpByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || position >= bytes.size || length <= 0) return 0
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), into, offset, n)
        return n
    }
}
