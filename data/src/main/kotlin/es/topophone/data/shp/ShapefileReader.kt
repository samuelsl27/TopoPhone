package es.topophone.data.shp

import es.topophone.core.formats.shp.ShpEntry
import es.topophone.core.formats.shp.ShpFileKind
import es.topophone.core.formats.shp.ShpPick
import es.topophone.core.formats.shp.ShpPickResult
import es.topophone.core.formats.shp.ShpRejection
import es.topophone.core.formats.shp.ShpSelection
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Un fichero que el usuario eligió en el selector: su nombre, su tamaño si lo dice el proveedor y un flujo nuevo en cada llamada (S030/RF-1). */
class ShpInput(val name: String, val sizeBytes: Long?, val open: () -> InputStream)

/** Por qué no se abre la pantalla de importación de un Shapefile (S030/RF-3, RF-4, RF-6, RF-30). */
enum class ShpOpenFailure { EMPTY, NOT_SHAPEFILE, NO_SHP, MIXED_SELECTION, DAMAGED, TOO_LARGE, NO_SPACE, READ_ERROR }

/** Una capa ya copiada a la carpeta temporal: su `.shp` y los ficheros que se asocian a él (S030/RF-2). */
class ShpLayerOnDisk(val name: String, val shp: File, val shx: File?, val dbf: File?, val prj: File?, val cpg: File?)

/**
 * Lo que dejó [ShapefileReader.extract]: la carpeta temporal con las capas, los ficheros huérfanos (S030/RF-5), los nombres de ZIP que se
 * descartaron por salir de la carpeta y el nombre con el que se guarda el dibujo (S030/RF-26). [close] borra la carpeta.
 */
class ShpExtracted(
    val directory: File,
    val layers: List<ShpLayerOnDisk>,
    val orphans: List<String>,
    val dropped: List<String>,
    val drawingName: String,
) : AutoCloseable {
    override fun close() {
        directory.deleteRecursively()
    }
}

sealed interface ShpExtractResult {
    class Extracted(val extracted: ShpExtracted) : ShpExtractResult

    data class Failed(val reason: ShpOpenFailure) : ShpExtractResult
}

/**
 * Deja lo que eligió el usuario —un ZIP o ficheros sueltos— en una carpeta temporal de la caché de la app (S030/RF-1 a RF-7, RF-29, RF-30,
 * decisión D5 del plan): la lectura por registro y el recuento por zona necesitan acceso aleatorio, que un ZIP comprimido no da. El tipo de
 * cada fichero se decide por su contenido ([ShpSelection]). Los límites de 100 MB entre los ficheros elegidos y de 500 MB descomprimidos se
 * miden con los bytes que se leen, no con los que dice el proveedor ni la cabecera del ZIP. De un ZIP solo se copian el `.shp` y sus
 * ficheros asociados; un nombre que sale de la carpeta no se escribe nunca. La carpeta se borra si se cancela, si falla y, con [close], al
 * terminar; las que dejó una importación interrumpida se borran al arrancar ([cleanUpStale]).
 */
