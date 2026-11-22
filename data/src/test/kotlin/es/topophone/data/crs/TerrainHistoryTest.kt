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
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.TerrainFrame
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** El plano de terreno en el historial del proyecto: cambiar, deshacer y cambiar el SC con el plano activo (S058/RF-6, RF-19, RF-20). */
@RunWith(AndroidJUnit4::class)
class TerrainHistoryTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private var now = 10_000L
    private val history = RoomProjectCrsHistoryRepository(db, clock = { now++ })
    private val calculator = ProjectPositionCalculator()
    private val plane = TerrainPlane(41.3851, 2.1734, 0.9996)
    private val barcelona = GeodeticPosition(41.3851, 2.1734, 12.0)

    @After
    fun tearDown() = db.close()

    private fun createProject(): String = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(name = "Obra", crs = SpainCrs.ETRS89_UTM30, heightSystem = HeightSystem.ELLIPSOIDAL),
        )
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun addPoint(projectId: String) {
        val position = calculator.fromGround(barcelona, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)
        insert(
            db.openHelper.writableDatabase,
            mapOf(
                "id" to "p1",
                "project_id" to projectId,
                "name" to "p1",
                "ground_lat" to barcelona.latitudeDeg,
                "ground_lon" to barcelona.longitudeDeg,
                "ground_h" to barcelona.ellipsoidalHeightM,
                "easting" to position.easting,
                "northing" to position.northing,
                "height" to position.height,
            ),
        )
    }

    private fun insert(sqlite: SupportSQLiteDatabase, values: Map<String, Any?>) {
        val columns = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        sqlite.query("PRAGMA table_info(points)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                val type = c.getString(2).uppercase()
                if (name in values) {
                    columns += name
                    args += values.getValue(name)
                } else if (c.getInt(3) == 1 && c.isNull(4)) {
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
        sqlite.execSQL("INSERT INTO points (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})", args.toTypedArray())
    }

    private fun pointColumns(): List<Double?> =
        db.openHelper.readableDatabase.query("SELECT easting, northing, height, ground_lat, ground_lon FROM points").use {
            it.moveToFirst() shouldBe true
            (0..4).map { i -> if (it.isNull(i)) null else it.getDouble(i) }
        }

    private suspend fun apply(id: String, terrain: TerrainPlane?, crs: es.topophone.core.model.project.ProjectCrs = SpainCrs.ETRS89_UTM30) =
        history.apply(id, crs, HeightSystem.ELLIPSOIDAL, TerrainChange.Set(terrain))

    @Test
    fun `S058 RF-19 pasar a terreno y cambiar el factor quedan en el historial`() = runBlocking<Unit> {
        val id = createProject()

        apply(id, plane).shouldBeInstanceOf<CrsChangeResult.Applied>()
        apply(id, plane.copy(factor = 1.0002)).shouldBeInstanceOf<CrsChangeResult.Applied>()

        val changes = history.observe(id).first()
        changes.size shouldBe 2
        changes[1].fromTerrain.shouldBeNull()
        changes[1].toTerrain shouldBe plane
        changes[0].fromTerrain shouldBe plane
        changes[0].toTerrain shouldBe plane.copy(factor = 1.0002)
        changes.all { it.kind == CrsChangeKind.CHANGE } shouldBe true
        projects.project(id)!!.terrain shouldBe plane.copy(factor = 1.0002)
    }

    @Test
    fun `S058 RF-20 un cambio solo de plano no toca ninguna columna de los puntos`() = runBlocking<Unit> {
        val id = createProject()
        addPoint(id)
        val before = pointColumns()

        apply(id, plane).shouldBeInstanceOf<CrsChangeResult.Applied>()
        apply(id, plane.copy(factor = 1.0003)).shouldBeInstanceOf<CrsChangeResult.Applied>()
        apply(id, null).shouldBeInstanceOf<CrsChangeResult.Applied>()

        pointColumns() shouldBe before
    }

    @Test
    fun `S058 RF-19 deshacer devuelve el plano anterior y volver a deshacer el de antes`() = runBlocking<Unit> {
        val id = createProject()
        apply(id, plane)

        history.undo(id).shouldBeInstanceOf<CrsChangeResult.Applied>()
        projects.project(id)!!.terrain.shouldBeNull()
        history.observe(id).first().first().kind shouldBe CrsChangeKind.UNDO

        history.undo(id).shouldBeInstanceOf<CrsChangeResult.Applied>()
        projects.project(id)!!.terrain shouldBe plane
    }

    @Test
    fun `S058 RF-19 el mismo plano no es un cambio y sin plano y sin tocarlo tampoco`() = runBlocking<Unit> {
        val id = createProject()

        history.apply(id, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL) shouldBe CrsChangeResult.NoChange
        apply(id, plane)
        apply(id, plane) shouldBe CrsChangeResult.NoChange
        history.apply(id, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL) shouldBe CrsChangeResult.NoChange // Keep: sigue el plano
        history.observe(id).first().size shouldBe 1
    }

    @Test
    fun `S058 RF-6 al cambiar el SC el origen sigue en el mismo punto`() = runBlocking<Unit> {
        val id = createProject()
        apply(id, plane)
        val utm31 = LibraryCrs(
            (CrsCatalog.bundled.build(25831, CorrectionsDatum.ETRS89, GeoPoint(41.4, 2.2)) as CrsBuildResult.Ready).definition,
            CorrectionsDatum.ETRS89,
        )

        history.apply(id, utm31, HeightSystem.ELLIPSOIDAL).shouldBeInstanceOf<CrsChangeResult.Applied>()

        val project = projects.project(id).shouldNotBeNull()
        project.crs shouldBe utm31
        project.terrain shouldBe plane
        val before = TerrainFrame.of(plane, SpainCrs.ETRS89_UTM30).shouldNotBeNull()
        val after = TerrainFrame.of(plane, utm31).shouldNotBeNull()
        (after.originEasting != before.originEasting) shouldBe true
        after.factor shouldBe plane.factor
        val last = history.observe(id).first().first()
        last.fromTerrain shouldBe plane
        last.toTerrain shouldBe plane
        // El origen es el mismo punto del suelo: su cuadricula en el SC nuevo es la de pyproj para EPSG:25831.
        val expected = calculator.fromGround(
            GeodeticPosition(plane.originLatitudeDeg, plane.originLongitudeDeg, null),
            utm31,
            HeightSystem.ELLIPSOIDAL,
        )
        after.originEasting shouldBe (expected.easting!! plusOrMinus 1e-9)
    }

    @Test
    fun `S058 RF-2 un plano que no vale en el SC nuevo no se aplica`() = runBlocking<Unit> {
        val id = createProject()
        val madrid = TerrainPlane(40.4, -3.7, 1.0)
        val lambert93 = LibraryCrs(
            (CrsCatalog.bundled.build(2154, CorrectionsDatum.ETRS89, GeoPoint(48.0, 2.0)) as CrsBuildResult.Ready).definition,
            CorrectionsDatum.ETRS89,
        )

        apply(id, madrid, lambert93) shouldBe CrsChangeResult.InvalidTerrain // Madrid queda fuera de Lambert-93
        history.apply(id, SpainCrs.ETRS89_GEOGRAPHIC, HeightSystem.ELLIPSOIDAL, TerrainChange.Set(plane)) shouldBe
            CrsChangeResult.InvalidTerrain

        projects.project(id)!!.terrain.shouldBeNull()
        history.observe(id).first() shouldBe emptyList()
    }

    @Test
    fun `S058 RF-19 deshacer no se puede si el plano se cambio por otro camino`() = runBlocking<Unit> {
        val id = createProject()
        apply(id, plane)
        projects.update(id, projects.project(id)!!.toDraft().copy(terrain = plane.copy(factor = 1.01)))

        history.undo(id) shouldBe CrsChangeResult.NothingToUndo
    }
}
