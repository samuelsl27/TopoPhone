package es.topophone.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.data.map.MapViewportEntity
import es.topophone.data.point.PointEntity
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.ProjectEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Base de datos: durabilidad y banco de pruebas de migraciones (S006 a S011, RNF de las specs; RNF-T6). */
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
    private fun createFromExportedSchema(version: Int) = ExportedSchemas.create(context.getDatabasePath(TEST_DB), version)

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

    @Test
    fun `S011 RF-20 al actualizar de la v5 a la v6 un punto de S010 conserva su medida y queda activo y visible`() {
        createFromExportedSchema(5)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v5 ->
            v5.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v5.execSQL(
                "INSERT INTO points (id, project_id, name, description, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, hdop, correction_age, " +
                    "sigma_h, sigma_v, mode, epochs_used, epochs_discarded, from_replay, out_of_tolerance, raw_nmea, " +
                    "measured_from, measured_to, created_at) VALUES (?, ?, 'P12', 'arqueta', 40.416801234567891, " +
                    "-3.7037123456789, 701.357123456, 1.8, 40.416801234567891, -3.7037123456789, 699.423123456, " +
                    "440290.434567891, 4474257.414123456, 648.340987654, 'FLOAT', 18, 0.7, 2.0, 0.031, 0.052, 'AVERAGED', " +
                    "5, 2, 1, 'NOT_FIX', '\$GNGGA,1*00', 1500, 1900, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val point = runBlocking { RoomPointRepository(db).points(project.id).first() }.single()
            val coordinates = PointCoordinates(
                GeodeticPosition(40.416801234567891, -3.7037123456789, 699.423123456),
                440_290.434567891,
                4_474_257.414123456,
                648.340987654,
            )
            point.origin shouldBe PointOrigin.MEASURED
            point.coordinates shouldBe coordinates
            point.measurement.shouldNotBeNull().coordinates shouldBe coordinates
            point.measurement.shouldNotBeNull().antenna shouldBe GeodeticPosition(40.416801234567891, -3.7037123456789, 701.357123456)
            point.measurement.shouldNotBeNull().outOfTolerance shouldBe setOf(PrecisionFailure.NOT_FIX)
            point.edited shouldBe false
            point.hidden shouldBe false
            point.deletedAtMs shouldBe null
            point.typed shouldBe null
        } finally {
            db.close()
        }
    }

    @Test
    fun `S015 RF-20 al actualizar de la v6 a la v7 un punto medido sigue ahi, sin base`() {
        createFromExportedSchema(6)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v6 ->
            v6.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v6.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, created_at) VALUES (?, ?, 'P12', '', 'MEASURED', " +
                    "40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, -3.7037123456789, 699.423, " +
                    "440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', 'INSTANT', 1, 0, 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val point = runBlocking { RoomPointRepository(db).points(project.id).first() }.single()
            point.name shouldBe "P12"
            point.measurement.shouldNotBeNull().quality.solution shouldBe "FIX"
            point.measurement.shouldNotBeNull().base shouldBe null
        } finally {
            db.close()
        }
    }

    @Test
    fun `S011 RF-22 y RF-25 el nombre de un punto de la papelera queda libre y entre los activos sigue siendo unico`() {
        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            runBlocking { db.projects().insert(project) }
            val base = PointEntity(
                id = "a", projectId = project.id, name = "P1", description = "", origin = "MANUAL",
                antennaLat = null, antennaLon = null, antennaH = null, poleHeight = null,
                groundLat = 40.0, groundLon = -3.7, groundH = null, easting = 1.0, northing = 2.0, height = null,
                measuredGroundLat = null, measuredGroundLon = null, measuredGroundH = null,
                measuredEasting = null, measuredNorthing = null, measuredHeight = null,
                solution = null, satellites = null, hdop = null, correctionAge = null, sigmaH = null, sigmaV = null, mode = null,
                epochsUsed = null, epochsDiscarded = null, fromReplay = null, outOfTolerance = "", rawNmea = null,
                measuredFrom = null, measuredTo = null, typedForm = null, typedFirst = null, typedSecond = null,
                typedHeight = null, typedHeightKind = null, edited = false, hidden = false, copyOf = null,
                createdAt = 1_000, deletedAt = 5_000, trashKey = "a",
            )
            runBlocking {
                db.points().insert(base)
                db.points().insert(base.copy(id = "b", deletedAt = null, trashKey = ""))
            }

            shouldThrow<SQLiteConstraintException> {
                runBlocking { db.points().insert(base.copy(id = "c", deletedAt = null, trashKey = "")) }
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "topophone-test.db"
        const val CURRENT_VERSION = 7

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
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val migrated = runBlocking { db.projects().byId(project.id) }.shouldNotBeNull()
            migrated.crs shouldBe "ETRS89_UTM30"
            migrated.heightSystem shouldBe "ORTHOMETRIC"
        } finally {
            db.close()
        }
    }

    @Test
    fun `S009 RNF al actualizar de la v3 a la v4 los proyectos siguen ahi y se anade la tabla de encuadres del mapa`() {
        createFromExportedSchema(3)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v3 ->
            v3.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ELLIPSOIDAL')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            runBlocking { db.projects().byId(project.id) }.shouldNotBeNull().heightSystem shouldBe "ELLIPSOIDAL"
            val viewport = MapViewportEntity(project.id, latitude = 40.41668, longitude = -3.70371, zoom = 17.5)
            runBlocking { db.mapViewports().upsert(viewport) }
            runBlocking { db.mapViewports().byProject(project.id) } shouldBe viewport
        } finally {
            db.close()
        }
    }

    @Test
    fun `S010 RNF al actualizar de la v4 a la v5 los proyectos siguen ahi y se añade la tabla de puntos`() {
        createFromExportedSchema(4)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v4 ->
            v4.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            runBlocking { db.projects().byId(project.id) }.shouldNotBeNull().name shouldBe project.name
            runBlocking { db.points().lastName(project.id) } shouldBe null
        } finally {
            db.close()
        }
    }
}
