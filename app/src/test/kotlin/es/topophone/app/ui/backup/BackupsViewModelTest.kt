package es.topophone.app.ui.backup

import es.topophone.app.backup.AutoBackupScheduler
import es.topophone.app.backup.FakeFolder
import es.topophone.app.backup.FakePreferences
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupManifest
import es.topophone.core.formats.backup.BackupSettings
import es.topophone.data.backup.AutoBackupState
import es.topophone.data.backup.BackupRead
import es.topophone.data.backup.Backups
import es.topophone.data.backup.ImportChoice
import es.topophone.data.backup.ImportResult
import es.topophone.data.backup.RestoreResult
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pantalla "Copias de seguridad": crear, restaurar, deshacer y la carpeta de las automáticas (S013). */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupsViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val createdAt = Instant.parse("2026-10-19T06:02:31Z")

    private inner class FakeBackups : Backups {
        override val undoAvailable = MutableStateFlow(false)
        var readResult: BackupRead = valid()
        var restoreResult: RestoreResult = RestoreResult.Restored(2, 4)
        var undoResult: RestoreResult = RestoreResult.Restored(3, 4)
        var writeOk = true
        val restored = mutableListOf<BackupRead.Valid>()
        var undone = 0
        var readKind: BackupKind? = null

        override suspend fun writeBackup(out: OutputStream): Boolean {
            if (writeOk) out.write("copia".toByteArray())
            return writeOk
        }

        override suspend fun read(input: () -> InputStream, kind: BackupKind): BackupRead {
            readKind = kind
            return readResult
        }

        override suspend fun restore(valid: BackupRead.Valid, keepCopy: suspend (File) -> Unit): RestoreResult {
            restored += valid
            keepCopy(folder.newFile().apply { writeText("antes") })
            return restoreResult
        }

        override suspend fun undoRestore(): RestoreResult {
            undone++
            return undoResult
        }

        override suspend fun writePackage(projectId: String, out: OutputStream): Boolean = true

        override suspend fun importPackage(valid: BackupRead.Valid, choice: ImportChoice?, importedLabel: String): ImportResult =
            ImportResult.Failed
    }

    private fun valid() = BackupRead.Valid(
        BackupManifest(BackupKind.BACKUP, 6, "0.13.0", createdAt, projectCount = 2, pointCount = 4),
        File("data.db"),
        BackupSettings(emptyMap(), ""),
    )

    private val backups = FakeBackups()
    private val preferences = FakePreferences(AutoBackupState())
    private val backupFolder = FakeFolder()
    private val measuring = MeasurementInProgress()
    private lateinit var vm: BackupsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scheduler = AutoBackupScheduler(
            createBackup = { it.writeText("copia") },
            folder = backupFolder,
            preferences = preferences,
            workDir = folder.newFolder("auto"),
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { createdAt.toEpochMilli() },
            zone = { ZoneId.of("UTC") },
        )
        vm = BackupsViewModel(backups, preferences, scheduler, measuring)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun state() = vm.state.value

    @Test
    fun `S013 RF-1 la copia se escribe y se dice que se ha guardado`() {
        val out = ByteArrayOutputStream()

        runBlocking { vm.writeBackup(out) } shouldBe true
        vm.backupSaved(true)

        out.toString() shouldBe "copia"
        state().message shouldBe BackupMessage.SAVED
    }

    @Test
    fun `S013 RF-5 si no se ha podido guardar se dice`() {
        backups.writeOk = false

        runBlocking { vm.writeBackup(ByteArrayOutputStream()) } shouldBe false
        vm.backupSaved(false)

        state().message shouldBe BackupMessage.NOT_SAVED
    }

    @Test
    fun `S013 RF-6 al elegir una copia se ve su fecha y lo que tiene`() {
        vm.open { "zip".byteInputStream() }

        backups.readKind shouldBe BackupKind.BACKUP
        state().step shouldBe BackupStep.Confirm(createdAt, projects = 2, points = 4)
        backups.restored shouldBe emptyList()
    }

    @Test
    fun `S013 RF-12 y RF-13 una copia que no vale se dice por que`() {
        val cases = mapOf(
            BackupRead.NotTopoPhone to BackupMessage.NOT_TOPOPHONE,
            BackupRead.WrongKind(BackupKind.PACKAGE) to BackupMessage.IS_PACKAGE,
            BackupRead.Damaged to BackupMessage.DAMAGED,
            BackupRead.Newer to BackupMessage.NEWER,
        )
        for ((read, message) in cases) {
            backups.readResult = read
            vm.open { "x".byteInputStream() }

            state().step shouldBe BackupStep.Idle
            state().message shouldBe message
        }
    }

    @Test
    fun `S013 RF-7 al confirmar se restaura y la copia previa va a la carpeta`() {
        runBlocking { preferences.setFolder("content://tree/copias") }
        vm.open { "zip".byteInputStream() }

        vm.confirmRestore()

        backups.restored.size shouldBe 1
        backupFolder.files shouldBe mapOf("TopoPhone_antes-de-restaurar_20261019-0602.zip" to "antes")
        state().message shouldBe BackupMessage.RESTORED
        state().step shouldBe BackupStep.Idle
    }

    @Test
    fun `S013 RF-6 cancelar no restaura nada`() {
        vm.open { "zip".byteInputStream() }

        vm.cancelRestore()

        state().step shouldBe BackupStep.Idle
        backups.restored shouldBe emptyList()
    }

    @Test
    fun `S013 RF-10 si no se ha podido restaurar se dice`() {
        backups.restoreResult = RestoreResult.Failed
        vm.open { "zip".byteInputStream() }

        vm.confirmRestore()

        state().message shouldBe BackupMessage.NOT_RESTORED
    }

    @Test
    fun `S013 RF-16 con una medida en curso no se restaura`() {
        vm.open { "zip".byteInputStream() }
        measuring.set(true)

        vm.confirmRestore()

        backups.restored shouldBe emptyList()
        state().message shouldBe BackupMessage.MEASURING
    }

    @Test
    fun `S013 RF-11 deshacer se ofrece solo tras restaurar y se usa`() {
        state().undoAvailable shouldBe false
        backups.undoAvailable.value = true
        state().undoAvailable shouldBe true

        vm.undo()

        backups.undone shouldBe 1
        state().message shouldBe BackupMessage.UNDONE
    }

    @Test
    fun `S013 RF-16 con una medida en curso no se deshace`() {
        backups.undoAvailable.value = true
        measuring.set(true)

        vm.undo()

        backups.undone shouldBe 0
        state().message shouldBe BackupMessage.MEASURING
    }

    @Test
    fun `S013 RF-17 elegir la carpeta activa las copias y hace la primera`() {
        vm.folderChosen("content://tree/copias")

        state().folderUri shouldBe "content://tree/copias"
        state().lastAutoBackupMs shouldBe createdAt.toEpochMilli()
        backupFolder.files.keys shouldBe setOf("TopoPhone_auto_20261019-0602.zip")
    }

    @Test
    fun `S013 RF-17 una carpeta sin permiso se dice`() {
        backupFolder.failPersist = true

        vm.folderChosen("content://tree/copias")

        state().folderUri shouldBe null
        state().message shouldBe BackupMessage.FOLDER_FAILED
    }

    @Test
    fun `S013 RF-17 y RF-21 quitar la carpeta y ver el fallo`() {
        runBlocking { preferences.setFolder("content://tree/copias") }
        runBlocking { preferences.recordFailure() }
        state().autoFailing shouldBe true

        vm.removeFolder()

        state().folderUri shouldBe null
        state().autoFailing shouldBe false
    }

    @Test
    fun `S013 RF-20 se ve la fecha de la ultima copia automatica`() {
        runBlocking { preferences.recordSuccess(1_000) }

        runBlocking { vm.state.first { it.lastAutoBackupMs == 1_000L } }.lastAutoBackupMs shouldBe 1_000
    }

    @Test
    fun `S014 RF-5 lo que la pantalla de copias no deja hacer queda en el log`() {
        val log = RecordingLog()
        val scheduler =
            AutoBackupScheduler({
                it.writeText("copia")
            }, backupFolder, preferences, folder.newFolder("auto2"), CoroutineScope(Dispatchers.Unconfined))
        val logged = BackupsViewModel(backups, preferences, scheduler, measuring, log)
        backupFolder.failPersist = true
        logged.folderChosen("content://tree/copias")
        logged.open { "zip".byteInputStream() }
        measuring.set(true)
        logged.confirmRestore()

        log.messages shouldBe listOf(
            "No se puede usar la carpeta elegida para las copias automáticas",
            "Restauración no hecha: hay una medida en curso",
        )
    }
}
