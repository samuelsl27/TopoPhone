package es.topophone.app.ui.lines

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.RoomCollectorCodeSource
import es.topophone.app.ui.map.MapLines
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSummary
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.codes.DefaultLibrarySource
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Las líneas con muchos vértices: escribir el mapa de 1.000 líneas y abrir la ficha de una de 100.000 vértices (S025, RNF-T13). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LinesPerformanceTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun summary(i: Int) = PointSummary(
        id = "p$i",
        name = "P$i",
        description = "",
        origin = PointOrigin.MANUAL,
        latitudeDeg = 40.0 + i * 1e-6,
        longitudeDeg = -3.0 + i * 1e-6,
        hasHeight = false,
        outOfTolerance = false,
        edited = false,
        hidden = false,
        createdAtMs = 0,
    )

    @Test
    fun `S025 RNF-T13 el GeoJSON de 1000 lineas con 100000 vertices se escribe en menos de 1 s, fuera del hilo principal`() {
        val points = List(100_000) { summary(it) }
        val code = PointCode("BORD", "", 0x1E88E5, CodeSymbol.CIRCLE)
        val lines = List(1_000) { l ->
            ProjectLine(
                id = "l$l",
                projectId = "p",
                name = "BORD_$l",
                code = code,
                geometry = CodeGeometry.LINE,
                closed = l % 10 == 0,
                hidden = false,
                active = false,
                vertices = List(100) { LineVertex("p${l * 100 + it}") },
                createdAtMs = 0,
            )
        }
        MapLines.scene(lines.take(10), points) // calienta

        val start = System.nanoTime()
        val scene = MapLines.scene(lines, points).shouldNotBeNull()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        scene.lines + scene.polygons shouldBe 1_000
        (elapsedMs < 1_000) shouldBe true
    }

    @Test
    fun `S025 la ficha de una linea de 100000 vertices se abre en menos de 1 s`() {
        val projects = RoomProjectRepository(db)
        val projectId =
            runBlocking { projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id }
        val sql = db.openHelper.writableDatabase
        val numbers = "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 100000) "
        sql.execSQL(
            numbers + "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, mode, epochs_used, " +
                "epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) " +
                "SELECT 'p' || i, ?, 'P' || i, '', 'MEASURED', 40.4, -3.7, 701.0, 1.8, 40.4, -3.7, 699.0, " +
                "440000.0 + i * 0.01, 4472000.0, 648.0, 'FIX', 24, '', 'INSTANT', 1, 0, 0, '', 'VERTICAL', 0, i FROM n",
            arrayOf<Any>(projectId),
        )
        sql.execSQL(
            "INSERT INTO project_lines VALUES ('big', ?, 'BORD_1', 'LINE', 0, 0, 0, 'BORD', '', 0, 'CIRCLE', 1, NULL, '')",
            arrayOf<Any>(projectId),
        )
        sql.execSQL(numbers + "INSERT INTO line_vertices SELECT 'big', i - 1, 'p' || i FROM n")
        val points = RoomPointRepository(db)
        val lines = RoomLineRepository(db, points)
        val libraries = RoomCodeLibraryRepository(db, DefaultLibrarySource { emptyList() })
        val vm = LineDetailsViewModel(lines, points, projects, RoomCollectorCodeSource(libraries, points))
        try {
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            val start = System.nanoTime()
            vm.start(projectId, "big")

            val ui = runBlocking { withTimeout(20_000) { vm.state.first { it.vertices.size == 100_000 } } }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            collector.cancel()

            ui.vertices.last().chainageM.shouldNotBeNull()
            (elapsedMs < 1_000) shouldBe true
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
