package es.topophone.app.log

import es.topophone.core.formats.backup.BackupSettings
import es.topophone.core.formats.backup.SettingValue
import es.topophone.core.model.ntrip.NtripProfile
import es.topophone.receiver.session.RecordedSession
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Datos de la app y del móvil para el informe (S014/RF-13). */
data class DeviceInfo(
    val appVersion: String,
    val gitCommit: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val freeBytes: Long,
    val permissions: Map<String, Boolean>,
    val batteryExempt: Boolean,
    val lastReceiver: String?,
)

/** El informe de sistema (S014/RF-10 a RF-17). */
fun interface SystemReportWriter {
    /** Escribe el ZIP del informe en [out]; false si no se ha podido. No cierra [out]. */
    suspend fun write(out: OutputStream): Boolean
}

/**
 * El ZIP del informe (S014; plan, "El informe" y A7): `informe.txt` con los datos de la app y del móvil, los ajustes, los
 * perfiles NTRIP sin usuario ni contraseña (S014/RF-15) y las sesiones incluidas; `log/` con todo el log, escrito antes en
 * disco; `sesiones/<id>/` con las sesiones elegidas (S014/RF-12).
 */
class ZipSystemReportWriter(
    private val device: suspend () -> DeviceInfo,
    private val settings: suspend () -> BackupSettings,
    private val profiles: suspend () -> List<NtripProfile>,
    private val sessions: () -> List<RecordedSession>,
    private val activeSessionId: () -> String?,
    private val logFiles: () -> List<File>,
    private val flushLog: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : SystemReportWriter {

    override suspend fun write(out: OutputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            flushLog()
            val selected = SessionSelection.select(sessions(), activeSessionId())
            val zip = ZipOutputStream(out)
            val text = SystemReportText.build(Instant.ofEpochMilli(clock()), zone(), device(), settings(), profiles(), selected)
            zip.putNextEntry(ZipEntry("informe.txt"))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            logFiles().forEach { zip.add("log/${it.name}", it) }
            selected.forEach { session -> session.files.forEach { zip.add("sesiones/${session.id}/${it.name}", it) } }
            zip.finish()
            zip.flush()
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Copia lo que mide el fichero ahora: una parte que aún se está escribiendo no crece dentro del informe. */
    private fun ZipOutputStream.add(name: String, file: File) {
        val length = file.length()
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyLimited(this, length) }
        closeEntry()
    }

    private fun InputStream.copyLimited(out: OutputStream, limit: Long) {
        val buffer = ByteArray(BUFFER)
        var left = limit
        while (left > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            left -= read
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/** El texto de `informe.txt` (S014/RF-12 a RF-15; plan, "El informe"). */
object SystemReportText {
    private const val MB = 1024L * 1024

    fun build(
        createdAt: Instant,
        zone: ZoneId,
        device: DeviceInfo,
        settings: BackupSettings,
        profiles: List<NtripProfile>,
        sessions: List<SelectedSession>,
    ): String = buildString {
        appendLine("Informe de sistema de TopoPhone")
        appendLine("Creado: ${createdAt.truncatedTo(ChronoUnit.SECONDS)}")
        appendLine("Zona horaria del móvil: ${zone.id}")
        appendLine("App: ${device.appVersion} (commit ${device.gitCommit})")
        appendLine("Móvil: ${device.manufacturer} ${device.model}, Android ${device.androidRelease} (API ${device.sdkInt})")
        appendLine("Espacio libre: ${device.freeBytes / MB} MB")
        appendLine("Permisos: ${device.permissions.entries.joinToString { (name, granted) -> "$name=${yesNo(granted)}" }}")
        appendLine("Sin optimización de batería: ${yesNo(device.batteryExempt)}")
        appendLine("Último receptor: ${device.lastReceiver ?: "ninguno"}")
        appendLine()
        appendLine("Ajustes")
        appendLine("  Idioma de la app: ${settings.appLanguage.ifEmpty { "el del sistema" }}")
        settings.stores.toSortedMap().forEach { (store, values) ->
            values.toSortedMap().forEach { (key, value) -> appendLine("  $store.$key = ${value.text()}") }
        }
        appendLine()
        appendLine("Perfiles NTRIP")
        if (profiles.isEmpty()) appendLine("  ninguno")
        // S014/RF-15: dónde conecta cada perfil; nunca el usuario ni la contraseña.
        profiles.forEach { profile ->
            val draft = profile.draft
            appendLine("  ${draft.name}: ${draft.host}:${draft.port}, TLS ${yesNo(draft.useTls)}, punto de montaje ${draft.mountpoint}")
        }
        appendLine()
        appendLine("Sesiones incluidas")
        if (sessions.isEmpty()) appendLine("  ninguna")
        sessions.forEach { session ->
            val size = session.files.sumOf { it.length() }.toDouble() / MB
            appendLine(
                "  ${session.id} (${"%.1f".format(
                    java.util.Locale.ROOT,
                    size,
                )} MB${if (session.partial) ", solo la parte más reciente" else ""})",
            )
        }
    }

    private fun yesNo(value: Boolean) = if (value) "sí" else "no"

    private fun SettingValue.text(): String = when (this) {
        is SettingValue.Text -> value
        is SettingValue.Int32 -> value.toString()
        is SettingValue.Int64 -> value.toString()
        is SettingValue.Float32 -> value.toString()
        is SettingValue.Float64 -> value.toString()
        is SettingValue.Flag -> value.toString()
        is SettingValue.Texts -> value.sorted().joinToString(", ")
    }
}
