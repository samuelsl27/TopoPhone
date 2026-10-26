package es.topophone.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointTilt
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.TiltMode
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
    fun `S016 RF-22 de la v7 a la v8 los medidos son jalon vertical`() {
        createFromExportedSchema(7)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v7 ->
            v7.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v7.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, created_at) VALUES (?, ?, 'P12', '', 'MEASURED', " +
                    "40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, -3.7037123456789, 699.423, " +
                    "440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', 'INSTANT', 1, 0, 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
            v7.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, ground_lat, ground_lon, out_of_tolerance, created_at) " +
                    "VALUES (?, ?, 'M1', '', 'MANUAL', 40.4, -3.7, '', 3000)",
                arrayOf<Any>("manual-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            val measured = points.single { it.name == "P12" }.measurement.shouldNotBeNull()
            measured.tilt shouldBe PointTilt(TiltMode.VERTICAL, verified = false, angles = null)
            db.openHelper.readableDatabase.query("SELECT tilt_mode FROM points WHERE id = 'manual-1'").use { cursor ->
                cursor.moveToFirst() shouldBe true
                cursor.isNull(0) shouldBe true
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S017 RF-12 de la v8 a la v9 los puntos conservan sus datos, con lo nuevo vacio y sin tramos de frases`() {
        createFromExportedSchema(8)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v8 ->
            v8.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v8.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P12', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', 'AVERAGED', " +
                    "20, 3, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
            v8.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, ground_lat, ground_lon, out_of_tolerance, created_at) " +
                    "VALUES (?, ?, 'M1', '', 'MANUAL', 40.4, -3.7, '', 3000)",
                arrayOf<Any>("manual-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            val measured = points.single { it.name == "P12" }.measurement.shouldNotBeNull()
            measured.rawNmea shouldBe "\$GNGGA,1*00"
            measured.nmeaChunks shouldBe 0
            measured.quality.epochsUsed shouldBe 20
            measured.quality.averagedTimeS shouldBe null
            measured.quality.deviationHorizontalM shouldBe null
            measured.quality.deviationVerticalM shouldBe null
            measured.quality.auto shouldBe null
            points.single { it.name == "M1" }.measurement shouldBe null
            db.openHelper.readableDatabase.query("SELECT avg_time, dev_h, dev_v, auto_mode, nmea_chunks FROM points").use { cursor ->
                while (cursor.moveToNext()) {
                    (0..3).forEach { cursor.isNull(it) shouldBe true }
                    cursor.getInt(4) shouldBe 0
                }
            }
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM point_nmea").use { cursor ->
                cursor.moveToFirst() shouldBe true
                cursor.getInt(0) shouldBe 0
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S018 RF-28 de la v9 a la v10 los puntos conservan sus datos, con lo del replanteo vacio`() {
        createFromExportedSchema(9)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v9 ->
            v9.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v9.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P12', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', 'INSTANT', " +
                    "1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val point = runBlocking { RoomPointRepository(db).points(project.id).first() }.single()
            point.name shouldBe "P12"
            point.origin shouldBe PointOrigin.MEASURED
            point.staked shouldBe null
            point.measurement.shouldNotBeNull().rawNmea shouldBe "\$GNGGA,1*00"
            db.openHelper.readableDatabase.query(
                "SELECT stake_design_id, stake_design_name, stake_design_east, stake_design_north, stake_design_height, " +
                    "stake_design_lat, stake_design_lon, stake_design_ground_h, stake_d_east, stake_d_north, stake_d_horizontal, " +
                    "stake_d_height FROM points",
            ).use { cursor ->
                cursor.moveToFirst() shouldBe true
                (0..11).forEach { cursor.isNull(it) shouldBe true }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S019 RF-26 de la v10 a la v11 los puntos y replanteos conservan sus datos, con lo nuevo vacio`() {
        createFromExportedSchema(10)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v10 ->
            v10.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            // Un punto medido normal y un replanteo de S018 (con diseno por valor y diferencias, sin lo de S019).
            v10.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P12', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', 'INSTANT', " +
                    "1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
            v10.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at, " +
                    "stake_design_id, stake_design_name, stake_design_east, stake_design_north, stake_design_height, " +
                    "stake_design_lat, stake_design_lon, stake_design_ground_h, stake_d_east, stake_d_north, stake_d_horizontal, " +
                    "stake_d_height) VALUES " +
                    "(?, ?, '12_stkd', '', 'STAKED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', 'INSTANT', " +
                    "1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 3000, " +
                    "'design-12', '12', 440290.4, 4474257.4, 648.346, 40.4168, -3.7037, 699.428, 0.0345, 0.0141, 0.0373, -0.005)",
                arrayOf<Any>("punto-2", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            val measured = points.single { it.name == "P12" }
            measured.staked shouldBe null
            val staked = points.single { it.name == "12_stkd" }
            staked.origin shouldBe PointOrigin.STAKED
            val ref = staked.staked.shouldNotBeNull()
            ref.designName shouldBe "12"
            ref.dHorizontalM shouldBe 0.0373
            ref.dHeightM shouldBe -0.005
            // S018 no guardaba la cota usada: era la altura del punto de diseno, y asi se lee.
            ref.designElevationM shouldBe 648.346
            ref.toleranceHM shouldBe null
            ref.toleranceVM shouldBe null
            ref.withinTolerance shouldBe null
            ref.notEvaluated shouldBe true
            db.openHelper.readableDatabase.query(
                "SELECT stake_design_elevation, stake_tol_h, stake_tol_v, stake_within FROM points",
            ).use { cursor ->
                while (cursor.moveToNext()) (0..3).forEach { cursor.isNull(it) shouldBe true }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S028 RNF-T6 de la v11 a la v12 los puntos conservan sus datos, con import_id vacio y su indice`() {
        createFromExportedSchema(11)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v11 ->
            v11.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v11.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P12', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', 'INSTANT', " +
                    "1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
            v11.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, ground_lat, ground_lon, easting, northing, " +
                    "out_of_tolerance, typed_form, typed_first, typed_second, typed_height_kind, created_at) VALUES " +
                    "(?, ?, 'M1', 'a mano', 'MANUAL', 40.4, -3.7, 440000.123456789, 4474000.987654321, '', " +
                    "'PROJECT_CRS', 440000.123456789, 4474000.987654321, 'PROJECT', 3000)",
                arrayOf<Any>("punto-2", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.map { it.name } shouldBe listOf("P12", "M1")
            points.map { it.origin } shouldBe listOf(PointOrigin.MEASURED, PointOrigin.MANUAL)
            points.map { it.importId } shouldBe listOf(null, null)
            points[1].typed.shouldNotBeNull().first shouldBe 440000.123456789
            points[0].measurement.shouldNotBeNull().rawNmea shouldBe "\$GNGGA,1*00"
            db.openHelper.readableDatabase.query("SELECT import_id FROM points").use { cursor ->
                while (cursor.moveToNext()) cursor.isNull(0) shouldBe true
            }
            db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_points_import_id'")
                .use { it.count shouldBe 1 }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S040 RNF-T6 de la v12 a la v13 los puntos siguen y se anaden las tablas del dibujo`() {
        createFromExportedSchema(12)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v12 ->
            v12.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v12.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, ground_lat, ground_lon, easting, northing, " +
                    "out_of_tolerance, typed_form, typed_first, typed_second, typed_height_kind, created_at) VALUES " +
                    "(?, ?, 'M1', 'a mano', 'MANUAL', 40.4, -3.7, 440000.123456789, 4474000.987654321, '', " +
                    "'PROJECT_CRS', 440000.123456789, 4474000.987654321, 'PROJECT', 3000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.map { it.name } shouldBe listOf("M1")
            points.single().typed.shouldNotBeNull().first shouldBe 440000.123456789
            val names = mutableListOf<String>()
            db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master " +
                    "WHERE name LIKE '%cad_%' AND name NOT LIKE 'sqlite_autoindex%'",
            ).use {
                while (it.moveToNext()) names += it.getString(0)
            }
            names.toSet() shouldBe setOf(
                "cad_drawings",
                "cad_layers",
                "cad_chunks",
                "index_cad_drawings_project_id",
                "index_cad_layers_drawing_id",
                "index_cad_chunks_layer_id",
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `S041 RNF-T6 de la v13 a la v14 los puntos replanteados y los dibujos siguen y lo nuevo queda vacio`() {
        createFromExportedSchema(13)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v13 ->
            v13.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v13.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at, " +
                    "stake_design_id, stake_design_name, stake_design_east, stake_design_north, stake_design_height, " +
                    "stake_design_lat, stake_design_lon, stake_design_ground_h, stake_d_east, stake_d_north, " +
                    "stake_d_horizontal, stake_d_height, stake_design_elevation, stake_tol_h, stake_tol_v, stake_within) VALUES " +
                    "(?, ?, '12_stkd', '', 'STAKED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', 'INSTANT', " +
                    "1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000, 'design-1', '12', 440290.45805, 4474257.38189, 648.35, " +
                    "40.4168, -3.7038, 699.428, -0.0236, 0.0323, 0.04, -0.009, 648.35, 0.025, 0.03, 0)",
                arrayOf<Any>("punto-1", project.id),
            )
            v13.execSQL(
                "INSERT INTO cad_drawings (id, project_id, file_name, imported_at_ms, source_crs, source_unit, use_elevations, " +
                    "has_elevations, incomplete, status, min_e, max_e, min_n, max_n) VALUES " +
                    "('d1', ?, 'plano.dxf', 1, 'PROJECT', 'METRE', 1, 1, 0, 'READY', 1.0, 2.0, 3.0, 4.0)",
                arrayOf<Any>(project.id),
            )
            v13.execSQL(
                "INSERT INTO cad_layers (id, drawing_id, name, kind, color_argb, auto_contrast, visible, entity_count, position) " +
                    "VALUES ('l1', 'd1', 'Ejes', 'LINES', -65536, 0, 1, 2, 0)",
            )
            v13.execSQL(
                "INSERT INTO cad_chunks (layer_id, min_e, max_e, min_n, max_n, entity_count, data) VALUES " +
                    "('l1', 1.0, 2.0, 3.0, 4.0, 2, x'0102')",
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val staked = runBlocking { RoomPointRepository(db).points(project.id).first() }.single().staked.shouldNotBeNull()
            staked.designId shouldBe "design-1"
            staked.dEastM shouldBe -0.0236
            staked.withinTolerance shouldBe false
            staked.source shouldBe StakeSource.ProjectPoint
            staked.mode shouldBe null
            staked.chainageM shouldBe null
            staked.dLateralM shouldBe null
            count(db, "cad_drawings") shouldBe 1
            count(db, "cad_layers") shouldBe 1
            count(db, "cad_chunks") shouldBe 1
            db.openHelper.readableDatabase.query(
                "SELECT stake_cad_drawing, stake_cad_layer, stake_cad_kind, stake_mode, stake_chainage, stake_offset, " +
                    "stake_d_lateral FROM points",
            ).use { cursor ->
                cursor.moveToFirst() shouldBe true
                (0 until 7).all { cursor.isNull(it) } shouldBe true
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S042 RNF-T6 de la v14 a la v15 los puntos y los dibujos siguen y lo nuevo queda vacio`() {
        createFromExportedSchema(14)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v14 ->
            v14.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v14.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at, " +
                    "stake_design_id, stake_design_name, stake_design_east, stake_design_north, stake_design_height, " +
                    "stake_design_lat, stake_design_lon, stake_design_ground_h, stake_d_east, stake_d_north, " +
                    "stake_d_horizontal, stake_d_height, stake_design_elevation, stake_tol_h, stake_tol_v, stake_within, " +
                    "stake_cad_drawing, stake_cad_layer, stake_cad_kind, stake_mode, stake_chainage, stake_offset, " +
                    "stake_d_lateral) VALUES " +
                    "(?, ?, 'Eje_1_stkd', '', 'STAKED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', 'INSTANT', " +
                    "1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000, 'cad:l1:1:0', 'Eje', 440290.45805, 4474257.38189, 648.35, " +
                    "40.4168, -3.7038, 699.428, -0.0236, 0.0323, 0.04, -0.009, 648.35, 0.025, 0.03, 0, " +
                    "'plano.dxf', 'Eje', 'POLYLINE', 'ALONG', 45.0, -1.5, 0.012)",
                arrayOf<Any>("punto-1", project.id),
            )
            v14.execSQL(
                "INSERT INTO cad_drawings (id, project_id, file_name, imported_at_ms, source_crs, source_unit, use_elevations, " +
                    "has_elevations, incomplete, status, min_e, max_e, min_n, max_n) VALUES " +
                    "('d1', ?, 'plano.dxf', 1, 'PROJECT', 'METRE', 1, 1, 0, 'READY', 1.0, 2.0, 3.0, 4.0)",
                arrayOf<Any>(project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val staked = runBlocking { RoomPointRepository(db).points(project.id).first() }.single().staked.shouldNotBeNull()
            staked.designId shouldBe "cad:l1:1:0"
            staked.mode shouldBe LineStakeoutMode.ALONG
            staked.chainageM shouldBe 45.0
            staked.dLateralM shouldBe 0.012
            staked.elevationSource shouldBe null
            staked.surfaceName shouldBe null
            staked.surfaceElevationM shouldBe null
            staked.verticalOffsetM shouldBe null
            staked.usedSurface shouldBe false
            staked.outsideSurface shouldBe false
            count(db, "cad_drawings") shouldBe 1
            count(db, "surfaces") shouldBe 0
            count(db, "surface_tiles") shouldBe 0
            db.openHelper.readableDatabase.query("SELECT active_surface_id, vertical_offset_m FROM projects").use { cursor ->
                cursor.moveToFirst() shouldBe true
                cursor.isNull(0) shouldBe true
                cursor.getDouble(1) shouldBe 0.0
            }
            db.openHelper.readableDatabase.query(
                "SELECT stake_elev_source, stake_surface_name, stake_surface_imported_ms, stake_surface_elev, stake_vertical_offset FROM points",
            ).use { cursor ->
                cursor.moveToFirst() shouldBe true
                (0 until 5).all { cursor.isNull(it) } shouldBe true
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S042 RNF-T6 borrar el proyecto borra sus superficies y sus teselas`() {
        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            runBlocking { db.projects().insert(project) }
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL(
                "INSERT INTO surfaces (id, project_id, name, file_name, imported_at_ms, source_crs, source_unit, status, min_e, max_e, " +
                    "min_n, max_n, min_z, max_z, vertex_count, triangle_count, hole_count) VALUES " +
                    "('s1', ?, 'terreno', 'obra.xml', 1, 'PROJECT', 'METRE', 'READY', 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 3, 1, 0)",
                arrayOf<Any>(project.id),
            )
            sqlite.execSQL(
                "INSERT INTO surface_tiles (surface_id, min_e, max_e, min_n, max_n, triangle_count, data) VALUES " +
                    "('s1', 1.0, 2.0, 3.0, 4.0, 1, x'0102')",
            )
            count(db, "surface_tiles") shouldBe 1

            sqlite.execSQL("DELETE FROM projects WHERE id = ?", arrayOf<Any>(project.id))

            count(db, "surfaces") shouldBe 0
            count(db, "surface_tiles") shouldBe 0
        } finally {
            db.close()
        }
    }

    @Test
    fun `S040 RF-30 borrar el proyecto borra sus dibujos, capas y trozos`() {
        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            runBlocking { db.projects().insert(project) }
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL(
                "INSERT INTO cad_drawings (id, project_id, file_name, imported_at_ms, source_crs, source_unit, use_elevations, " +
                    "has_elevations, incomplete, status, min_e, max_e, min_n, max_n) VALUES " +
                    "('d1', ?, 'plano.dxf', 1, 'PROJECT', 'METRE', 1, 1, 0, 'READY', 1.0, 2.0, 3.0, 4.0)",
                arrayOf<Any>(project.id),
            )
            sqlite.execSQL(
                "INSERT INTO cad_layers (id, drawing_id, name, kind, color_argb, auto_contrast, visible, entity_count, position) " +
                    "VALUES ('l1', 'd1', 'Ejes', 'LINES', -65536, 0, 1, 2, 0)",
            )
            sqlite.execSQL(
                "INSERT INTO cad_chunks (layer_id, min_e, max_e, min_n, max_n, entity_count, data) VALUES " +
                    "('l1', 1.0, 2.0, 3.0, 4.0, 2, x'0102')",
            )
            count(db, "cad_chunks") shouldBe 1

            sqlite.execSQL("DELETE FROM projects WHERE id = ?", arrayOf<Any>(project.id))

            count(db, "cad_drawings") shouldBe 0
            count(db, "cad_layers") shouldBe 0
            count(db, "cad_chunks") shouldBe 0
        } finally {
            db.close()
        }
    }

    private fun count(db: TopoPhoneDatabase, table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `S028 RF-27 un punto importado se guarda con su origen y su importacion, y se lee igual`() {
        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            runBlocking { db.projects().insert(project) }
            val point = es.topophone.core.model.point.Point(
                id = "imp-1-a",
                projectId = project.id,
                name = "007",
                description = "Esquina",
                origin = PointOrigin.IMPORTED,
                coordinates = PointCoordinates(
                    GeodeticPosition(40.416801234567891, -3.7037123456789, 699.423),
                    440290.434567891,
                    4474257.414123456,
                    648.34,
                ),
                measurement = null,
                typed = es.topophone.core.model.point.TypedCoordinates(
                    es.topophone.core.model.point.EntryForm.PROJECT_CRS,
                    440290.434567891,
                    4474257.414123456,
                    648.34,
                    es.topophone.core.model.point.HeightKind.PROJECT,
                ),
                edited = false,
                hidden = false,
                copyOf = null,
                createdAtMs = 5_000,
                deletedAtMs = null,
                importId = "imp-1",
            )

            runBlocking { RoomPointRepository(db).create(point) }

            val read = runBlocking { RoomPointRepository(db).points(project.id).first() }.single()
            read shouldBe point
            read.origin shouldBe PointOrigin.IMPORTED
            read.importId shouldBe "imp-1"
            runBlocking { RoomPointRepository(db).pointsForExport(project.id) }.single().importId shouldBe "imp-1"
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
        const val CURRENT_VERSION = 15

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
