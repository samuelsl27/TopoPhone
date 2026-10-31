package es.topophone.app.backup

import es.topophone.core.formats.backup.BackupArchive
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupManifest
import es.topophone.data.backup.AutoBackupState
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Las fotos de las copias automáticas: una sola vez cada una, en su subcarpeta, con retención (S017b/RF-30, RF-31). */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoBackupPhotosTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val hour = 60 * 60 * 1000L
    private val start = Instant.parse("2026-10-19T06:00:00Z").toEpochMilli()
    private val withFolder = AutoBackupState(folderUri = "content://tree/copias")
    private val backupFolder = FakeFolder()

    /** Las fotos que hay ahora en el móvil, por nombre de fichero. */
    private val mine = linkedMapOf<String, File>()

    private fun photo(id: String, text: String = id) {
        mine["$id.jpg"] = File(folder.root, "$id.jpg").apply { writeText(text) }
    }

    private val photos = object : AutoBackupPhotos {
        override suspend fun current(): Map<String, File> = mine.toMap()

        override suspend fun createBackup(target: File, photos: Map<String, File>) {
            target.writeText(zip(photos.keys), Charsets.ISO_8859_1)
        }
    }

    /** Un ZIP real de copia automática que nombra [names] como fotos externas. */
    private fun zip(names: Collection<String>): String {
        val db = File(folder.root, "data.db").apply { writeText("db") }
        val settings = File(folder.root, "settings.json").apply { writeText("{}") }
        val manifest = BackupManifest(
            kind = BackupKind.BACKUP,
            schemaVersion = 24,
            appVersion = "0.17",
            createdAt = Instant.ofEpochMilli(start),
            projectCount = 1,
            pointCount = 1,
            externalPhotos = names.associateWith { "00".repeat(32) },
        )
        return ByteArrayOutputStream().also {
            BackupArchive.write(manifest, mapOf(BackupArchive.DATABASE to db, BackupArchive.SETTINGS to settings), it)
        }.toByteArray().toString(Charsets.ISO_8859_1)
    }

    private fun TestScope.scheduler(prefs: FakePreferences) = AutoBackupScheduler(
        createBackup = { it.writeText("sin fotos") },
        folder = backupFolder,
        preferences = prefs,
        workDir = folder.newFolder(),
        scope = backgroundScope,
        clock = { start + testScheduler.currentTime },
        zone = { ZoneId.of("UTC") },
        photos = photos,
    )

    @Test
    fun `S017b RF-30 la primera copia sube las fotos a la subcarpeta y el ZIP las nombra sin llevarlas`() = runTest {
        photo("aaa-1", "uno")
        photo("bbb-2", "dos")
        val prefs = FakePreferences(withFolder.copy(pendingChanges = true))

        scheduler(prefs).start()
        runCurrent()

        backupFolder.photos shouldBe mapOf("aaa-1.jpg" to "uno", "bbb-2.jpg" to "dos")
        val zipName = backupFolder.files.keys.single()
        val manifest = BackupArchive.readManifest(backupFolder.openFile("", zipName)!!)!!
        manifest.externalPhotos.keys shouldBe setOf("aaa-1.jpg", "bbb-2.jpg")
        backupFolder.files.getValue(zipName).contains("uno") shouldBe false
    }

    @Test
    fun `S017b RF-30 la segunda copia no repite las fotos y sube solo las nuevas`() = runTest {
        photo("aaa-1")
        val prefs = FakePreferences(withFolder.copy(pendingChanges = true))
        val scheduler = scheduler(prefs)
        scheduler.start()
        runCurrent()
        backupFolder.photoWrites.toList() shouldBe listOf("aaa-1.jpg")

        photo("bbb-2")
        prefs.markPending()
        advanceTimeBy(hour + 1)

        backupFolder.photoWrites.toList() shouldBe listOf("aaa-1.jpg", "bbb-2.jpg")
        backupFolder.files.size shouldBe 2
    }

    @Test
    fun `S017b RF-31 una foto que ninguna copia guardada nombra se borra y las demas se quedan`() = runTest {
        photo("nueva")
        backupFolder.photos["vieja.jpg"] = "vieja" // solo la nombra la copia mas antigua, que rota
        backupFolder.photos["comun.jpg"] = "comun" // la nombran todas
        backupFolder.files["TopoPhone_auto_20261001-0500.zip"] = zip(listOf("vieja.jpg", "comun.jpg"))
        (2..24).forEach { backupFolder.files["TopoPhone_auto_202610%02d-0500.zip".format(it)] = zip(listOf("comun.jpg")) }
        backupFolder.files["notas.txt"] = "mias"
        backupFolder.files["TopoPhone_antes-de-restaurar_20261001-0800.zip"] = "previa"
        val prefs = FakePreferences(withFolder.copy(pendingChanges = true))

        scheduler(prefs).start()
        runCurrent()

        backupFolder.photos.keys shouldBe setOf("comun.jpg", "nueva.jpg")
        backupFolder.files.keys.contains("TopoPhone_auto_20261001-0500.zip") shouldBe false
        backupFolder.files.keys.containsAll(listOf("notas.txt", "TopoPhone_antes-de-restaurar_20261001-0800.zip")) shouldBe true
    }

    @Test
    fun `S017b RF-31 si no se puede abrir una copia no se borra ninguna foto`() = runTest {
        backupFolder.photos["vieja.jpg"] = "vieja"
        backupFolder.files["TopoPhone_auto_20261001-0500.zip"] = zip(listOf("vieja.jpg"))
        backupFolder.unreadable += "TopoPhone_auto_20261001-0500.zip"
        val prefs = FakePreferences(withFolder.copy(pendingChanges = true))

        scheduler(prefs).start()
        runCurrent()

        backupFolder.photos.keys shouldBe setOf("vieja.jpg")
    }

    @Test
    fun `S017b RF-30 si falla subir una foto la copia falla y no se escribe ningun ZIP`() = runTest {
        photo("aaa-1")
        backupFolder.failPhotoWrites = true
        backupFolder.files["notas.txt"] = "mias"
        val prefs = FakePreferences(withFolder.copy(pendingChanges = true))

        scheduler(prefs).start()
        runCurrent()

        prefs.flow.value.failing shouldBe true
        prefs.flow.value.pendingChanges shouldBe true
        backupFolder.files.keys shouldBe setOf("notas.txt")
        backupFolder.photos shouldBe emptyMap()
    }

    @Test
    fun `S017b RF-30 si no se puede leer la subcarpeta la copia falla`() = runTest {
        photo("aaa-1")
        backupFolder.photoFolderUnreadable = true
        val prefs = FakePreferences(withFolder.copy(pendingChanges = true))

        scheduler(prefs).start()
        runCurrent()

        prefs.flow.value.failing shouldBe true
        backupFolder.files shouldBe emptyMap()
    }
}
