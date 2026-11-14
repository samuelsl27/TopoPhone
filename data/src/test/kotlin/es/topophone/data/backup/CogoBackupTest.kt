package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupArchive
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupManifest
import es.topophone.core.model.cogo.DraftPoint
import es.topophone.core.model.cogo.LegKind
import es.topophone.core.model.cogo.TraverseDraft
import es.topophone.core.model.cogo.TraverseLeg
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.cogo.RoomCogoRepository
import es.topophone.data.db.ExportedSchemas
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** El borrador de la poligonal va en la copia completa y no en el duplicado ni en el paquete del proyecto (S038/RF-29). */
@RunWith(AndroidJUnit4::class)
class CogoBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "cogo-backup-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var cogo: RoomCogoRepository
    private lateinit var fixture: BackupFixture

    private val draft: TraverseDraft
        get() = TraverseDraft(
            fixture.streetId,
            DraftPoint.OfProject("p1", "P1"),
            DraftPoint.Typed(440_000.123456789012, 4_474_000.5),
            null,
            listOf(TraverseLeg(LegKind.AZIMUTH, 0.7853981633974483, 50.0, 0.4), TraverseLeg(LegKind.ANGLE_RIGHT, null, 12.0, null)),
            0L,
        )

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.38.0", workDir = folder.newFolder("w"), undoDir = folder.newFolder("u"))
        cogo = RoomCogoRepository(db, clock = { 9_000 })
        cogo.saveDraft(draft)
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    private fun source() = context.getDatabasePath(name)

    private fun snapshot(scope: SnapshotScope): File {
        val target = File(folder.root, "snapshot-${System.nanoTime()}.db")
        DatabaseSnapshot(context, source()).copy(target, scope)
        return target
    }

    private fun backup(): ByteArray = ByteArrayOutputStream().also {
        runBlocking { repository.writeBackup(it) } shouldBe true
    }.toByteArray()

    private fun restore(bytes: ByteArray) {
        val valid = runBlocking { repository.read({ bytes.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()
    }

    private fun currentDraft() = runBlocking { cogo.draft(fixture.streetId).first() }

    @Test
    fun `S038 RF-29 la copia completa lleva el borrador de la poligonal`() {
        val target = snapshot(SnapshotScope.All)

        rows(target, "traverse_drafts").size shouldBe 1
        rows(target, "traverse_drafts") shouldBe rows(source(), "traverse_drafts")
    }

    @Test
    fun `S038 RF-29 el paquete de un proyecto no lleva el borrador`() {
        val target = snapshot(SnapshotScope.Project(fixture.streetId))

        rows(target, "traverse_drafts") shouldBe emptyList()
    }

    @Test
    fun `S038 RF-29 el paquete escrito y leido tampoco lo lleva`() {
        val out = ByteArrayOutputStream().also { runBlocking { repository.writePackage(fixture.streetId, it) } shouldBe true }
        val bytes = out.toByteArray()
        val valid = runBlocking { repository.read({ bytes.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        val database = valid.database

        rows(database, "traverse_drafts") shouldBe emptyList()
    }

    @Test
    fun `S038 RF-29 el duplicado de un proyecto empieza sin borrador`() {
        val copy = runBlocking { fixture.projects.duplicate(fixture.streetId, "copia") }.shouldBeInstanceOf<SaveResult.Saved>().id

        runBlocking { cogo.draft(copy).first() }.shouldBeNull()
        currentDraft().shouldNotBeNull()
    }

    @Test
    fun `S038 RF-29 restaurar una copia devuelve el borrador como estaba en ella`() {
        val bytes = backup()
        runBlocking { cogo.clearDraft(fixture.streetId) }
        currentDraft().shouldBeNull()

        restore(bytes)

        currentDraft() shouldBe draft.copy(updatedAtMs = 9_000L)
    }

    @Test
    fun `S038 RF-29 la copia de una version anterior sin borrador se restaura sin borrador`() {
        val old = File(folder.root, "v32.db")
        ExportedSchemas.create(old, 32)
        val manifest = BackupManifest(BackupKind.BACKUP, 32, "0.37.0", Instant.EPOCH, 0, 0)
        val settingsFile = File(folder.root, "settings-32.json").apply { writeText("""{"stores":{},"appLanguage":""}""") }
        val files = mapOf(BackupArchive.DATABASE to old, BackupArchive.SETTINGS to settingsFile)
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(manifest, files, it) }.toByteArray()

        restore(bytes)

        currentDraft().shouldBeNull()
    }
}
