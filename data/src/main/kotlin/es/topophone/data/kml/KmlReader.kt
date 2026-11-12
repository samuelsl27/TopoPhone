package es.topophone.data.kml

import es.topophone.core.formats.kml.KmlDamagedException
import es.topophone.core.formats.kml.KmlFileKind
import es.topophone.core.formats.kml.KmlParser
import es.topophone.core.formats.kml.KmzEntries
import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.model.cad.GpkgIgnoredReason
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** El fichero que el usuario eligió en el selector: su nombre, su tamaño si lo dice el proveedor y un flujo nuevo en cada llamada (S031/RF-1). */
class KmlInput(val name: String, val sizeBytes: Long?, val open: () -> InputStream)

/** Por qué no se abre la pantalla de importación de un KML o KMZ (S031/RF-3, RF-4, RF-17). */
enum class KmlOpenFailure { EMPTY, NOT_KML, NO_PLACEMARKS, DAMAGED, TOO_LARGE, NO_SPACE, READ_ERROR }

sealed interface KmlReadResult {
    class Read(val store: KmlStore) : KmlReadResult

    data class Failed(val reason: KmlOpenFailure) : KmlReadResult
}

/**
 * Lee un KML o un KMZ una sola vez por flujo y deja lo leído en un almacén temporal de la caché de la app (S031/RF-1 a RF-4, RF-17, plan D2):
 * el recuento por zona y la ficha piden objetos por clave, que un XML no da. El tipo se decide por el contenido ([KmlFileKind]). Los límites
 * de 100 MB del fichero y de 500 MB descomprimidos se miden con los bytes que se leen, no con los que dice el proveedor ni la cabecera del
 * ZIP. Un KMZ se copia a la carpeta temporal (hasta 100 MB leídos) y se abre con `ZipFile`, que lee su directorio central: un KMZ cortado se
 * detecta y las entradas se leen sin descomprimir ninguna a disco, así que un nombre de entrada no puede escribir fuera de la carpeta. La
 * carpeta se borra si se cancela, si falla y, con [KmlStore.close], al terminar; las que dejó una importación interrumpida se borran al
 * arrancar ([cleanUpStale]).
 */
