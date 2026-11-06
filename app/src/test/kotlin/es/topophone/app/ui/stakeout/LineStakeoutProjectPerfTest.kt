package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

/** Abrir el replanteo de una línea del proyecto de 100.000 vértices tarda menos de 1 s, también con alturas en solo algunos (S027, RNF-T13). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LineStakeoutProjectPerfTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val lines = RoomLineRepository(db, points)
    private val cad = RoomCadRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val viewModels = mutableListOf<LineStakeoutViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /** Una línea de 100.000 vértices cada 1 cm; la altura de `height` solo en los vértices que cumplen [heightEvery]. */
    private fun insertBigLine(heightEvery: Int): Pair<String, String> {
        val projectId =
            runBlocking { projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id }
        val sql = db.openHelper.writableDatabase
        val numbers = "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 100000) "
        sql.execSQL(
            numbers + "INSERT INTO points (id, project_id, name, description, origin, antenna_lat, antenna_lon, antenna_h, pole_height, " +
                "ground_lat, ground_lon, ground_h, easting, northing, height, solution, satellites, out_of_tolerance, mode, epochs_used, " +
                "epochs_discarded, from_replay, raw_nmea, tilt_mode, tilt_verified, created_at) " +
                "SELECT 'p' || i, ?, 'P' || i, '', 'MEASURED', 40.4, -3.7, 701.0, 1.8, 40.4, -3.7, 699.0, " +
                "440000.0 + i * 0.01, 4472000.0 + (i % 7) * 0.003, CASE WHEN i % $heightEvery = 0 THEN 648.0 + i * 0.0001 ELSE NULL END, " +
                "'FIX', 24, '', 'INSTANT', 1, 0, 0, '', 'VERTICAL', 0, i FROM n",
            arrayOf<Any>(projectId),
        )
        sql.execSQL(
            "INSERT INTO project_lines VALUES ('big', ?, 'BORD_1', 'LINE', 0, 0, 0, 'BORD', '', 0, 'CIRCLE', 1, NULL, '')",
            arrayOf<Any>(projectId),
        )
        sql.execSQL(numbers + "INSERT INTO line_vertices SELECT 'big', i - 1, 'p' || i FROM n")
        return projectId to "big"
    }

    private fun openAndMeasure(heightEvery: Int): Long {
        val (projectId, lineId) = insertBigLine(heightEvery)
        val vm = LineStakeoutTestSupport.viewModel(
            projects,
            cad,
            LineStakeoutTestSupport.FakeStakeoutPreferences(),
            session,
            ProjectPositionCalculator(),
            RoomStakeLines(lines),
        )
        viewModels += vm
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }

        val start = System.nanoTime()
        vm.open(projectId, LineSource.ProjectLine(lineId))
        val state = runBlocking { withTimeout(20_000) { vm.state.first { it.element != null && it.target != null } } }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        state.element.shouldNotBeNull().projectLine.shouldNotBeNull().name shouldBe "BORD_1"
        state.vertices.shouldNotBeNull().size shouldBe 100_000
        return elapsedMs
    }

    @Test
    fun `S027 RNF-T13 abrir el replanteo de una linea de 100000 vertices con altura en todos tarda menos de 1 s`() {
        (openAndMeasure(heightEvery = 1) < 1_000) shouldBe true
    }

    @Test
    fun `S027 RNF-T13 abrir el replanteo de una linea de 100000 vertices con altura en uno de cada tres tarda menos de 1 s`() {
        (openAndMeasure(heightEvery = 3) < 1_000) shouldBe true
    }
}
