package es.topophone.app.backup

import es.topophone.data.backup.AutoBackupPreferences
import es.topophone.data.backup.AutoBackupState
import es.topophone.data.backup.BackupFolder
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Copias automáticas con tiempo virtual: cuándo, dónde, cuántas y qué pasa si fallan (S013/RF-7, RF-18, RF-19, RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoBackupSchedulerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val hour = 60 * 60 * 1000L
    private val start = Instant.parse("2026-10-19T06:00:00Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")

    private class FakePreferences(initial: AutoBackupState) : AutoBackupPreferences {
        val flow = MutableStateFlow(initial)
        override val state: Flow<AutoBackupState> = flow

        override suspend fun setFolder(uri: String?) = flow.update { it.copy(folderUri = uri, failing = false) }

        override suspend fun markPending() = flow.update { it.copy(pendingChanges = true) }

        override suspend fun clearPending() = flow.update { it.copy(pendingChanges = false) }

        override suspend fun recordSuccess(atMs: Long) = flow.update { it.copy(lastSuccessMs = atMs, failing = false) }

        override suspend fun recordFailure() = flow.update { it.copy(failing = true, pendingChanges = true) }

        override suspend fun dismissNotice(atMs: Long) = flow.update { it.copy(noticeDismissedMs = atMs) }
    }

    private class FakeFolder : BackupFolder {
        val files = linkedMapOf<String, String>()
        var failWrites = false
        val persisted = mutableListOf<String>()

        override suspend fun write(treeUri: String, name: String, from: File): Boolean {
            if (failWrites) return false
            files[name] = from.readText()
            return true
        }

        override suspend fun list(treeUri: String): List<String> = files.keys.toList()

        override suspend fun delete(treeUri: String, name: String): Boolean = files.remove(name) != null

        override fun persist(treeUri: String) {
            persisted += treeUri
        }

        override fun release(treeUri: String) {
            persisted -= treeUri
        }
    }

    private val backupFolder = FakeFolder()

    private fun TestScope.scheduler(prefs: FakePreferences) = AutoBackupScheduler(
        createBackup = { it.writeText("copia") },
        folder = backupFolder,
        preferences = prefs,
        workDir = folder.newFolder(),
        scope = backgroundScope,
        clock = { start + testScheduler.currentTime },
        zone = { utc },
    )

    private val withFolder = AutoBackupState(folderUri = "content://tree/copias")

    @Test
    fun `S013 RF-18 con cambios y sin copia previa se hace en el momento`() = runTest {
        val prefs = FakePreferences(withFolder.copy(pendingChanges = true))

        scheduler(prefs).start()
        runCurrent()

        backupFolder.files.keys shouldBe setOf("TopoPhone_auto_20261019-0600.zip")
        prefs.flow.value shouldBe withFolder.copy(lastSuccessMs = start)
    }

    @Test
    fun `S013 RF-18 como mucho una copia cada hora`() = runTest {
        val prefs = FakePreferences(withFolder.copy(pendingChanges = true))
        scheduler(prefs).start()
        runCurrent()

        prefs.markPending()
        advanceTimeBy(30 * 60 * 1000L)
        backupFolder.files.size shouldBe 1

        advanceTimeBy(30 * 60 * 1000L + 5 * 60 * 1000L)
        backupFolder.files.keys shouldBe setOf("TopoPhone_auto_20261019-0600.zip", "TopoPhone_auto_20261019-0700.zip")
    }

    @Test
    fun `S013 RF-18 sin cambios no se hace ninguna`() = runTest {
        val prefs = FakePreferences(withFolder.copy(lastSuccessMs = start - 5 * hour))

        scheduler(prefs).start()
        advanceTimeBy(3 * hour)

        backupFolder.files.size shouldBe 0
    }

    @Test
    fun `S013 RF-18 al pasar a segundo plano se mira en el momento`() = runTest {
        val prefs = FakePreferences(withFolder.copy(lastSuccessMs = start - 2 * hour))
        val scheduler = scheduler(prefs)
        scheduler.start()
        runCurrent()
        advanceTimeBy(60 * 1000L)

        prefs.markPending()
        scheduler.onBackground()
        runCurrent()

        backupFolder.files.keys shouldBe setOf("TopoPhone_auto_20261019-0601.zip")
    }

    @Test
    fun `S013 RF-18 al elegir la carpeta se hace la primera copia`() = runTest {
        val prefs = FakePreferences(AutoBackupState())

        scheduler(prefs).folderChosen("content://tree/copias") shouldBe true

        backupFolder.persisted shouldBe listOf("content://tree/copias")
        prefs.flow.value.folderUri shouldBe "content://tree/copias"
        backupFolder.files.keys shouldBe setOf("TopoPhone_auto_20261019-0600.zip")
    }

    @Test
    fun `S013 RF-17 quitar la carpeta desactiva las copias`() = runTest {
        val prefs = FakePreferences(withFolder.copy(pendingChanges = true))
        backupFolder.persisted += "content://tree/copias"

        scheduler(prefs).folderRemoved()
        advanceTimeBy(2 * hour)

        prefs.flow.value.folderUri shouldBe null
        backupFolder.persisted shouldBe emptyList()
        backupFolder.files.size shouldBe 0
    }

    @Test
    fun `S013 RF-19 se conservan las 24 mas recientes sin tocar lo demas`() = runTest {
        (1..18).forEach { backupFolder.files["TopoPhone_auto_202610%02d-0500.zip".format(it)] = "vieja" }
        (19..24).forEach { backupFolder.files["TopoPhone_auto_202609%02d-0500.zip".format(it)] = "más vieja" }
        backupFolder.files["notas.txt"] = "mías"
        backupFolder.files["TopoPhone_antes-de-restaurar_20261001-0800.zip"] = "previa"

        scheduler(FakePreferences(withFolder.copy(pendingChanges = true))).backupNow() shouldBe true

        backupFolder.files.keys.filter { it.startsWith("TopoPhone_auto_") }.size shouldBe 24
        // 24 de antes y la nueva: sobra solo la más antigua.
        backupFolder.files.containsKey("TopoPhone_auto_20260919-0500.zip") shouldBe false
        backupFolder.files.containsKey("TopoPhone_auto_20260920-0500.zip") shouldBe true
        backupFolder.files.keys.containsAll(listOf("notas.txt", "TopoPhone_antes-de-restaurar_20261001-0800.zip")) shouldBe true
    }

    @Test
    fun `S013 RF-21 un fallo se avisa y se reintenta en la siguiente comprobacion`() = runTest {
        val prefs = FakePreferences(withFolder.copy(pendingChanges = true))
        backupFolder.failWrites = true
        scheduler(prefs).start()
        runCurrent()

        prefs.flow.value.failing shouldBe true
        prefs.flow.value.pendingChanges shouldBe true

        backupFolder.failWrites = false
        advanceTimeBy(5 * 60 * 1000L + 1)

        prefs.flow.value.failing shouldBe false
        backupFolder.files.keys shouldBe setOf("TopoPhone_auto_20261019-0605.zip")
    }

    @Test
    fun `S013 RF-7 la copia de antes de restaurar va a la carpeta con su nombre`() = runTest {
        val prefs = FakePreferences(withFolder)
        val before = folder.newFile("before-restore.zip").apply { writeText("antes") }

        scheduler(prefs).keepBeforeRestore(before)

        backupFolder.files shouldBe mapOf("TopoPhone_antes-de-restaurar_20261019-0600.zip" to "antes")
    }

    @Test
    fun `S013 RF-7 sin carpeta la copia de antes de restaurar solo queda en el movil`() = runTest {
        scheduler(FakePreferences(AutoBackupState())).keepBeforeRestore(folder.newFile("b.zip"))

        backupFolder.files.size shouldBe 0
    }

    @Test
    fun `S013 RF-21 si la copia de antes de restaurar falla se avisa`() = runTest {
        val prefs = FakePreferences(withFolder)
        backupFolder.failWrites = true

        scheduler(prefs).keepBeforeRestore(folder.newFile("b.zip"))

        prefs.flow.value.failing shouldBe true
    }
}
