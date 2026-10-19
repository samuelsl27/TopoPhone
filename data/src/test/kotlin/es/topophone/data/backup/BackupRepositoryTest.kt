package es.topophone.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupArchive
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupManifest
import es.topophone.core.formats.backup.BackupReadResult
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Escribir la copia completa y leerla antes de restaurar (S013/RF-1, RF-5, RF-6, RF-12, RF-13). */
@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "repo-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private val now = Instant.parse("2026-10-19T06:02:31.734Z").toEpochMilli()

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"), TestSettings.FakeLanguage("es"))
        runBlocking { settings.stores.getValue("collector").edit { it[intPreferencesKey("collector_averaging_seconds")] = 20 } }
        repository =
            BackupRepository(context, db, settings.archive, appVersion = "0.13.0", clock = { now }, workDir = folder.newFolder("work"))
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    private fun written(): ByteArray = ByteArrayOutputStream().also { out ->
        runBlocking { repository.writeBackup(out) } shouldBe true
    }.toByteArray()

    private fun read(bytes: ByteArray, kind: BackupKind = BackupKind.BACKUP): BackupRead = runBlocking {
        repository.read({ bytes.inputStream() }, kind)
    }

    @Test
    fun `S013 RF-1 la copia lleva su manifiesto, la base de datos y los ajustes`() {
        val result = BackupArchive.read(written().inputStream(), BackupKind.BACKUP, TopoPhoneDatabase.VERSION, folder.newFolder("out"))

        val valid = result.shouldBeInstanceOf<BackupReadResult.Valid>()
        valid.manifest.kind shouldBe BackupKind.BACKUP
        valid.manifest.schemaVersion shouldBe TopoPhoneDatabase.VERSION
        valid.manifest.appVersion shouldBe "0.13.0"
        valid.files.keys shouldBe setOf(BackupArchive.DATABASE, BackupArchive.SETTINGS)
    }

    @Test
    fun `S013 RF-6 al leerla se sabe su fecha y cuantos proyectos y puntos tiene`() {
        val valid = read(written()).shouldBeInstanceOf<BackupRead.Valid>()

        valid.manifest.createdAt shouldBe Instant.parse("2026-10-19T06:02:31Z")
        valid.manifest.projectCount shouldBe 2
        valid.manifest.pointCount shouldBe 4
    }

    @Test
    fun `S013 RF-1 lo leido es lo que habia, con los ajustes y el idioma`() {
        val valid = read(written()).shouldBeInstanceOf<BackupRead.Valid>()

        val source = context.getDatabasePath(name)
        for (table in listOf("project_folders", "projects", "map_viewports", "points", "point_history")) {
            rows(valid.database, table) shouldBe rows(source, table)
        }
        valid.settings shouldBe runBlocking { settings.archive.export() }
        valid.settings!!.appLanguage shouldBe "es"
    }

    @Test
    fun `S013 RF-5 si el destino falla al escribir, la copia no se ha guardado`() {
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("sin espacio")
        }

        runBlocking { repository.writeBackup(broken) } shouldBe false
    }

    @Test
    fun `S013 RF-12 lo que no es una copia de TopoPhone, o es un paquete, se rechaza`() {
        read("Name,Easting\r\n".toByteArray()) shouldBe BackupRead.NotTopoPhone
        read(written(), kind = BackupKind.PACKAGE) shouldBe BackupRead.WrongKind(BackupKind.BACKUP)
        runBlocking { repository.read({ throw IOException("no se puede abrir") }, BackupKind.BACKUP) } shouldBe BackupRead.Damaged
    }

    @Test
    fun `S013 RF-12 una base de datos que no es la de TopoPhone esta danada`() {
        val notSqlite = File(folder.root, "texto.db").apply { writeText("no soy una base de datos") }
        read(archive(notSqlite)) shouldBe BackupRead.Damaged

        val foreign = File(folder.root, "ajena.db")
        SQLiteDatabase.openOrCreateDatabase(foreign, null).use {
            it.execSQL("CREATE TABLE fotos (id TEXT PRIMARY KEY)")
            it.version = TopoPhoneDatabase.VERSION
        }
        read(archive(foreign)) shouldBe BackupRead.Damaged
    }

    @Test
    fun `S013 RF-12 unos ajustes ilegibles hacen la copia danada`() {
        val valid = read(written()).shouldBeInstanceOf<BackupRead.Valid>()
        val database = File(folder.root, "copia.db").also { valid.database.copyTo(it) }

        read(archive(database, settingsText = "{no es json")) shouldBe BackupRead.Damaged
    }

    @Test
    fun `S013 RF-13 una copia con un esquema mas nuevo pide actualizar la app`() {
        val valid = read(written()).shouldBeInstanceOf<BackupRead.Valid>()
        val database = File(folder.root, "nueva.db").also { valid.database.copyTo(it) }
        SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = TopoPhoneDatabase.VERSION + 1 }

        read(archive(database)) shouldBe BackupRead.Newer
        read(archive(database, schemaVersion = TopoPhoneDatabase.VERSION + 1)) shouldBe BackupRead.Newer
    }

    /** Una copia hecha a mano con la base de datos [database]. */
    private fun archive(
        database: File,
        schemaVersion: Int = TopoPhoneDatabase.VERSION,
        settingsText: String = """{"stores":{},"appLanguage":""}""",
    ): ByteArray {
        val manifest = BackupManifest(BackupKind.BACKUP, schemaVersion, "0.12.0", Instant.ofEpochMilli(now), 1, 1)
        val settingsFile = File(folder.root, "settings-${System.nanoTime()}.json").apply { writeText(settingsText) }
        return ByteArrayOutputStream().also {
            BackupArchive.write(manifest, mapOf(BackupArchive.DATABASE to database, BackupArchive.SETTINGS to settingsFile), it)
        }.toByteArray()
    }
}
