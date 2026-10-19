package es.topophone.core.formats.backup

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Copia completa de la app o paquete de un proyecto (S013). */
enum class BackupKind(val id: String) { BACKUP("backup"), PACKAGE("package") }

/**
 * Descripción de una copia o un paquete: qué es, de qué versión de los datos, cuándo se hizo, qué lleva y la
 * suma SHA-256 de cada fichero (S013/RF-6, RF-12, RF-13). [createdAt] se escribe en UTC al segundo (RNF-T10).
 */
data class BackupManifest(
    val kind: BackupKind,
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Instant,
    val projectCount: Int,
    val pointCount: Int,
    val projectId: String? = null,
    val projectName: String? = null,
    val formatVersion: Int = FORMAT_VERSION,
    val files: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT = "topophone"
        const val FORMAT_VERSION = 1
    }
}

/** Resultado de leer una copia o un paquete (S013/RF-12, RF-13). */
sealed interface BackupReadResult {
    /** Todo cuadra; [files] son los ficheros extraídos, por su nombre en el ZIP. */
    data class Valid(val manifest: BackupManifest, val files: Map<String, File>) : BackupReadResult

    /** No es un fichero de TopoPhone. */
    data object NotTopoPhone : BackupReadResult

    /** Es de TopoPhone, pero un paquete donde se esperaba una copia, o al revés. */
    data class WrongKind(val actual: BackupKind) : BackupReadResult

    /** Le falta algo, está cortado o una suma no coincide. */
    data object Damaged : BackupReadResult

    /** Lo hizo una versión de la app más nueva que esta. */
    data object Newer : BackupReadResult
}

/** El ZIP de la copia y del paquete: el manifiesto primero y después los ficheros (S013, plan "El fichero"). */
object BackupArchive {
    const val MANIFEST = "manifest.json"
    const val DATABASE = "data.db"
    const val SETTINGS = "settings.json"

