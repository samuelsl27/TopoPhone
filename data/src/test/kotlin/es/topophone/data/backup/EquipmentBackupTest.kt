package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * El equipo de cada punto viaja en la copia y en el paquete de un proyecto; el progreso de las listas y los datos de cada receptor
 * solo en la copia completa (S043/RF-19, RNF-T6).
 */
@RunWith(AndroidJUnit4::class)
class EquipmentBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "equipment-backup-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var fixture: BackupFixture

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.43.0", workDir = folder.newFolder("w"), undoDir = folder.newFolder("u"))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE points SET equipment_model = 'Reach RS3', equipment_serial = 'RS3-0042', equipment_firmware = '2026.2'")
        sql.execSQL("INSERT INTO config_steps VALUES ('AA:BB', 'NTRIP_ROVER', 'ntrip_fix', 1000)")
        sql.execSQL("INSERT INTO equipment_profiles VALUES ('AA:BB', 'Reach RS3', 'RS3-0042', '2026.2', '192.168.42.1')")
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

    private fun equipment(file: File) = BackupFixture.columns(file, "points").let { columns ->
        val at = listOf("equipment_model", "equipment_serial", "equipment_firmware").map(columns::indexOf)
        rows(file, "points").map { row -> at.map { row[it] } }
    }

    @Test
    fun `S043 RF-19 la copia completa lleva el equipo de cada punto`() {
        val target = snapshot(SnapshotScope.All)

        equipment(target).distinct() shouldBe listOf(listOf("Reach RS3", "RS3-0042", "2026.2"))
        equipment(target).size shouldBe rows(source(), "points").size
    }

    @Test
    fun `S043 RF-19 el paquete de un proyecto lleva el equipo de sus puntos`() {
        val target = snapshot(SnapshotScope.Project(fixture.streetId))

        equipment(target).isNotEmpty() shouldBe true
        equipment(target).distinct() shouldBe listOf(listOf("Reach RS3", "RS3-0042", "2026.2"))
    }

    @Test
    fun `S043 RNF-T6 pasos y datos del receptor solo en la copia`() {
        val all = snapshot(SnapshotScope.All)
        val pack = snapshot(SnapshotScope.Project(fixture.streetId))

        rows(all, "config_steps") shouldBe rows(source(), "config_steps")
        rows(all, "equipment_profiles") shouldBe rows(source(), "equipment_profiles")
        rows(pack, "config_steps") shouldBe emptyList()
        rows(pack, "equipment_profiles") shouldBe emptyList()
    }

    @Test
    fun `S043 RNF-T6 restaurar devuelve equipo, pasos y datos`() {
        val points = rows(source(), "points")
        val steps = rows(source(), "config_steps")
        val profiles = rows(source(), "equipment_profiles")
        val bytes = backup()
        db.openHelper.writableDatabase.execSQL("UPDATE points SET equipment_model = NULL, equipment_serial = NULL")
        db.openHelper.writableDatabase.execSQL("DELETE FROM config_steps")
        db.openHelper.writableDatabase.execSQL("DELETE FROM equipment_profiles")

        val valid = runBlocking { repository.read({ bytes.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        rows(source(), "points") shouldBe points
        rows(source(), "config_steps") shouldBe steps
        rows(source(), "equipment_profiles") shouldBe profiles
    }
}