class KmlReader(
    private val cacheDir: File,
    private val freeSpace: () -> Long = { cacheDir.usableSpace },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxFileBytes: Long = KmlFileKind.MAX_FILE_BYTES,
    private val maxUncompressedBytes: Long = KmlFileKind.MAX_UNCOMPRESSED_BYTES,
) {
    private class Failure(val reason: KmlOpenFailure) : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Cuenta los bytes que se leen de verdad contra un límite y corta al pasarse. */
    private class Budget(private val limit: Long) {
        private var used = 0L

        fun spend(bytes: Int) {
            used += bytes
            if (used > limit) throw Failure(KmlOpenFailure.TOO_LARGE)
        }
    }

    private class CountingInput(stream: InputStream, private val budget: Budget) : FilterInputStream(stream) {
        override fun read(): Int = super.read().also { if (it >= 0) budget.spend(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) budget.spend(it) }
    }

    /** Lee [input] y deja el almacén en una carpeta temporal. Cancelar la corrutina borra la carpeta. */
    suspend fun read(input: KmlInput): KmlReadResult = withContext(Dispatchers.IO) {
        cacheDir.mkdirs() // sin la carpeta, el espacio libre sale a cero
        val directory = File(cacheDir, "$PREFIX${newId()}")
        try {
            val head = input.open().use { readHead(it) }
            val size = if (head.isEmpty()) 0L else input.sizeBytes ?: head.size.toLong()
            val result = when (KmlFileKind.of(head, size)) {
                KmlFileKind.Kind.EMPTY -> KmlReadResult.Failed(KmlOpenFailure.EMPTY)
                KmlFileKind.Kind.NOT_KML -> KmlReadResult.Failed(KmlOpenFailure.NOT_KML)
                KmlFileKind.Kind.KML -> readChecked(input, size, directory, kmz = false)
                KmlFileKind.Kind.KMZ -> readChecked(input, size, directory, kmz = true)
            }
            if (result is KmlReadResult.Failed) directory.deleteRecursively() // sin importar por dónde falló, no queda nada
            result
        } catch (e: Failure) {
            directory.deleteRecursively()
            KmlReadResult.Failed(e.reason)
        } catch (e: CancellationException) {
            directory.deleteRecursively()
            throw e
        } catch (e: XmlFormatException) {
            directory.deleteRecursively()
            KmlReadResult.Failed(KmlOpenFailure.DAMAGED)
        } catch (e: KmlDamagedException) {
            directory.deleteRecursively()
            KmlReadResult.Failed(KmlOpenFailure.DAMAGED)
        } catch (e: ZipException) {
            directory.deleteRecursively()
            KmlReadResult.Failed(KmlOpenFailure.DAMAGED)
        } catch (e: EOFException) {
            directory.deleteRecursively()
            KmlReadResult.Failed(KmlOpenFailure.DAMAGED)
        } catch (e: IOException) {
            directory.deleteRecursively()
            KmlReadResult.Failed(if (noSpace(e)) KmlOpenFailure.NO_SPACE else KmlOpenFailure.READ_ERROR)
        } catch (e: SecurityException) {
            directory.deleteRecursively()
            KmlReadResult.Failed(KmlOpenFailure.READ_ERROR)
        }
    }

    /** Borra las carpetas temporales que dejó una importación interrumpida (la app murió a mitad); devuelve cuántas. */
    fun cleanUpStale(): Int = cacheDir.listFiles { f -> f.name.startsWith(PREFIX) }.orEmpty().count { it.deleteRecursively() }

    private suspend fun readChecked(input: KmlInput, size: Long, directory: File, kmz: Boolean): KmlReadResult {
        if (size > maxFileBytes) return KmlReadResult.Failed(KmlOpenFailure.TOO_LARGE)
        if (freeSpace() < size + SPACE_MARGIN_BYTES) return KmlReadResult.Failed(KmlOpenFailure.NO_SPACE)
        directory.mkdirs()
        val job = currentCoroutineContext()[Job]
        val stem = input.name.substringBeforeLast('.', input.name)
        val writer = KmlStoreWriter(directory, stem)
        try {
            val ignored = if (kmz) parseKmz(input, directory, writer, job) else parseKml(input, writer, job)
            val store = writer.finish(ignored, input.name)
            if (store.validObjects == 0) {
                store.close()
                return KmlReadResult.Failed(KmlOpenFailure.NO_PLACEMARKS)
            }
            job?.ensureActive()
            return KmlReadResult.Read(store)
        } finally {
            writer.close()
        }
    }

    private fun parseKml(input: KmlInput, writer: KmlStoreWriter, job: Job?) =
        CountingInput(input.open(), Budget(maxFileBytes)).use { stream ->
            KmlParser().parse(stream) { placemark ->
                job?.ensureActive()
                writer.add(placemark)
            }.ignored
        }

    private fun parseKmz(input: KmlInput, directory: File, writer: KmlStoreWriter, job: Job?): Map<GpkgIgnoredReason, Int> {
        val archive = File(directory, SOURCE_KMZ)
        CountingInput(input.open(), Budget(maxFileBytes)).use { stream -> archive.outputStream().use { stream.copyTo(it, BUFFER_BYTES) } }
        job?.ensureActive()
        try {
            ZipFile(archive).use { zip ->
                val all = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                // Lo que dicen las cabeceras no basta (RF-4), pero lo que dicen de más ya no se descomprime.
                if (all.sumOf { maxOf(it.size, 0L) } > maxUncompressedBytes) throw Failure(KmlOpenFailure.TOO_LARGE)
                val names = all.map { it.name.replace('\\', '/') }.filter { isSafe(it) }
                val main = KmlFileKind.mainEntry(names) ?: throw Failure(KmlOpenFailure.NOT_KML)
                val budget = Budget(maxUncompressedBytes)
                val entries = KmzEntries(names) { name ->
                    all.firstOrNull { it.name.replace('\\', '/') == name }?.let { CountingInput(zip.getInputStream(it), budget) }
                }
                val stream = entries.open(main) ?: throw Failure(KmlOpenFailure.READ_ERROR)
                return stream.use {
                    KmlParser().parse(it, entries, main) { placemark ->
                        job?.ensureActive()
                        writer.add(placemark)
                    }.ignored
                }
            }
        } finally {
            archive.delete()
        }
    }

    /** Un nombre de entrada sin rutas absolutas ni `..` (RNF-T5): nunca se escribe a disco, pero tampoco se enlaza. */
    private fun isSafe(path: String): Boolean = !path.startsWith("/") && path.split('/').none { it == ".." } && !path.contains(':')

    /** Los primeros bytes del fichero (`readNBytes` pide la API 33). */
    private fun readHead(stream: InputStream): ByteArray {
        val head = ByteArray(KmlFileKind.HEAD_BYTES)
        var total = 0
        while (total < head.size) {
            val read = stream.read(head, total, head.size - total)
            if (read < 0) break
            total += read
        }
        return head.copyOf(total)
    }

    private fun noSpace(e: IOException) =
        e.message?.contains("No space left", ignoreCase = true) == true || freeSpace() < SPACE_MARGIN_BYTES

    companion object {
        const val PREFIX = "kml-"
        private const val BUFFER_BYTES = 64 * 1024
        private const val SOURCE_KMZ = ".origen.kmz"
        private const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
    }
}
