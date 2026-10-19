package es.topophone.data.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.data.backup.BackupFixture
import es.topophone.data.backup.BackupRead
import es.topophone.data.backup.BackupRepository
import es.topophone.data.backup.ImportChoice
import es.topophone.data.backup.RestoreHooks
import es.topophone.data.backup.TestSettings
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Copias, restauraciones, deshacer e importaciones quedan en el log con su resultado (S014/RF-4, RF-5). */
@RunWith(AndroidJUnit4::class)
class BackupLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "blog-${System.nanoTime()}.db"
    private val log = RecordingLog()
    private lateinit var db: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository = BackupRepository(
            context,
            db,
            settings.archive,
            "0.14.0",
            clock = { Instant.parse("2026-10-19T09:15:52Z").toEpochMilli() },
            workDir = folder.newFolder("work"),
            undoDir = folder.newFolder("undo"),
            log = log,
        )
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    private fun backup(): ByteArray = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } }.toByteArray()

    private fun read(bytes: ByteArray, kind: BackupKind) = runBlocking { repository.read({ bytes.inputStream() }, kind) }

    @Test
    fun `S014 RF-4 la copia creada se anota con lo que lleva`() {
        backup()

        log.entries.single() shouldBe Triple(LogLevel.INFO, LogCategory.BACKUPS, "Copia de seguridad creada: 2 proyectos y 4 puntos")
    }

    @Test
    fun `S014 RF-4 restaurar y deshacer se anotan con su resultado`() {
        val valid = read(backup(), BackupKind.BACKUP).shouldBeInstanceOf<BackupRead.Valid>()
        log.entries.clear()

        runBlocking { repository.restore(valid) }
        runBlocking { repository.undoRestore() }

        log.messages shouldBe listOf(
            "Restaurada la copia del 2026-10-19T09:15:52Z: 2 proyectos y 4 puntos",
            "Deshecha la restauración",
        )
    }

    @Test
    fun `S014 RF-4 una restauracion fallida se anota como error`() {
        val valid = read(backup(), BackupKind.BACKUP).shouldBeInstanceOf<BackupRead.Valid>()
        repository.hooks = RestoreHooks(beforeCommit = { error("sin espacio") })

        runBlocking { repository.restore(valid) }

        val failed = log.entries.last()
        failed.first shouldBe LogLevel.ERROR
        failed.third shouldBe "No se pudo restaurar la copia del 2026-10-19T09:15:52Z; los datos siguen como estaban"
    }

    @Test
    fun `S014 RF-4 exportar e importar un paquete se anota`() {
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(fixture.streetId, it) } }.toByteArray()
        val valid = read(pkg, BackupKind.PACKAGE).shouldBeInstanceOf<BackupRead.Valid>()

        runBlocking { repository.importPackage(valid, ImportChoice.AS_NEW, "importado") }
        runBlocking { repository.importPackage(valid, ImportChoice.REPLACE, "importado") }

        log.messages shouldContain "Paquete exportado: «Calle Mayor 12»"
        log.messages shouldContain "Paquete importado como nuevo: «Calle Mayor 12 (importado)»"
        log.messages shouldContain "Paquete importado sustituyendo al del móvil: «Calle Mayor 12»"
    }

    @Test
    fun `S014 RF-5 un fichero rechazado se anota con el motivo`() {
        read("Name,Easting\r\n".toByteArray(), BackupKind.BACKUP)
        read(backup(), BackupKind.PACKAGE)

        log.entries.filter { it.first == LogLevel.WARNING }.map { it.third } shouldBe listOf(
            "Fichero rechazado como copia de seguridad: no es de TopoPhone",
            "Fichero rechazado como paquete: es una copia de seguridad",
        )
        log.messages.first() shouldContain "rechazado"
    }
}
