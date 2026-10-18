package es.topophone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.topophone.data.project.ProjectEntity
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Base de datos: durabilidad y banco de pruebas de migraciones (S006 y S007, RNF de las specs; RNF-T6). */
@RunWith(AndroidJUnit4::class)
class TopoPhoneDatabaseTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TopoPhoneDatabase::class.java)

    @After
    fun deleteDatabase() {
        context.deleteDatabase(TEST_DB)
    }

    private val project = ProjectEntity(
        id = "6f1c0d2e-0000-4000-8000-000000000001",
        name = "Calle Mayor 12",
        author = "S. S.",
        description = "",
        crs = "ETRS89_UTM30",
        linearUnit = "METRE",
        angularUnit = "DMS",
        correctionsDatum = "ETRS89",
        folderId = null,
        createdAt = 1_792_080_000_000,
        modifiedAt = 1_792_080_000_000,
        openedAt = null,
        deletedAt = null,
    )

    @Test
    fun `S006 RNF un proyecto guardado sigue ahi al cerrar y volver a abrir la base de datos`() {
        val db = TopoPhoneDatabase.build(context, TEST_DB)
        runBlocking { db.projects().insert(project) }
        db.close()

        val reopened = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            runBlocking { reopened.projects().byId(project.id) } shouldBe project
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `S006 RNF la base de datos escribe en modo WAL con sincronizacion completa`() {
        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.query("PRAGMA journal_mode").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "wal"
            }
            // Las escrituras van por la conexión principal, que es la de las transacciones.
            sqlite.beginTransaction()
            try {
                sqlite.query("PRAGMA synchronous").use {
                    it.moveToFirst() shouldBe true
                    it.getInt(0) shouldBe FULL
                }
            } finally {
                sqlite.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    /** Crea la base de datos tal como la describe el esquema exportado, sin room-testing. */
    private fun createFromExportedSchema(version: Int) {
        val schema = JSONObject(File("schemas/${TopoPhoneDatabase::class.java.name}/$version.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(TEST_DB).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(
                        indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table),
                    )
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = schema.getInt("version")
        }
    }

    @Test
    fun `S006 RNF el esquema exportado de la version actual coincide con el de la app`() {
        createFromExportedSchema(CURRENT_VERSION)

        // Room compara la huella del esquema guardado con la de la app al abrir: si no coinciden, no abre.
        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
        } finally {
            db.close()
        }
    }

    @Test
    fun `S007 RNF al actualizar desde la v1 los proyectos siguen ahi y se anade la tabla de perfiles NTRIP`() {
        createFromExportedSchema(1)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v1 ->
            v1.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
        }

        // Al abrir, Room migra hasta la versión actual y comprueba que el esquema resultante es el de la app.
        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            runBlocking { db.projects().byId(project.id) } shouldBe project
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ntrip_profiles").use {
                it.moveToFirst() shouldBe true
                it.getInt(0) shouldBe 0
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S006 RNF el banco de pruebas valida las migraciones contra los esquemas exportados`() {
        // room-testing compara el nombre de la base de datos con su ruta usando "/": en Windows falla
        // siempre. Se ejecuta en la integración continua (Linux) y en macOS.
        assumeFalse(File.separatorChar == '\\')
        migrations.createDatabase(TEST_DB, 1).close()

        migrations.runMigrationsAndValidate(TEST_DB, CURRENT_VERSION, true).close()
    }

    private companion object {
        const val TEST_DB = "topophone-test.db"
        const val CURRENT_VERSION = 3

        /** `PRAGMA synchronous`: 0 OFF, 1 NORMAL, 2 FULL. */
        const val FULL = 2
    }

    @Test
    fun `S008 RNF al actualizar de la v2 a la v3 los proyectos conservan su SC con altitud`() {
        createFromExportedSchema(2)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v2 ->
            v2.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe 3
            val migrated = runBlocking { db.projects().byId(project.id) }.shouldNotBeNull()
            migrated.crs shouldBe "ETRS89_UTM30"
            migrated.heightSystem shouldBe "ORTHOMETRIC"
        } finally {
            db.close()
        }
    }
}
