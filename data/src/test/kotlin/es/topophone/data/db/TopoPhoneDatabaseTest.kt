package es.topophone.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.AxisRef
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointTilt
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.surface.SurfaceKind
import es.topophone.data.alignment.AlignmentEntity
import es.topophone.data.baseshift.BaseShiftEntity
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.codes.AttributeOptionEntity
import es.topophone.data.codes.CodeAttributeEntity
import es.topophone.data.codes.CodeLibraryEntity
import es.topophone.data.codes.LibraryCodeEntity
import es.topophone.data.linear.LinearSurveyEntity
import es.topophone.data.linear.LinearSurveyPointEntity
import es.topophone.data.map.MapViewportEntity
import es.topophone.data.photo.PhotoEventEntity
import es.topophone.data.photo.PointPhotoEntity
import es.topophone.data.point.PointEntity
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.ProjectEntity
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.section.SectionProfileEntity
import es.topophone.data.section.SectionTemplateEntity
import es.topophone.data.section.SuperelevationRowEntity
import es.topophone.data.slope.SlopeRuleEntity
import es.topophone.data.surface.RoomSurfaceRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
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
                "cad_chunk_attributes", // S048 (v19): se añade al migrar hasta la versión actual
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

    @Test
    fun `S046 RNF-T6 de la v15 a la v16 los puntos siguen y el equipo queda vacio`() {
        createFromExportedSchema(15)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v15 ->
            v15.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v15.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', 'INSTANT', " +
                    "1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.single().name shouldBe "P1"
            points.single().measurement.shouldNotBeNull().poleHeightM shouldBe 1.8
            count(db, "antenna_profiles") shouldBe 0
            count(db, "saved_poles") shouldBe 0
            count(db, "receiver_gear") shouldBe 0
            db.openHelper.readableDatabase.query(
                "SELECT pole_name, pole_kind, pole_entered_m, antenna_profile, antenna_offset_m, antenna_height_m FROM points",
            ).use { cursor ->
                cursor.moveToFirst() shouldBe true
                (0 until 6).all { cursor.isNull(it) } shouldBe true
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S047 RNF-T6 de la v16 a la v17 los puntos siguen y los tipos admitidos quedan vacios`() {
        createFromExportedSchema(16)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v16 ->
            v16.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v16.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FLOAT', 24, 'NOT_FIX', " +
                    "'INSTANT', 1, 3, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.single().name shouldBe "P1"
            val measurement = points.single().measurement.shouldNotBeNull()
            measurement.quality.solution shouldBe "FLOAT"
            measurement.quality.epochsDiscarded shouldBe 3
            measurement.outOfTolerance shouldBe setOf(PrecisionFailure.NOT_FIX)
            db.openHelper.readableDatabase.query("SELECT admitted_solutions, epochs_discarded_by_type, epochs_discarded FROM points")
                .use { cursor ->
                    cursor.moveToFirst() shouldBe true
                    cursor.isNull(0) shouldBe true
                    cursor.isNull(1) shouldBe true
                    cursor.getInt(2) shouldBe 3
                }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S006 RNF-T6 de la v17 a la v18 los proyectos siguen sin punto central`() {
        createFromExportedSchema(17)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v17 ->
            v17.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val migrated = runBlocking { RoomProjectRepository(db).project(project.id) }.shouldNotBeNull()
            migrated.name shouldBe project.name
            migrated.centralPoint.shouldBeNull()
            db.openHelper.readableDatabase.query("SELECT central_latitude, central_longitude FROM projects").use { cursor ->
                cursor.moveToFirst() shouldBe true
                cursor.isNull(0) shouldBe true
                cursor.isNull(1) shouldBe true
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S048 RNF-T6 de la v18 a la v19 los dibujos de DXF siguen y lo nuevo queda vacio`() {
        createFromExportedSchema(18)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v18 ->
            v18.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v18.execSQL(
                "INSERT INTO cad_drawings (id, project_id, file_name, imported_at_ms, source_crs, source_unit, use_elevations, " +
                    "has_elevations, incomplete, status, min_e, max_e, min_n, max_n) VALUES " +
                    "('d1', ?, 'plano.dxf', 1, 'UTM:ED50:30', 'METRE', 1, 1, 0, 'READY', 1.0, 2.0, 3.0, 4.0)",
                arrayOf<Any>(project.id),
            )
            v18.execSQL(
                "INSERT INTO cad_layers (id, drawing_id, name, kind, color_argb, auto_contrast, visible, entity_count, position) " +
                    "VALUES ('l1', 'd1', 'Ejes', 'LINES', -65536, 0, 1, 2, 0)",
            )
            v18.execSQL(
                "INSERT INTO cad_chunks (layer_id, min_e, max_e, min_n, max_n, entity_count, data) VALUES " +
                    "('l1', 1.0, 2.0, 3.0, 4.0, 2, x'0102')",
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val drawing = runBlocking { RoomCadRepository(db).drawings(project.id).first() }.single()
            drawing.drawing.origin shouldBe CadOrigin.DXF
            drawing.drawing.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
            drawing.layers.single().sourceTable.shouldBeNull()
            drawing.layers.single().nameField.shouldBeNull()
            count(db, "cad_chunks") shouldBe 1
            count(db, "cad_chunk_attributes") shouldBe 0
        } finally {
            db.close()
        }
    }

    @Test
    fun `S049 RNF-T6 de la v19 a la v20 los puntos y proyectos siguen y los ejes quedan vacios`() {
        createFromExportedSchema(19)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v19 ->
            v19.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v19.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', " +
                    "'INSTANT', 1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.single().name shouldBe "P1"
            points.single().onAxis.shouldBeNull()
            runBlocking { RoomProjectRepository(db).project(project.id) }.shouldNotBeNull().name shouldBe project.name
            count(db, "alignments") shouldBe 0
            db.openHelper.readableDatabase.query("SELECT active_alignment_id FROM projects").use { cursor ->
                cursor.moveToFirst() shouldBe true
                cursor.isNull(0) shouldBe true
            }
            db.openHelper.readableDatabase.query("SELECT axis_name, axis_chainage, axis_offset FROM points").use { cursor ->
                cursor.moveToFirst() shouldBe true
                (0 until 3).all { cursor.isNull(it) } shouldBe true
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S054 RNF-T6 de la v20 a la v21 los puntos y los ejes siguen y las columnas nuevas quedan vacias`() {
        createFromExportedSchema(20)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v20 ->
            v20.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, 'a1')",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v20.execSQL(
                "INSERT INTO alignments (id, project_id, name, file_name, imported_at_ms, source, start_chainage, length_m, visible, " +
                    "min_e, max_e, min_n, max_n, geometry, profile) VALUES ('a1', ?, 'Eje A', NULL, 1, 'LANDXML', 0.0, 100.0, 1, " +
                    "0.0, 1.0, 0.0, 1.0, x'0102', NULL)",
                arrayOf<Any>(project.id),
            )
            v20.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at, " +
                    "axis_name, axis_chainage, axis_offset) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', " +
                    "'INSTANT', 1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000, 'Eje A', 150.5, -3.5)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.single().name shouldBe "P1"
            points.single().onAxis shouldBe AxisRef("Eje A", 150.5, -3.5)
            count(db, "alignments") shouldBe 1
            db.openHelper.readableDatabase.query(
                "SELECT stake_axis, stake_thickness, stake_d_chainage, axis_d_height, axis_height_ref FROM points",
            ).use { cursor ->
                cursor.moveToFirst() shouldBe true
                (0 until 5).all { cursor.isNull(it) } shouldBe true
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S045 RNF-T6 v21 a v22 las superficies siguen siendo TIN`() {
        createFromExportedSchema(21)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v21 ->
            v21.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', 's1', 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v21.execSQL(
                "INSERT INTO surfaces (id, project_id, name, file_name, imported_at_ms, source_crs, source_unit, status, min_e, max_e, " +
                    "min_n, max_n, min_z, max_z, vertex_count, triangle_count, hole_count) VALUES " +
                    "('s1', ?, 'terreno', 'obra.xml', 1, 'PROJECT', 'METRE', 'READY', 0.0, 10.0, 0.0, 10.0, 600.0, 610.0, 4, 2, 0)",
                arrayOf<Any>(project.id),
            )
            v21.execSQL(
                "INSERT INTO surface_tiles (surface_id, min_e, max_e, min_n, max_n, triangle_count, data) VALUES " +
                    "('s1', 0.0, 10.0, 0.0, 10.0, 2, x'0102')",
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            count(db, "surfaces") shouldBe 1
            count(db, "surface_tiles") shouldBe 1
            count(db, "raster_tiles") shouldBe 0
            db.openHelper.readableDatabase.query(
                "SELECT kind, height_system, value_type, column_count, row_count, origin_x, origin_y, step_x, step_y, data_cells, " +
                    "nodata_cells, triangle_count FROM surfaces",
            ).use { cursor ->
                cursor.moveToFirst() shouldBe true
                cursor.getString(0) shouldBe "TIN"
                (1..10).all { cursor.isNull(it) } shouldBe true
                cursor.getInt(11) shouldBe 2
            }
            val surfaces = runBlocking { RoomSurfaceRepository(db).surfaces(project.id).first() }
            surfaces.single().name shouldBe "terreno"
            surfaces.single().kind shouldBe SurfaceKind.TIN
            surfaces.single().raster shouldBe null
        } finally {
            db.close()
        }
    }

    @Test
    fun `S020 RNF-T6 v22 a v23 puntos sin shift y tabla nueva`() {
        createFromExportedSchema(22)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v22 ->
            v22.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v22.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', " +
                    "'INSTANT', 1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.single().name shouldBe "P1"
            points.single().measurement.shouldNotBeNull().coordinates.northing shouldBe 4474257.414123456
            count(db, "base_shifts") shouldBe 0
            db.openHelper.readableDatabase.query(
                "SELECT shift_id, shift_name, shift_dx, shift_dy, shift_dz, unshifted_ground_lat, unshifted_ground_lon, " +
                    "unshifted_ground_h FROM points",
            ).use { cursor ->
                cursor.moveToFirst() shouldBe true
                (0 until 8).all { cursor.isNull(it) } shouldBe true
            }
            val shift = BaseShiftEntity(
                id = "s1", projectId = project.id, createdAtMs = 5, fromReplay = false, knownName = "V-12", knownPointId = null,
                knownEasting = 440290.123456789, knownNorthing = 4474257.987654321, knownHeight = null, measuredLat = 40.4,
                measuredLon = -3.7, measuredH = 651.3, epochsUsed = 60, dx = -2.1, dy = -2.0, dz = 0.5, horizontalOnly = true,
                correctionsDatum = "ETRS89", active = true, baseLat = null, baseLon = null, baseH = null,
            )
            runBlocking {
                db.baseShifts().insert(shift)
                db.baseShifts().byId("s1") shouldBe shift
                db.baseShifts().all(project.id).first() shouldBe listOf(shift)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S017b RNF-T6 v23 a v24 los puntos conservan sus datos y las tablas de fotos nacen vacias`() {
        createFromExportedSchema(23)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v23 ->
            v23.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v23.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', " +
                    "'INSTANT', 1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.single().measurement.shouldNotBeNull().coordinates.northing shouldBe 4474257.414123456
            count(db, "point_photos") shouldBe 0
            count(db, "photo_events") shouldBe 0
            runBlocking {
                db.photos().insert(PointPhotoEntity("f1", "punto-1", "P1_1.jpg", "CAMERA", 1, 2, 300_000, 2_048, 1_536))
                db.photos().insertEvent(PhotoEventEntity("e1", "punto-1", 3, "ADDED", "P1_1.jpg", 1))
                db.photos().count("punto-1") shouldBe 1
                db.photos().photos("punto-1").first().single().name shouldBe "P1_1.jpg"
                db.photos().events("punto-1").first().single().action shouldBe "ADDED"
                // La cascada: borrar el punto se lleva las filas de sus fotos y sus sucesos.
                db.openHelper.writableDatabase.execSQL("DELETE FROM points WHERE id = 'punto-1'")
                count(db, "point_photos") shouldBe 0
                count(db, "photo_events") shouldBe 0
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S050 RNF-T6 v24 a v25 los ejes y puntos siguen y las tablas de secciones nacen vacias`() {
        createFromExportedSchema(24)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v24 ->
            v24.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v24.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', " +
                    "'INSTANT', 1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.single().measurement.shouldNotBeNull().coordinates.northing shouldBe 4474257.414123456
            points.single().onAxis shouldBe null
            count(db, "section_templates") shouldBe 0
            count(db, "section_profiles") shouldBe 0
            count(db, "superelevation_rows") shouldBe 0
            val axis = AlignmentEntity(
                "e1", project.id, "Eje A", null, 1, "MANUAL", 0.0, 100.0, true, 0.0, 1.0, 0.0, 1.0, false, ByteArray(1), null,
            )
            val template = SectionTemplateEntity("t1", "e1", 0.0, 100.0, null, byteArrayOf(1, 2), 5)
            val profile = SectionProfileEntity("s1", "e1", "DESIGN", 50.0, "RELATIVE", byteArrayOf(3), 6)
            val row = SuperelevationRowEntity("r1", "e1", 40.0, -0.06, 0.06, 7)
            runBlocking {
                db.alignments().insert(axis)
                db.sections().insertTemplate(template)
                db.sections().insertProfile(profile)
                db.sections().insertRow(row)
                db.sections().templates("e1").first() shouldBe listOf(template)
                db.sections().profiles("e1").first() shouldBe listOf(profile)
                db.sections().rows("e1").first() shouldBe listOf(row)
                // Un PK de peralte repetido en el mismo eje no entra.
                shouldThrow<SQLiteConstraintException> { db.sections().insertRow(row.copy(id = "r2")) }
                // La cascada: borrar el eje se lleva sus secciones y su peralte, y el punto sigue.
                db.alignments().delete("e1")
                count(db, "section_templates") shouldBe 0
                count(db, "section_profiles") shouldBe 0
                count(db, "superelevation_rows") shouldBe 0
                count(db, "points") shouldBe 1
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S051 RNF-T6 v25 a v26 los ejes y puntos siguen y los taludes nacen vacios`() {
        createFromExportedSchema(25)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v25 ->
            v25.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v25.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', " +
                    "'INSTANT', 1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.single().measurement.shouldNotBeNull().coordinates.northing shouldBe 4474257.414123456
            points.single().staked shouldBe null
            count(db, "slope_rules") shouldBe 0
            val axis = AlignmentEntity(
                "e1", project.id, "Eje A", null, 1, "MANUAL", 0.0, 100.0, true, 0.0, 1.0, 0.0, 1.0, false, ByteArray(1), null,
            )
            val rule = SlopeRuleEntity("r1", "e1", "RIGHT", 0.0, 100.0, 1.5, 2.0, "BD", 3.5, -0.07, "RELATIVE", 5)
            runBlocking {
                db.alignments().insert(axis)
                db.slopes().insert(rule)
                db.slopes().rules("e1").first() shouldBe listOf(rule)
                // La cascada: borrar el eje se lleva sus taludes y el punto sigue.
                db.alignments().delete("e1")
                count(db, "slope_rules") shouldBe 0
                count(db, "points") shouldBe 1
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S052 RNF-T6 v26 a v27 los ejes y puntos siguen y las tomas nacen vacias`() {
        createFromExportedSchema(26)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v26 ->
            v26.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v26.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', " +
                    "'INSTANT', 1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val points = runBlocking { RoomPointRepository(db).points(project.id).first() }
            points.single().measurement.shouldNotBeNull().coordinates.northing shouldBe 4474257.414123456
            count(db, "linear_surveys") shouldBe 0
            count(db, "linear_survey_points") shouldBe 0
            val axis = AlignmentEntity(
                "e1", project.id, "Eje A", null, 1, "FIELD", 0.0, 100.0, true, 0.0, 1.0, 0.0, 1.0, false, ByteArray(1), null,
            )
            val survey = LinearSurveyEntity("t1", project.id, "LONGITUDINAL", "e1", "Perfil", 0.0, 100.0, 10.0, null, true, 0.5, 5, null)
            val member = LinearSurveyPointEntity("t1", "punto-1", 0, true, 12.345678901234, -0.123456789012, null, "NONE", null)
            runBlocking {
                db.alignments().insert(axis)
                db.linearSurveys().insertSurvey(survey)
                db.linearSurveys().insertPoint(member)
                db.linearSurveys().surveys(project.id).first() shouldBe listOf(survey)
                db.linearSurveys().points("t1").first() shouldBe listOf(member)
                // Un punto no entra dos veces en la misma toma.
                shouldThrow<SQLiteConstraintException> { db.linearSurveys().insertPoint(member.copy(ordinal = 1)) }
                // Borrar el eje deja la toma sin eje y el punto sigue.
                db.alignments().delete("e1")
                db.linearSurveys().surveys(project.id).first() shouldBe listOf(survey.copy(alignmentId = null))
                count(db, "points") shouldBe 1
                // Purgar el punto lo quita de la toma, pero la toma sigue.
                db.openHelper.writableDatabase.execSQL("DELETE FROM points WHERE id = 'punto-1'")
                count(db, "linear_survey_points") shouldBe 0
                count(db, "linear_surveys") shouldBe 1
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S023 RNF-T6 v27 a v28 los proyectos y puntos siguen y las bibliotecas nacen vacias`() {
        createFromExportedSchema(27)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v27 ->
            v27.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            runBlocking { RoomProjectRepository(db).project(project.id).shouldNotBeNull().name shouldBe project.name }
            listOf("code_libraries", "library_codes", "code_attributes", "attribute_options").forEach { count(db, it) shouldBe 0 }

            val dao = db.codeLibraries()
            runBlocking {
                dao.insertLibrary(CodeLibraryEntity("l1", "Mis codigos", "mis codigos", null, null, 1))
                dao.insertLibrary(CodeLibraryEntity("l2", "Copia", "copia", project.id, "Mis codigos", 2))
                dao.insertCode(LibraryCodeEntity("c1", "l2", "ARQ", "arq", "Arqueta", "POINT", 0x1E88E5))
                dao.insertAttribute(CodeAttributeEntity("a1", "c1", 0, "Material", "LIST", "PVC"))
                dao.insertOption(AttributeOptionEntity("a1", 0, "PVC"))
                dao.insertOption(AttributeOptionEntity("a1", 1, "Hormigón"))
                dao.copyOf(project.id).shouldNotBeNull().sourceName shouldBe "Mis codigos"
                dao.userLibraries().first().map { it.id } shouldBe listOf("l1")
                // Un proyecto tiene como mucho una copia, y un código no se repite en su biblioteca.
                shouldThrow<SQLiteConstraintException> { dao.insertLibrary(CodeLibraryEntity("l3", "Otra", "otra", project.id, null, 3)) }
                shouldThrow<SQLiteConstraintException> {
                    dao.insertCode(LibraryCodeEntity("c2", "l2", "arq", "arq", "", "LINE", 0))
                }
                // Dos bibliotecas del usuario sí pueden tener el mismo código.
                dao.insertCode(LibraryCodeEntity("c3", "l1", "ARQ", "arq", "", "POINT", 0))
                // Borrar el proyecto borra su copia entera, y la biblioteca del usuario sigue.
                db.openHelper.writableDatabase.execSQL("DELETE FROM projects WHERE id = ?", arrayOf<Any>(project.id))
                count(db, "code_libraries") shouldBe 1
                count(db, "library_codes") shouldBe 1
                count(db, "code_attributes") shouldBe 0
                count(db, "attribute_options") shouldBe 0
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S024 RNF-T6 v28 a v29 los puntos y las bibliotecas siguen y el codigo de los puntos nace vacio`() {
        createFromExportedSchema(28)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v28 ->
            v28.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v28.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', " +
                    "'INSTANT', 1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
            v28.execSQL("INSERT INTO code_libraries VALUES ('l1', 'Mis codigos', 'mis codigos', NULL, NULL, 1)")
            v28.execSQL("INSERT INTO library_codes VALUES ('c1', 'l1', 'ARQ', 'arq', 'Arqueta', 'POINT', 2000000)")
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val point = runBlocking { RoomPointRepository(db).points(project.id).first() }.single()
            point.measurement.shouldNotBeNull().coordinates.northing shouldBe 4474257.414123456
            count(db, "point_attributes") shouldBe 0
            count(db, "point_history_attributes") shouldBe 0
            // Los codigos de antes siguen y toman el circulo; los puntos no llevan codigo.
            val sql = db.openHelper.writableDatabase
            sql.query("SELECT symbol FROM library_codes WHERE id = 'c1'").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "CIRCLE"
            }
            sql.query("SELECT code, code_description, code_color, code_symbol FROM points").use { c ->
                c.moveToFirst() shouldBe true
                (0..3).forEach { c.isNull(it) shouldBe true }
            }

            // Los codigos del proyecto son una biblioteca mas, unica por proyecto, que se borra con el.
            sql.execSQL("INSERT INTO code_libraries VALUES ('l2', 'Proyecto', 'proyecto', NULL, NULL, 2, ?)", arrayOf<Any>(project.id))
            shouldThrow<SQLiteConstraintException> {
                sql.execSQL("INSERT INTO code_libraries VALUES ('l3', 'Otra', 'otra', NULL, NULL, 3, ?)", arrayOf<Any>(project.id))
            }
            sql.execSQL("INSERT INTO point_attributes VALUES ('punto-1', 0, 'Diametro', 'NUMBER', '0.123456789012345')")
            shouldThrow<SQLiteConstraintException> {
                sql.execSQL("INSERT INTO point_attributes VALUES ('punto-1', 0, 'Otro', 'TEXT', 'x')")
            }
            sql.execSQL("DELETE FROM points WHERE id = 'punto-1'")
            count(db, "point_attributes") shouldBe 0
            sql.execSQL("DELETE FROM projects WHERE id = ?", arrayOf<Any>(project.id))
            count(db, "code_libraries") shouldBe 1
        } finally {
            db.close()
        }
    }

    @Test
    fun `S036 RNF-T6 v31 a v32 los proyectos siguen y las tablas WMS nacen vacias`() {
        createFromExportedSchema(31)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v31 ->
            v31.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            count(db, "projects") shouldBe 1
            count(db, "wms_services") shouldBe 0
            count(db, "wms_layers") shouldBe 0
            count(db, "project_wms_layers") shouldBe 0
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO wms_services VALUES ('s1', 'Servicio', 'https://x.es/wms', 'https://x.es/wms', 'T', '1.3.0', '', NULL, " +
                    "'TILES', 'image/png', 'text/plain', 1, 1)",
            )
            count(db, "wms_services") shouldBe 1
        } finally {
            db.close()
        }
    }

    @Test
    fun `S027 RNF-T6 v30 a v31 los puntos y las lineas siguen y el replanteo de linea nace vacio`() {
        createFromExportedSchema(30)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v30 ->
            v30.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v30.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', " +
                    "'INSTANT', 1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
            v30.execSQL(
                "INSERT INTO project_lines VALUES ('l1', ?, 'BORD_1', 'LINE', 0, 0, 0, 'BORD', '', 0, 'CIRCLE', 1, NULL, '')",
                arrayOf<Any>(project.id),
            )
            v30.execSQL("INSERT INTO line_vertices VALUES ('l1', 0, 'punto-1')")
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val point = runBlocking { RoomPointRepository(db).points(project.id).first() }.single()
            point.measurement.shouldNotBeNull().coordinates.northing shouldBe 4474257.414123456
            point.staked shouldBe null
            count(db, "project_lines") shouldBe 1
            count(db, "line_vertices") shouldBe 1
            db.openHelper.writableDatabase.query("SELECT stake_line_id, stake_line_name FROM points").use { cursor ->
                cursor.moveToFirst() shouldBe true
                cursor.isNull(0) shouldBe true
                cursor.isNull(1) shouldBe true
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `S025 RNF-T6 v29 a v30 los puntos siguen y las lineas nacen vacias con sus reglas`() {
        createFromExportedSchema(29)
        SQLiteDatabase.openDatabase(context.getDatabasePath(TEST_DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v29 ->
            v29.execSQL(
                "INSERT INTO projects VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, 'ORTHOMETRIC', NULL, 0, NULL, NULL, NULL)",
                arrayOf<Any>(
                    project.id, project.name, project.author, project.description, project.crs, project.linearUnit,
                    project.angularUnit, project.correctionsDatum, project.createdAt, project.modifiedAt,
                ),
            )
            v29.execSQL(
                "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                    "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, " +
                    "mode, epochs_used, epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) VALUES " +
                    "(?, ?, 'P1', '', 'MEASURED', 40.416801234567891, -3.7037123456789, 701.357, 1.8, 40.416801234567891, " +
                    "-3.7037123456789, 699.423, 440290.434567891, 4474257.414123456, 648.340987654, 'FIX', 24, '', " +
                    "'INSTANT', 1, 0, 0, '\$GNGGA,1*00', 'VERTICAL', 0, 2000)",
                arrayOf<Any>("punto-1", project.id),
            )
        }

        val db = TopoPhoneDatabase.build(context, TEST_DB)
        try {
            db.openHelper.writableDatabase.version shouldBe CURRENT_VERSION
            val point = runBlocking { RoomPointRepository(db).points(project.id).first() }.single()
            point.measurement.shouldNotBeNull().coordinates.northing shouldBe 4474257.414123456
            listOf(
                "project_lines",
                "line_vertices",
                "line_attributes",
                "line_history",
                "line_history_attributes",
                "line_history_vertices",
            ).forEach { count(db, it) shouldBe 0 }

            val sql = db.openHelper.writableDatabase
            sql.execSQL(
                "INSERT INTO project_lines VALUES ('l1', ?, 'BORD_1', 'LINE', 0, 0, 1, 'BORD', '', 0, 'CIRCLE', 1, NULL, '')",
                arrayOf<Any>(project.id),
            )
            sql.execSQL("INSERT INTO line_vertices VALUES ('l1', 0, 'punto-1')")
            // El nombre es unico entre las lineas activas del proyecto, y la papelera lo deja libre.
            shouldThrow<SQLiteConstraintException> {
                sql.execSQL(
                    "INSERT INTO project_lines VALUES ('l2', ?, 'BORD_1', 'LINE', 0, 0, 0, NULL, NULL, NULL, NULL, 2, NULL, '')",
                    arrayOf<Any>(project.id),
                )
            }
            sql.execSQL(
                "INSERT INTO project_lines VALUES ('l3', ?, 'BORD_1', 'LINE', 0, 0, 0, NULL, NULL, NULL, NULL, 3, 9, 'l3')",
                arrayOf<Any>(project.id),
            )
            // Un vertice es un solo punto en una sola posicion de la linea.
            shouldThrow<SQLiteConstraintException> { sql.execSQL("INSERT INTO line_vertices VALUES ('l1', 0, 'punto-1')") }
            // Borrar el punto quita su vertice; borrar la linea quita sus filas; borrar el proyecto quita las lineas.
            sql.execSQL("DELETE FROM points WHERE id = 'punto-1'")
            count(db, "line_vertices") shouldBe 0
            count(db, "project_lines") shouldBe 2
            sql.execSQL("DELETE FROM projects WHERE id = ?", arrayOf<Any>(project.id))
            count(db, "project_lines") shouldBe 0
        } finally {
            db.close()
        }
    }

    private companion object {
        const val TEST_DB = "topophone-test.db"
        const val CURRENT_VERSION = 32

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
