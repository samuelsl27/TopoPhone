package es.topophone.app.ui.geometry

import android.content.Context
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.geometry.GeometryCalculator
import es.topophone.core.survey.geometry.GeometryResult
import es.topophone.core.survey.geometry.GeometryVertex
import es.topophone.core.survey.geometry.GroundScale
import es.topophone.core.survey.geometry.SegmentScale
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.geometry.GeometryPreferences
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Una línea de 100.000 vértices: la información en menos de 1 s y la tabla perezosa sin componer todas las filas (S026, RNF-T13). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GeometryPerformanceTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private class FakePreferences(mode: DistanceMode) : GeometryPreferences {
        val mode = MutableStateFlow(mode)
        override val distanceMode = this.mode

        override suspend fun setDistanceMode(mode: DistanceMode) {
            this.mode.value = mode
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private val rowMatcher = androidx.compose.ui.test.SemanticsMatcher("fila de la tabla") { node ->
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("geometry_segment_") == true
    }

    private fun vertices(count: Int) = List(count) { i ->
        GeometryVertex(
            easting = 440_000.0 + i * 0.5,
            northing = 4_472_000.0 + (i % 2) * 0.3,
            height = 650.0 + (i % 7) * 0.1,
            latitudeDeg = 40.4 + (i % 2) * 2.7e-6,
            longitudeDeg = -3.7 + i * 5.8e-6,
            ellipsoidalHeightM = 700.0 + (i % 7) * 0.1,
        )
    }

    @Test
    fun `S026 RNF-T13 la informacion de 100000 vertices en cuadricula se calcula en menos de 1 s`() {
        val data = vertices(100_000)
        GeometryCalculator.of(data.take(1_000), false, SegmentScale.Grid) // calienta

        val start = System.nanoTime()
        val result = GeometryCalculator.of(data, false, SegmentScale.Grid)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        result.shouldBeInstanceOf<GeometryResult.Info>().info.segmentCount shouldBe 99_999
        (elapsedMs < 1_000) shouldBe true
    }

    @Test
    fun `S026 RNF-T13 la informacion de 100000 vertices en terreno se calcula en menos de 1 s`() {
        val data = vertices(100_000)
        GeometryCalculator.of(data.take(1_000), false, GroundScale(ProjectCrs.ETRS89_UTM30, null)) // calienta

        val start = System.nanoTime()
        val result = GeometryCalculator.of(data, false, GroundScale(ProjectCrs.ETRS89_UTM30, null))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        result.shouldBeInstanceOf<GeometryResult.Info>().info.segmentCount shouldBe 99_999
        (elapsedMs < 1_000) shouldBe true
    }

    @Test
    fun `S026 RNF-T13 un poligono de 100000 vertices se comprueba en menos de 1 s`() {
        // Una circunferencia de 100.000 vertices: sin cruces, el barrido de cruces recorre pocos pares.
        val ring = List(100_000) { i ->
            val angle = 2 * Math.PI * i / 100_000
            GeometryVertex(easting = 440_000.0 + 5_000 * Math.cos(angle), northing = 4_472_000.0 + 5_000 * Math.sin(angle))
        }

        val start = System.nanoTime()
        val result = GeometryCalculator.of(ring, true, SegmentScale.Grid)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        val info = result.shouldBeInstanceOf<GeometryResult.Info>().info
        info.crosses shouldBe false
        (info.area!! > 7.8e7) shouldBe true
        (elapsedMs < 1_000) shouldBe true
    }

    @Test
    fun `S026 RNF-T13 la informacion de una linea de 100000 vertices de la base llega en menos de 1 s`() {
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
        val vm = GeometryInfoViewModel(RoomLineRepository(db, points), projects, FakePreferences(DistanceMode.GRID))
        try {
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            val start = System.nanoTime()
            vm.start(projectId, "big")

            val ui = runBlocking { withTimeout(20_000) { vm.state.first { it.info != null } } }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            collector.cancel()

            ui.info!!.segmentCount shouldBe 99_999
            (elapsedMs < 1_000) shouldBe true
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `S026 RNF-T13 la tabla de 100000 segmentos solo compone las filas visibles y se desplaza al final`() {
        val data = vertices(100_001)
        val info = (GeometryCalculator.of(data, false, SegmentScale.Grid) as GeometryResult.Info).info
        val state = GeometryInfoUi(
            loaded = true,
            projectName = "Obra",
            lineName = "BORD_1",
            info = info,
            names = List(data.size) { "P$it" },
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GeometryInfoScreen(ThemeState.DEFAULT, {}, state, GeometryInfoActions(), onBack = {})
            }
        }

        compose.waitForIdle()
        val firstFrame = compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size
        compose.onNodeWithTag(GEOMETRY_INFO_TAG).performScrollToNode(hasTestTag(geometrySegmentTag(300)))
        compose.onNodeWithTag(geometrySegmentTag(300)).assertIsDisplayed()
        val composed = compose.onAllNodes(rowMatcher).fetchSemanticsNodes().size
        (firstFrame < 60) shouldBe true
        (composed < 60) shouldBe true
    }
}