    /** Ningún fichero de una copia pasa de 2 GiB: evita llenar el disco con un ZIP malicioso. */
    const val MAX_ENTRY_BYTES = 2L shl 30
    private const val MAX_MANIFEST_BYTES = 1L shl 20
    private const val BUFFER = 64 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /** Escribe [manifest] con las sumas de [files] y después cada fichero. No cierra [out]. */
    fun write(manifest: BackupManifest, files: Map<String, File>, out: OutputStream) {
        val sums = files.mapValues { (_, file) -> file.inputStream().use { sha256(it, OutputStream.nullOutputStream(), Long.MAX_VALUE)!! } }
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(encode(manifest.copy(files = sums)).encodeToByteArray())
        zip.closeEntry()
        files.forEach { (name, file) ->
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip, BUFFER) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    /**
     * Lee y comprueba una copia o un paquete, y extrae sus ficheros en [extractTo]. Solo se extraen los ficheros
     * conocidos; si no es válido, no deja nada en [extractTo]. [schemaVersion] es la versión de los datos de la app.
     */
    fun read(
        input: InputStream,
        expected: BackupKind,
        schemaVersion: Int,
        extractTo: File,
        maxEntryBytes: Long = MAX_ENTRY_BYTES,
    ): BackupReadResult {
        val result = try {
            readChecked(input, expected, schemaVersion, extractTo, maxEntryBytes)
        } catch (_: IOException) {
            BackupReadResult.Damaged
        }
        if (result !is BackupReadResult.Valid) extractTo.deleteRecursively()
        return result
    }

    private fun readChecked(
        input: InputStream,
        expected: BackupKind,
        schemaVersion: Int,
        extractTo: File,
        maxEntryBytes: Long,
    ): BackupReadResult {
        var manifestBytes: ByteArray? = null
        val extracted = mutableMapOf<String, Pair<File, String>>()
        ZipInputStream(input.buffered(BUFFER)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    MANIFEST -> {
                        if (manifestBytes != null) return BackupReadResult.Damaged
                        manifestBytes = readLimited(zip, MAX_MANIFEST_BYTES) ?: return BackupReadResult.Damaged
                    }
                    DATABASE, SETTINGS -> {
                        if (entry.name in extracted) return BackupReadResult.Damaged
                        extractTo.mkdirs()
                        val file = File(extractTo, entry.name)
                        val sum = file.outputStream().use { sha256(zip, it, maxEntryBytes) } ?: return BackupReadResult.Damaged
                        extracted[entry.name] = file to sum
                    }
                    else -> Unit // S013/RF-12: lo que no es de la copia no se extrae.
                }
            }
        }
        val bytes = manifestBytes ?: return BackupReadResult.NotTopoPhone
        val manifest = when (val decoded = decode(bytes.decodeToString())) {
            is Decoded.Ok -> decoded.manifest
            Decoded.Foreign -> return BackupReadResult.NotTopoPhone
            Decoded.Newer -> return BackupReadResult.Newer
            Decoded.Invalid -> return BackupReadResult.Damaged
        }
        if (manifest.kind != expected) return BackupReadResult.WrongKind(manifest.kind)
        if (manifest.schemaVersion > schemaVersion) return BackupReadResult.Newer // S013/RF-13
        val required = if (manifest.kind == BackupKind.BACKUP) setOf(DATABASE, SETTINGS) else setOf(DATABASE)
        if (!manifest.files.keys.containsAll(required)) return BackupReadResult.Damaged
        val files = manifest.files.mapValues { (name, sum) ->
            val (file, actual) = extracted[name] ?: return BackupReadResult.Damaged
            if (actual != sum) return BackupReadResult.Damaged
            file
        }
        return BackupReadResult.Valid(manifest, files)
    }

    private fun encode(manifest: BackupManifest): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", BackupManifest.FORMAT)
            put("formatVersion", manifest.formatVersion)
            put("kind", manifest.kind.id)
            put("schemaVersion", manifest.schemaVersion)
            put("appVersion", manifest.appVersion)
            put("createdAt", manifest.createdAt.truncatedTo(ChronoUnit.SECONDS).toString())
            put("projectCount", manifest.projectCount)
            put("pointCount", manifest.pointCount)
            put("projectId", manifest.projectId?.let(::JsonPrimitive) ?: JsonNull)
            put("projectName", manifest.projectName?.let(::JsonPrimitive) ?: JsonNull)
            put("files", JsonObject(manifest.files.mapValues { JsonPrimitive(it.value) }))
        },
    )

    private sealed interface Decoded {
        data class Ok(val manifest: BackupManifest) : Decoded
        data object Foreign : Decoded
        data object Newer : Decoded
        data object Invalid : Decoded
    }

    private fun decode(text: String): Decoded = try {
        val root = json.parseToJsonElement(text).jsonObject
        fun string(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun int(key: String) = (root[key] as? JsonPrimitive)?.intOrNull
        when {
            string("format") != BackupManifest.FORMAT -> Decoded.Foreign
            (int("formatVersion") ?: 0) > BackupManifest.FORMAT_VERSION -> Decoded.Newer // S013/RF-13
            else -> {
                val kind = BackupKind.entries.firstOrNull { it.id == string("kind") }
                val files = (root["files"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }
                val manifest = BackupManifest(
                    kind = kind ?: return Decoded.Invalid,
                    schemaVersion = int("schemaVersion") ?: return Decoded.Invalid,
                    appVersion = string("appVersion") ?: return Decoded.Invalid,
                    createdAt = Instant.parse(string("createdAt") ?: return Decoded.Invalid),
                    projectCount = int("projectCount") ?: return Decoded.Invalid,
                    pointCount = int("pointCount") ?: return Decoded.Invalid,
                    projectId = string("projectId"),
                    projectName = string("projectName"),
                    formatVersion = int("formatVersion") ?: return Decoded.Invalid,
                    files = files ?: return Decoded.Invalid,
                )
                if (manifest.kind == BackupKind.PACKAGE && manifest.projectId == null) Decoded.Invalid else Decoded.Ok(manifest)
            }
        }
    } catch (_: SerializationException) {
        Decoded.Invalid
    } catch (_: IllegalArgumentException) {
        Decoded.Invalid
    } catch (_: DateTimeParseException) {
        Decoded.Invalid
    }

    private fun readLimited(input: InputStream, max: Long): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        return if (sha256(input, buffer, max) == null) null else buffer.toByteArray()
    }

    /** Copia [input] a [out] calculando su SHA-256 en hexadecimal; null si pasa de [max] bytes. */
    private fun sha256(input: InputStream, out: OutputStream, max: Long): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) return null
            digest.update(buffer, 0, read)
            out.write(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
