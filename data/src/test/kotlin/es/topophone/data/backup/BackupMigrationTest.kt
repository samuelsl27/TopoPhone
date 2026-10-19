package es.topophone.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupArchive
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupManifest
import es.topophone.data.db.ExportedSchemas
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Copias hechas con versiones anteriores de los datos: se abren con las migraciones de Room (S013/RF-14, RF-13). */
@RunWith(AndroidJUnit4::class)
class BackupMigrationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migr-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, name)
        settings = TestSettings(folder.newFolder("settings"))
        repository = BackupRepository(context, db, settings.archive, "0.13.0", clock = { 0 }, workDir = folder.newFolder("work"))
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    /** Proyecto en la forma de cada versión: v1–v2 sin `height_system`, v3 en adelante con él. */
    private fun oldDatabase(version: Int): File {
        val file = File(folder.root, "v$version.db")
        ExportedSchemas.create(file, version)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { old ->
            val heightSystem = if (version >= 3) ", 'ORTHOMETRIC'" else ""
            old.execSQL(
                "INSERT INTO projects VALUES ('p1', 'Calle Mayor 12', 'S. S.', '', 'ETRS89_UTM30', 'METRE', 'DMS', 'ETRS89', NULL, " +
                    "1792080000000, 1792080000000, NULL, NULL$heightSystem)",
            )
            if (version == 5) {
                old.execSQL(
                    "INSERT INTO points (id, project_id, name, description, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                        "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, hdop, correction_age, " +
                        "sigma_h, sigma_v, mode, epochs_used, epochs_discarded, from_replay, out_of_tolerance, raw_nmea, " +
                        "measured_from, measured_to, created_at) VALUES ('punto-1', 'p1', 'P12', 'arqueta', 40.416801234567891, " +
                        "-3.7037123456789, 701.357123456, 1.8, 40.416801234567891, -3.7037123456789, 699.423123456, " +
                        "440290.434567891, 4474257.414123456, 648.340987654, 'FLOAT', 18, 0.7, 2.0, 0.031, 0.052, 'AVERAGED', " +
                        "5, 2, 1, 'NOT_FIX', '\$GNGGA,1*00', 1500, 1900, 2000)",
                )
            }
        }
        return file
    }

    private fun archive(database: File, schemaVersion: Int): ByteArray {
        val manifest = BackupManifest(BackupKind.BACKUP, schemaVersion, "0.${schemaVersion + 5}.0", Instant.EPOCH, 1, 1)
        val settingsFile = File(folder.root, "settings-$schemaVersion.json").apply { writeText("""{"stores":{},"appLanguage":""}""") }
        return ByteArrayOutputStream().also {
            BackupArchive.write(manifest, mapOf(BackupArchive.DATABASE to database, BackupArchive.SETTINGS to settingsFile), it)
        }.toByteArray()
    }

    @Test
    fun `S013 RF-14 una copia de cada version anterior se abre en la version actual sin perder datos`() {
        for (version in 1 until TopoPhoneDatabase.VERSION) {
            val bytes = archive(oldDatabase(version), version)

            val valid = runBlocking { repository.read({ bytes.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()

            SQLiteDatabase.openDatabase(valid.database.path, null, SQLiteDatabase.OPEN_READONLY).use { migrated ->
                migrated.version shouldBe TopoPhoneDatabase.VERSION
                migrated.rawQuery("SELECT name, height_system FROM projects", null).use {
                    it.moveToFirst() shouldBe true
                    it.getString(0) shouldBe "Calle Mayor 12"
                    it.getString(1) shouldBe "ORTHOMETRIC"
                }
                if (version == 5) {
                    migrated.rawQuery("SELECT measured_easting, easting, trash_key FROM points", null).use {
                        it.moveToFirst() shouldBe true
                        it.getDouble(0) shouldBe 440_290.434567891
                        it.getDouble(1) shouldBe 440_290.434567891
                        it.getString(2) shouldBe ""
                    }
                }
            }
        }
    }

    @Test
    fun `S013 RF-13 una copia de una version futura no se abre`() {
        val future = File(folder.root, "v7.db").also { ExportedSchemas.create(it, TopoPhoneDatabase.VERSION) }
        SQLiteDatabase.openDatabase(future.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = TopoPhoneDatabase.VERSION + 1 }

        runBlocking { repository.read({ archive(future, TopoPhoneDatabase.VERSION + 1).inputStream() }, BackupKind.BACKUP) } shouldBe
            BackupRead.Newer
    }
}
