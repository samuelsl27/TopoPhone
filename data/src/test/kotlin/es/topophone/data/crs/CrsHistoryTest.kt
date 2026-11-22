package es.topophone.data.crs

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsChangeKind
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Cambiar el SC de un proyecto con puntos: lo derivado se recalcula, las geodésicas no, todo en una transacción y con su historial (S035/RF-17 a RF-20). */
@RunWith(AndroidJUnit4::class)
class CrsHistoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private var now = 10_000L
    private var counter = 0
    private val history = RoomProjectCrsHistoryRepository(db, clock = { now++ }, ids = { "h-${counter++}" })
    private val calculator = ProjectPositionCalculator()

    @After
    fun tearDown() = db.close()

    private fun library(epsg: Int, at: GeoPoint = GeoPoint(40.4, -3.7)): LibraryCrs {
        val definition = when (val r = CrsCatalog.bundled.build(epsg, CorrectionsDatum.ETRS89, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            is CrsBuildResult.Unavailable -> error("EPSG:$epsg: $r")
        }
        return LibraryCrs(definition, CorrectionsDatum.ETRS89)
    }

    private fun createProject(crs: ProjectCrs = SpainCrs.ETRS89_UTM30, heightSystem: HeightSystem = HeightSystem.ELLIPSOIDAL): String =
        runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Obra", crs = crs, heightSystem = heightSystem))
                .shouldBeInstanceOf<SaveResult.Saved>().id
        }

    /** Un punto con su posición del suelo y lo derivado en [crs]; con [measured] también las coordenadas de su medida. */
    private fun addPoint(
        projectId: String,
        id: String,
        ground: GeodeticPosition,
        crs: ProjectCrs,
        measured: Boolean = false,
        trashed: Boolean = false,
    ) {
        val position = calculator.fromGround(ground, crs, HeightSystem.ELLIPSOIDAL)
        val sqlite = db.openHelper.writableDatabase
        val values = mutableMapOf<String, Any?>(
            "id" to id,
            "project_id" to projectId,
            "name" to id,
            "ground_lat" to ground.latitudeDeg,
            "ground_lon" to ground.longitudeDeg,
            "ground_h" to ground.ellipsoidalHeightM,
            "easting" to position.easting,
            "northing" to position.northing,
            "height" to position.height,
        )
        if (measured) {
            values["measured_ground_lat"] = ground.latitudeDeg
            values["measured_ground_lon"] = ground.longitudeDeg
            values["measured_ground_h"] = ground.ellipsoidalHeightM
            values["measured_easting"] = position.easting
            values["measured_northing"] = position.northing
            values["measured_height"] = position.height
        }
        if (trashed) {
            values["deleted_at"] = 5L
            values["trash_key"] = id
        }
        insertMinimal(sqlite, "points", values)
    }

    private fun insertMinimal(sqlite: SupportSQLiteDatabase, table: String, values: Map<String, Any?>) {
        val columns = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        sqlite.query("PRAGMA table_info($table)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                val type = c.getString(2).uppercase()
                val notNull = c.getInt(3) == 1
                val hasDefault = !c.isNull(4)
                if (name in values) {
                    columns += name
                    args += values.getValue(name)
                } else if (notNull && !hasDefault) {
                    columns += name
                    args += if ("INT" in type) {
                        0
                    } else if ("REAL" in type) {
                        0.0
                    } else {
                        "x"
                    }
                }
            }
        }
        sqlite.execSQL("INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})", args.toTypedArray())
    }

    private data class Row(
        val e: Double?,
        val n: Double?,
        val h: Double?,
        val me: Double?,
        val mn: Double?,
        val lat: Double,
        val lon: Double,
    )

    private fun row(id: String): Row = db.openHelper.readableDatabase.query(
        "SELECT easting, northing, height, measured_easting, measured_northing, ground_lat, ground_lon FROM points WHERE id = '$id'",
    ).use { c ->
        c.moveToFirst() shouldBe true
        fun d(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
        Row(d(0), d(1), d(2), d(3), d(4), c.getDouble(5), c.getDouble(6))
    }

    private val barcelona = GeodeticPosition(41.3851123456789, 2.1734123456789, 12.123456789)

    @Test
    fun `S035 RF-18 cambiar el SC recalcula lo derivado de cada punto y no toca sus geodesicas`() = runBlocking<Unit> {
        val id = createProject()
        addPoint(id, "p1", barcelona, SpainCrs.ETRS89_UTM30, measured = true)
        val target = library(25831, GeoPoint(41.4, 2.2)) // ETRS89 / UTM 31N, del mismo datum

        val result = history.apply(id, target, HeightSystem.ELLIPSOIDAL)

        result.shouldBeInstanceOf<CrsChangeResult.Applied>()
        val expected = calculator.fromGround(barcelona, target, HeightSystem.ELLIPSOIDAL)
        val r = row("p1")
        r.e.shouldNotBeNull() shouldBe (expected.easting!! plusOrMinus 1e-9)
        r.n.shouldNotBeNull() shouldBe (expected.northing!! plusOrMinus 1e-9)
        r.me.shouldNotBeNull() shouldBe (expected.easting!! plusOrMinus 1e-9) // las de la medida tambien
        r.lat shouldBe barcelona.latitudeDeg // las geodesicas, sin tocar y sin redondear
        r.lon shouldBe barcelona.longitudeDeg
        projects.project(id)!!.crs shouldBe target
    }

    @Test
    fun `S035 RF-18 los puntos de la papelera tambien se recalculan`() = runBlocking<Unit> {
        val id = createProject()
        addPoint(id, "p1", barcelona, SpainCrs.ETRS89_UTM30, trashed = true)
        val target = library(25831, GeoPoint(41.4, 2.2))

        history.apply(id, target, HeightSystem.ELLIPSOIDAL)

        row("p1").e.shouldNotBeNull() shouldBe
            (calculator.fromGround(barcelona, target, HeightSystem.ELLIPSOIDAL).easting!! plusOrMinus 1e-9)
    }

    @Test
    fun `S035 RF-20 el cambio queda en el historial con el SC anterior y el nuevo y las alturas`() = runBlocking<Unit> {
        val id = createProject(heightSystem = HeightSystem.ORTHOMETRIC)
        val target = library(25831, GeoPoint(41.4, 2.2))

        history.apply(id, target, HeightSystem.ELLIPSOIDAL).shouldBeInstanceOf<CrsChangeResult.Applied>()

        val entries = history.observe(id).first()
        entries.size shouldBe 1
        entries.single().from shouldBe SpainCrs.ETRS89_UTM30
        entries.single().to shouldBe target
        entries.single().fromHeightSystem shouldBe HeightSystem.ORTHOMETRIC
        entries.single().toHeightSystem shouldBe HeightSystem.ELLIPSOIDAL
        entries.single().kind shouldBe CrsChangeKind.CHANGE
        projects.project(id)!!.heightSystem shouldBe HeightSystem.ELLIPSOIDAL
    }

    @Test
    fun `S035 RF-17 un SC de otro datum no se aplica y no cambia nada`() = runBlocking<Unit> {
        val id = createProject()
        addPoint(id, "p1", barcelona, SpainCrs.ETRS89_UTM30)
        val before = row("p1")

        history.apply(id, SpainCrs.ED50_UTM30, HeightSystem.ELLIPSOIDAL) shouldBe CrsChangeResult.OtherDatum

        projects.project(id)!!.crs shouldBe SpainCrs.ETRS89_UTM30
        row("p1") shouldBe before
        history.observe(id).first() shouldBe emptyList()
    }

    @Test
    fun `S035 RF-20 el mismo SC y las mismas alturas no son un cambio y un proyecto que no existe se dice`() = runBlocking<Unit> {
        val id = createProject()

        history.apply(id, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL) shouldBe CrsChangeResult.NoChange
        history.apply("no-existe", SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL) shouldBe CrsChangeResult.ProjectNotFound
        history.observe(id).first() shouldBe emptyList()
    }

    @Test
    fun `S035 RF-19 un punto fuera del ambito del SC nuevo queda sin coordenadas y la vista previa lo senala`() = runBlocking<Unit> {
        val id = createProject()
        val madrid = GeodeticPosition(40.4168, -3.7038, 667.0) // al sur del ambito de Lambert-93 (latitud 41,15)
        addPoint(id, "p1", madrid, SpainCrs.ETRS89_UTM30)
        val lambert93 = library(2154, GeoPoint(48.0, 2.0))

        val plan = history.preview(id, lambert93, HeightSystem.ELLIPSOIDAL).shouldNotBeNull()

        plan.issues.map { it.id } shouldBe listOf("p1")
        history.apply(id, lambert93, HeightSystem.ELLIPSOIDAL).shouldBeInstanceOf<CrsChangeResult.Applied>()
        row("p1").e.shouldBeNull()
        row("p1").lat shouldBe madrid.latitudeDeg
    }

    @Test
    fun `S035 RNF un fallo a mitad del cambio no deja nada a medias`() = runBlocking<Unit> {
        val id = createProject()
        addPoint(id, "p1", barcelona, SpainCrs.ETRS89_UTM30)
        addPoint(id, "p2", GeodeticPosition(41.0, 2.0, 500.0), SpainCrs.ETRS89_UTM30)
        val before = listOf(row("p1"), row("p2"))
        // El identificador de la fila del historial se pide al final, tras cambiar el SC y reescribir los puntos: si falla, todo debe deshacerse.
        val broken = RoomProjectCrsHistoryRepository(db, clock = { now++ }, ids = { error("fallo simulado") })

        runCatching { broken.apply(id, library(25831), HeightSystem.ELLIPSOIDAL) }.isFailure shouldBe true

        projects.project(id)!!.crs shouldBe SpainCrs.ETRS89_UTM30
        listOf(row("p1"), row("p2")) shouldBe before
        history.observe(id).first() shouldBe emptyList()
    }

    @Test
    fun `S035 RNF 10000 puntos se recalculan en menos de 2 s`() = runBlocking<Unit> {
        val id = createProject()
        val sqlite = db.openHelper.writableDatabase
        sqlite.beginTransaction()
        try {
            repeat(10_000) { i ->
                val ground = GeodeticPosition(41.0 + (i % 100) * 0.001, 2.0 + (i / 100) * 0.001, 600.0)
                addPoint(id, "q$i", ground, SpainCrs.ETRS89_UTM30)
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
        val target = library(25831, GeoPoint(41.4, 2.2))

        val millis = measureTimeMillis { history.apply(id, target, HeightSystem.ELLIPSOIDAL).shouldBeInstanceOf<CrsChangeResult.Applied>() }

        millis shouldBeLessThan 2_000L
    }

    @Test
    fun `S035 RF-21 volver al SC anterior deshace el cambio, recalcula los puntos y queda como un cambio mas`() = runBlocking<Unit> {
        val id = createProject(heightSystem = HeightSystem.ORTHOMETRIC)
        addPoint(id, "p1", barcelona, SpainCrs.ETRS89_UTM30, measured = true)
        val original = row("p1")
        history.apply(id, library(25831, GeoPoint(41.4, 2.2)), HeightSystem.ELLIPSOIDAL)

        val result = history.undo(id)

        result.shouldBeInstanceOf<CrsChangeResult.Applied>().change.kind shouldBe CrsChangeKind.UNDO
        projects.project(id)!!.crs shouldBe SpainCrs.ETRS89_UTM30
        projects.project(id)!!.heightSystem shouldBe HeightSystem.ORTHOMETRIC
        row("p1").e.shouldNotBeNull() shouldBe (original.e!! plusOrMinus 1e-9)
        row("p1").me.shouldNotBeNull() shouldBe (original.me!! plusOrMinus 1e-9)
        history.observe(id).first().map { it.kind } shouldBe listOf(CrsChangeKind.UNDO, CrsChangeKind.CHANGE)
    }

    @Test
    fun `S035 RF-21 deshacer otra vez vuelve al SC de antes de deshacer y el historial lleva tres filas`() = runBlocking<Unit> {
        val id = createProject()
        val target = library(25831, GeoPoint(41.4, 2.2))
        history.apply(id, target, HeightSystem.ELLIPSOIDAL)
        history.undo(id)

        history.undo(id).shouldBeInstanceOf<CrsChangeResult.Applied>()

        projects.project(id)!!.crs shouldBe target
        history.observe(id).first().map { it.kind } shouldBe listOf(CrsChangeKind.UNDO, CrsChangeKind.UNDO, CrsChangeKind.CHANGE)
    }

    @Test
    fun `S035 RF-21 sin historial, o con el SC cambiado por otro camino, no hay nada que deshacer`() = runBlocking<Unit> {
        val id = createProject()
        history.undo(id) shouldBe CrsChangeResult.NothingToUndo
        history.undo("no-existe") shouldBe CrsChangeResult.ProjectNotFound

        history.apply(id, library(25831, GeoPoint(41.4, 2.2)), HeightSystem.ELLIPSOIDAL)
        projects.update(id, projects.project(id)!!.toDraft().copy(crs = SpainCrs.ETRS89_UTM29))

        history.undo(id) shouldBe CrsChangeResult.NothingToUndo
    }

    @Test
    fun `S035 RF-11 se puede volver a un SC personalizado que ya se borro de Mis SC`() = runBlocking<Unit> {
        val mine = RoomCustomCrsRepository(db)
        val definition = library(25830).definition.copy(epsg = 0, name = "Obra")
        val custom = (mine.create(definition, es.topophone.core.model.project.CustomHeight.Ellipsoidal) as CustomCrsSave.Saved).crs
        val id = createProject(custom.toProjectCrs(CorrectionsDatum.ETRS89))
        history.apply(id, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)
        mine.delete(custom.id)

        history.undo(id).shouldBeInstanceOf<CrsChangeResult.Applied>()

        projects.project(id)!!.crs shouldBe custom.toProjectCrs(CorrectionsDatum.ETRS89)
    }
}
