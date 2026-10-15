package es.topophone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.topophone.data.project.ProjectEntity
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Base de datos de proyectos: durabilidad y banco de pruebas de migraciones (S006, RNF de la spec; RNF-T6). */
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
    fun `S006 RNF el esquema v1 exportado coincide con el de la app`() {
        createFromExportedSchema(1)

        // Room compara la huella del esquema guardado con la de la app al abrir: si no coinciden, no abre.
        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe 1
        } finally {
            db.close()
        }
    }

    @Test
    fun `S006 RNF el banco de pruebas valida la v1 contra su esquema exportado`() {
        // room-testing compara el nombre de la base de datos con su ruta usando "/": en Windows falla
        // siempre. Se ejecuta en la integración continua (Linux) y en macOS.
        assumeFalse(File.separatorChar == '\\')
        migrations.createDatabase(TEST_DB, 1).close()

        migrations.runMigrationsAndValidate(TEST_DB, 1, true).close()
    }

    private companion object {
        const val TEST_DB = "topophone-test.db"

        /** `PRAGMA synchronous`: 0 OFF, 1 NORMAL, 2 FULL. */
        const val FULL = 2
    }
}
