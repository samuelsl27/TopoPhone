package es.topophone.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
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

/** La definición del SC de la biblioteca viaja en la copia de seguridad y en el paquete de un proyecto (S034/RF-13). */
@RunWith(AndroidJUnit4::class)
class LibraryCrsBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "library-crs-backup-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var fixture: BackupFixture
    private val definition =
        "v1\t2154\tRGF93 v1 / Lambert-93\tP\t4171\tRGF93 v1\t6378137.0,298.257222101\t0.0\tlcc2\t" +
            "\t1.0\t\tETRS89\tt\t\t\tnull"

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.38.0", workDir = folder.newFolder("w"), undoDir = folder.newFolder("u"))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE projects SET crs = 'LIBRARY:2154', crs_definition = ?, geoid_grid = 'fr_ign_RAF20.tif' WHERE id = ?",
            arrayOf<Any>(definition, fixture.streetId),
        )
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    private fun column(file: File, column: String): String? =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
            sql.rawQuery("SELECT $column FROM projects WHERE id = ?", arrayOf(fixture.streetId)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }

    @Test
    fun `S034 RF-13 el paquete de un proyecto lleva su SC de la biblioteca y su geoide`() {
        val target = File(folder.root, "snapshot.db")
        DatabaseSnapshot(context, context.getDatabasePath(name)).copy(target, SnapshotScope.Project(fixture.streetId))

        column(target, "crs_definition") shouldBe definition
        column(target, "geoid_grid") shouldBe "fr_ign_RAF20.tif"
        column(target, "crs") shouldBe "LIBRARY:2154"
    }

    @Test
    fun `S034 RF-13 restaurar una copia devuelve la definicion del SC de la biblioteca`() {
        val bytes = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        db.openHelper.writableDatabase.execSQL("UPDATE projects SET crs = 'ETRS89_UTM30', crs_definition = NULL, geoid_grid = NULL")

        val valid = runBlocking { repository.read({ bytes.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        column(context.getDatabasePath(name), "crs_definition") shouldBe definition
        column(context.getDatabasePath(name), "geoid_grid") shouldBe "fr_ign_RAF20.tif"
    }
}
