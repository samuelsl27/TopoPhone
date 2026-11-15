package es.topophone.app.ui.stakeout

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.scenario.Scenario
import es.topophone.core.gnss.scenario.ScenarioId
import es.topophone.core.gnss.scenario.ScenarioOrigin
import es.topophone.core.gnss.scenario.ScenarioPlayer
import es.topophone.core.gnss.scenario.ScenarioState
import es.topophone.core.gnss.scenario.Scenarios
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.point.StakeoutPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.LocationFix
import es.topophone.receiver.transport.LocationSource
import es.topophone.receiver.transport.PhoneGnssTransport
import es.topophone.receiver.transport.VirtualReceiverTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El replanteo con el receptor virtual (el paseo en línea recta, la pérdida de FIX) y con el GNSS del móvil (siempre SINGLE, con la guía
 * apagada o admitida según S047) (S022/RF-10, RF-14, RF-16; S047/RF-12, RF-13).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StakeoutSourcesTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val calculator = ProjectPositionCalculator()

    private class FakeStakeoutPreferences : StakeoutPreferences {
        override val settings = MutableStateFlow(StakeoutSettings())

        override suspend fun save(settings: StakeoutSettings) {
            this.settings.value = settings.copy(toleranceM = settings.validToleranceM, toleranceVM = settings.validToleranceVM)
        }
    }

    private class FakeCollectorPreferences(admitted: AdmittedSolutions) : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings(precision = PrecisionControl(admitted = admitted)))

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }

    private val viewModels = mutableListOf<StakeoutViewModel>()
    private val projectId: String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
    }
    private val t0 = 1_794_736_800_000L // 2026-11-15 10:00:00 UTC
    private val latitude = 40.4168
    private val longitude = -3.703713

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

    private fun viewModel(admitted: AdmittedSolutions = AdmittedSolutions.FIX_ONLY): StakeoutViewModel {
        val vm = StakeoutViewModel(
            projects,
            points,
            FakeStakeoutPreferences(),
            FakeCollectorPreferences(admitted),
            CurrentGear.NONE,
            session,
            object : Alerts {
                override fun connectionLost() = Unit

                override fun correctionsLost() = Unit

                override fun pointSaved() = Unit

                override fun solutionLost() = Unit

                override fun tiltLost() = Unit
            },
            { calculator },
            MeasurementInProgress(),
            RecordingLog(),
        )
        viewModels += vm
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        return vm
    }

    private fun await(vm: StakeoutViewModel, predicate: (StakeoutUiState) -> Boolean): StakeoutUiState = runBlocking {
        try {
            withTimeout(20_000) { vm.state.first(predicate) }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("El replanteo no llegó al estado esperado; se quedó en ${vm.state.value}", e)
        }
    }

    /** Un punto de diseño a [eastM] metros al este del origen de los escenarios. */
    private fun addTarget(eastM: Double): Point {
        val phi = Math.toRadians(latitude)
        val metresPerDegree = 111_412.84 * cos(phi) - 93.5 * cos(3 * phi) + 0.118 * cos(5 * phi)
        val ground = GeodeticPosition(latitude, longitude + eastM / metresPerDegree, 699.0)
        val position = calculator.fromGround(ground, ProjectDefaults().toDraft().crs, ProjectDefaults().toDraft().heightSystem)
        val measurement = Measurement(
            antenna = GeodeticPosition(latitude, longitude, 700.9),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_000,
            measuredToMs = 1_200,
            coordinates = PointCoordinates(ground, position.easting, position.northing, position.height),
        )
        return Point.measured("id-T", projectId, "T", "", measurement, createdAtMs = 1_000).also { runBlocking { points.create(it) } }
    }

    private fun distance(state: StakeoutUiState): Double? = (state.guidance as? GuidanceState.On)?.ui?.guidance?.distanceM

    private fun virtual(scenario: Scenario): VirtualReceiverTransport {
        val player = ScenarioPlayer(scenario, ScenarioOrigin(latitude, longitude, 650.0), t0)
        return VirtualReceiverTransport("Receptor virtual", player, ReplaySpeed.X5)
    }

    @Test
    fun `S022 RF-14 con el paseo en linea recta la guia se acerca al punto, lo alcanza y se aleja`() {
        val target = addTarget(eastM = 30.0)
        val vm = viewModel()
        vm.open(projectId, target.id)
        await(vm) { it.target != null }

        session.connect(virtual(Scenarios.straightWalk))

        val start = await(vm) { distance(it) != null }
        distance(start)!! shouldBe (30.0 plusOrMinus 3.0) // las primeras épocas llegan con el jalón aún cerca del origen
        val onTarget = await(vm) { (distance(it) ?: 99.0) < 1.0 }
        (distance(onTarget)!! < 1.0) shouldBe true
        // Después de los 60 s de escenario (12 s a ×5) el paseo gira al norte y la distancia vuelve a crecer.
        val away = await(vm) { (distance(it) ?: 0.0) > 10.0 }
        (distance(away)!! > 10.0) shouldBe true
    }

    @Test
    fun `S047 RF-12 con solo FIX la guia se apaga cuando el escenario pasa a FLOAT y vuelve con el FIX`() {
        val target = addTarget(eastM = 10.0)
        val vm = viewModel()
        vm.open(projectId, target.id)
        await(vm) { it.target != null }
        val loss = Scenario(ScenarioId.FIX_LOSS_AND_RECOVERY, durationS = 40) { second ->
            val base = ScenarioState(0.0, 0.0, SolutionStatus.FIX, 22, 0.5, 0.008, 0.007, 0.015, 1.0)
            if (second in 8..17) base.copy(solution = SolutionStatus.FLOAT, satellites = 14) else base
        }

        session.connect(virtual(loss))

        await(vm) { distance(it) != null }
        await(vm) { (it.guidance as? GuidanceState.Off)?.reason == GuidanceOff.SOLUTION_NOT_ADMITTED }
        await(vm) { distance(it) != null } // recuperado: FIX otra vez
    }

    private var callback: ((LocationFix) -> Unit)? = null
    private val location = object : LocationSource {
        override fun hasPermission() = true

        override fun isEnabled() = true

        override fun start(onFix: (LocationFix) -> Unit) {
            callback = onFix
        }

        override fun stop() = Unit
    }

    private fun phoneFixes(count: Int) {
        session.connect(PhoneGnssTransport("GNSS del móvil", location))
        runBlocking { withTimeout(10_000) { while (callback == null) delay(10) } }
        for (second in 0 until count) {
            callback!!.invoke(LocationFix(latitude, longitude, 650.0, 3.0, null, t0 + second * 1_000L, mock = false))
        }
    }

    @Test
    fun `S022 RF-10 con el movil y solo FIX la guia queda apagada porque el movil es SINGLE`() {
        val target = addTarget(eastM = 10.0)
        val vm = viewModel()
        vm.open(projectId, target.id)
        await(vm) { it.target != null }

        phoneFixes(4)

        val off = await(vm) { (it.guidance as? GuidanceState.Off)?.reason == GuidanceOff.SOLUTION_NOT_ADMITTED }
        (off.guidance as GuidanceState.Off).reason shouldBe GuidanceOff.SOLUTION_NOT_ADMITTED
    }

    @Test
    fun `S047 RF-13 con SINGLE admitido el movil mantiene la guia y la solucion es SINGLE`() {
        val target = addTarget(eastM = 10.0)
        val admitted = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT, SolutionType.SINGLE))
        val vm = viewModel(admitted)
        vm.open(projectId, target.id)
        await(vm) { it.target != null }

        phoneFixes(4)

        val on = await(vm) { distance(it) != null }
        val ui = (on.guidance as GuidanceState.On).ui
        ui.solution shouldBe SolutionStatus.SINGLE
        distance(on)!! shouldBe (10.0 plusOrMinus 0.5)
    }
}