class ShapefileReader(
    private val cacheDir: File,
    private val freeSpace: () -> Long = { cacheDir.usableSpace },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxPickedBytes: Long = ShpFileKind.MAX_PICKED_BYTES,
    private val maxUncompressedBytes: Long = ShpFileKind.MAX_UNCOMPRESSED_BYTES,
) {
    private class Failure(val reason: ShpOpenFailure) : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Copia [inputs] a una carpeta temporal y las reparte en capas. Cancelar la corrutina borra la carpeta. */
    suspend fun extract(inputs: List<ShpInput>): ShpExtractResult = withContext(Dispatchers.IO) {
        val directory = File(cacheDir, "$PREFIX${newId()}")
        try {
            val picks = inputs.map { pick(it) }
            val result = when (val check = ShpSelection.check(picks)) {
                is ShpPickResult.Rejected -> ShpExtractResult.Failed(check.reason.toFailure())
                is ShpPickResult.SingleZip -> extractZip(inputs[picks.indexOf(check.pick)], check.pick, directory)
                is ShpPickResult.Loose -> extractLoose(inputs, picks, directory)
            }
            if (result is ShpExtractResult.Failed) directory.deleteRecursively() // sin importar por dónde falló, no queda nada
            result
        } catch (e: Failure) {
            directory.deleteRecursively()
            ShpExtractResult.Failed(e.reason)
        } catch (e: CancellationException) {
            directory.deleteRecursively()
            throw e
        } catch (e: ZipException) {
            directory.deleteRecursively()
            ShpExtractResult.Failed(ShpOpenFailure.DAMAGED)
        } catch (e: EOFException) {
            directory.deleteRecursively()
            ShpExtractResult.Failed(ShpOpenFailure.DAMAGED)
        } catch (e: IOException) {
            directory.deleteRecursively()
            ShpExtractResult.Failed(if (noSpace(e)) ShpOpenFailure.NO_SPACE else ShpOpenFailure.READ_ERROR)
        } catch (e: SecurityException) {
            directory.deleteRecursively()
            ShpExtractResult.Failed(ShpOpenFailure.READ_ERROR)
        }
    }

    /** Borra las carpetas temporales que dejó una importación interrumpida (la app murió a mitad); devuelve cuántas. */
    fun cleanUpStale(): Int = cacheDir.listFiles { f -> f.name.startsWith(PREFIX) }.orEmpty().count { it.deleteRecursively() }

    private fun pick(input: ShpInput): ShpPick {
        val head = input.open().use { it.readNBytes(ShpFileKind.HEAD_BYTES) }
        val size = input.sizeBytes ?: head.size.toLong()
        return ShpPick(input.name, head, if (head.isEmpty()) 0 else size)
    }

    private fun ShpRejection.toFailure() = when (this) {
        ShpRejection.EMPTY -> ShpOpenFailure.EMPTY
        ShpRejection.MIXED_SELECTION -> ShpOpenFailure.MIXED_SELECTION
        ShpRejection.NOT_SHAPEFILE -> ShpOpenFailure.NOT_SHAPEFILE
        ShpRejection.NO_SHP -> ShpOpenFailure.NO_SHP
        ShpRejection.TOO_LARGE -> ShpOpenFailure.TOO_LARGE
    }

    private fun noSpace(e: IOException) =
        e.message?.contains("No space left", ignoreCase = true) == true || freeSpace() < SPACE_MARGIN_BYTES

    /** Cuenta los bytes que se leen de verdad contra un límite y corta al pasarse. */
    private class Budget(private val limit: Long) {
        private var used = 0L

        fun spend(bytes: Int) {
            used += bytes
            if (used > limit) throw Failure(ShpOpenFailure.TOO_LARGE)
        }
    }

    private suspend fun extractLoose(inputs: List<ShpInput>, picks: List<ShpPick>, directory: File): ShpExtractResult {
        val needed = picks.sumOf { it.sizeBytes }
        if (freeSpace() < needed + SPACE_MARGIN_BYTES) return ShpExtractResult.Failed(ShpOpenFailure.NO_SPACE)
        directory.mkdirs()
        val budget = Budget(maxPickedBytes)
        val entries = ArrayList<ShpEntry>()
        val names = HashSet<String>()
        for (input in inputs) {
            var name = File(input.name).name.ifEmpty { "fichero" }
            while (!names.add(name.lowercase())) name = "_$name"
            val target = File(directory, name)
            val head = input.open().use { stream -> copy(stream, target, budget) }
            entries += ShpEntry(name, head, target.length())
        }
        currentCoroutineContext().ensureActive()
        return group(entries, directory, drawingName = null)
    }

    private suspend fun extractZip(input: ShpInput, pick: ShpPick, directory: File): ShpExtractResult {
        if (pick.sizeBytes > maxPickedBytes) return ShpExtractResult.Failed(ShpOpenFailure.TOO_LARGE)
        if (freeSpace() < pick.sizeBytes + SPACE_MARGIN_BYTES) return ShpExtractResult.Failed(ShpOpenFailure.NO_SPACE)
        directory.mkdirs()
        // El ZIP se copia entero (hasta 100 MB leídos) y se abre con ZipFile, que lee su directorio central: un ZIP cortado se detecta
        // (RF-3) y un shp se lee de su entrada sin descomprimir las demás.
        val archive = File(directory, SOURCE_ZIP)
        input.open().use { copy(it, archive, Budget(maxPickedBytes)) }
        val entries = ArrayList<ShpEntry>()
        ZipFile(archive).use { zip ->
            val all = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            // Lo que dicen las cabeceras no basta (RF-6), pero lo que dicen de más ya no se descomprime.
            if (all.sumOf { maxOf(it.size, 0L) } > maxUncompressedBytes) throw Failure(ShpOpenFailure.TOO_LARGE)
            val budget = Budget(maxUncompressedBytes)
            for (zipEntry in all) {
                currentCoroutineContext().ensureActive()
                val path = zipEntry.name.replace('\\', '/')
                if (ShpSelection.isIgnorablePath(path)) continue
                if (ShpSelection.isUnsafePath(path) || !inside(directory, path)) {
                    entries += ShpEntry(zipEntry.name, ByteArray(0), maxOf(zipEntry.size, 0L))
                    continue
                }
                val head = zip.getInputStream(zipEntry).use { it.readNBytes(ShpFileKind.HEAD_BYTES) }
                val extension = path.substringAfterLast('.', "").lowercase()
                if (extension == "shp" ||
                    extension in ShpSelection.SIDE_EXTENSIONS ||
                    ShpFileKind.isShapeHeader(head, head.size.toLong())
                ) {
                    val target = File(directory, path)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(zipEntry).use { copy(it, target, budget) }
                    entries += ShpEntry(path, head, target.length())
                } else {
                    entries += ShpEntry(path, head, maxOf(zipEntry.size, head.size.toLong()))
                }
            }
        }
        archive.delete()
        currentCoroutineContext().ensureActive()
        if (entries.isEmpty()) return ShpExtractResult.Failed(ShpOpenFailure.EMPTY)
        return group(entries, directory, drawingName = input.name)
    }

    /** El nombre cae dentro de [directory]: la última guarda contra una ruta que se escape por cualquier vía. */
    private fun inside(directory: File, path: String): Boolean {
        val target = File(directory, path).canonicalFile
        return target.path.startsWith(directory.canonicalFile.path + File.separator)
    }

    private suspend fun copy(stream: InputStream, target: File, budget: Budget): ByteArray {
        val head = ByteArray(ShpFileKind.HEAD_BYTES)
        var headLength = 0
        target.outputStream().use { out ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                budget.spend(read)
                if (headLength < head.size) {
                    val take = minOf(read, head.size - headLength)
                    System.arraycopy(buffer, 0, head, headLength, take)
                    headLength += take
                }
                out.write(buffer, 0, read)
                currentCoroutineContext().ensureActive()
            }
        }
        return head.copyOf(headLength)
    }

    private fun group(entries: List<ShpEntry>, directory: File, drawingName: String?): ShpExtractResult {
        val grouping = ShpSelection.group(entries)
        if (grouping.layers.isEmpty()) {
            directory.deleteRecursively()
            return ShpExtractResult.Failed(ShpOpenFailure.NO_SHP)
        }
        fun file(entry: ShpEntry?) = entry?.let { File(directory, it.path) }
        val layers = grouping.layers.map {
            ShpLayerOnDisk(it.name, File(directory, it.shp.path), file(it.shx), file(it.dbf), file(it.prj), file(it.cpg))
        }
        val name = drawingName ?: grouping.layers.first().shp.name
        return ShpExtractResult.Extracted(ShpExtracted(directory, layers, grouping.orphans, grouping.dropped, name))
    }

    companion object {
        const val PREFIX = "shp-"
        private const val BUFFER_BYTES = 64 * 1024
        private const val SOURCE_ZIP = ".origen.zip"
        private const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
    }
}
