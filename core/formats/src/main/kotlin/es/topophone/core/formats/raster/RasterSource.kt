package es.topophone.core.formats.raster

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile

/**
 * Un fichero de ráster con acceso directo (S045, plan D4): un GeoTIFF se lee a saltos entre su directorio y sus bloques, y el selector
 * de ficheros del móvil solo da un flujo, así que quien importa copia el fichero a la caché y lo abre con esto.
 */
interface RasterSource {
    /** Los bytes del fichero. */
    val size: Long

    /** Lee exactamente [length] bytes desde [position] en [buffer]; [EOFException] si el fichero acaba antes. */
    fun read(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset)

    /** [length] bytes desde [position]. */
    fun readBytes(position: Long, length: Int): ByteArray = ByteArray(length).also { read(position, it, 0, length) }
}

/** Un [RasterSource] sobre unos bytes en memoria, para los ficheros pequeños y los tests. */
class ByteArrayRasterSource(private val bytes: ByteArray) : RasterSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (position < 0 ||
            position + length > bytes.size
        ) {
            throw EOFException("El fichero acaba en ${bytes.size} y se piden $length bytes desde $position")
        }
        System.arraycopy(bytes, position.toInt(), buffer, offset, length)
    }
}

/** Un [RasterSource] sobre un fichero del disco, que hay que cerrar. */
class FileRasterSource(file: File) :
    RasterSource,
    Closeable {
    private val access = RandomAccessFile(file, "r")

    override val size: Long = access.length()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (position < 0 ||
            position + length > size
        ) {
            throw EOFException("El fichero acaba en $size y se piden $length bytes desde $position")
        }
        access.seek(position)
        access.readFully(buffer, offset, length)
    }

    override fun close() = access.close()
}
