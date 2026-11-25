package es.topophone.app.ui.collector

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.baseshift.BASE_SHIFT_BANNER_DEACTIVATE_TAG
import es.topophone.app.ui.baseshift.BASE_SHIFT_BANNER_TAG
import es.topophone.app.ui.field.setContentExpanded
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.BaseShift
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.BaseShiftApplier
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.baseshift.ActiveBaseShift
import es.topophone.data.baseshift.RoomBaseShiftRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

/** El recolector lleva el desplazamiento de base activo en los puntos que mide, lo avisa y se desactiva desde allí (S020/RF-7 a RF-9, RF-11). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-w411dp-h900dp")
class CollectorBaseShiftTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val shifts = RoomBaseShiftRepository(db)
    private val preferences = object : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val alerts = object : Alerts {
        override fun tiltLost() = Unit

        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit
    }
    private val viewModels = mutableListOf<CollectorViewModel>()
    private val calculator = ProjectPositionCalculator()
    private val vector = BaseShiftVector(2.1, -1.7, 0.4)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun project(): String = runBlocking {
        val draft = ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", heightSystem = HeightSystem.ELLIPSOIDAL)
        (projects.create(draft) as SaveResult.Saved).id
    }

    private fun activeShift(projectId: String) = runBlocking {
        shifts.add(
            BaseShift(
                id = "s1",
                projectId = projectId,
                createdAtMs = 1,
                fromReplay = false,
                knownName = "V-12",
                knownPointId = null,
                knownEasting = 1.0,
                knownNorthing = 2.0,
                knownHeightM = 3.0,
                measured = GeodeticPosition(40.0, -3.0, 700.0),
                epochsUsed = 5,
                vector = vector,
                horizontalOnly = false,
                correctionsDatum = CorrectionsDatum.ETRS89,
                active = false,
                base = null,
            ),
            activate = true,
        )
    }

    private fun viewModel(projectId: String): CollectorViewModel {
        val vm = CollectorViewModel(
            projects,
            points,
            preferences,
            session,
            alerts,
            { calculator },
            MeasurementInProgress(),
            baseShift = ActiveBaseShift(shifts),
        )
        viewModels += vm
        vm.start(projectId)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await(vm) { it.loaded }
        runBlocking { withTimeout(10_000) { while (!vm.isProjectLoaded) kotlinx.coroutines.delay(10) } }
        return vm
    }

    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState =
        runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    private fun sendEpoch(second: Int) {
        val time = "1015%02d.00".format(30 + second)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun receiving(vm: CollectorViewModel) {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0)
        sendEpoch(1)
        awaitReceiving(session, vm)
        await(vm) { it.live != null }
        await(vm) { it.blocker == null }
    }

    private val tip = GeodeticPosition(40.4168, -(3.0 + 42.2228 / 60.0), 650.123 + 51.234 - 1.934)

    @Test
    fun `S020 RF-7 y RF-8 el punto medido lleva el desplazamiento y guarda lo bruto aparte`() {
        val id = project()
        activeShift(id)
        val vm = viewModel(id)
        receiving(vm)
        await(vm) { it.baseShiftName == "V-12" }

        vm.measure()
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.savedName == "1" }

        val measurement = runBlocking { points.points(id).first() }.single().measurement.shouldNotBeNull()
        val expectedGround = BaseShiftApplier.apply(tip, vector)
        measurement.coordinates.ground.latitudeDeg shouldBe (expectedGround.latitudeDeg plusOrMinus 1e-12)
        measurement.coordinates.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (expectedGround.ellipsoidalHeightM!! plusOrMinus 1e-6)
        val position = calculator.fromGround(measurement.coordinates.ground, ProjectCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)
        measurement.coordinates.easting shouldBe position.easting
        val shift = measurement.shift.shouldNotBeNull()
        shift.shiftId shouldBe "s1"
        shift.knownName shouldBe "V-12"
        shift.vector shouldBe vector
        shift.unshiftedGround.latitudeDeg shouldBe (tip.latitudeDeg plusOrMinus 1e-12)
        measurement.antenna.ellipsoidalHeightM.shouldNotBeNull() shouldBe (650.123 + 51.234 plusOrMinus 1e-9)
    }

    @Test
    fun `S020 RF-9 desactivar a mitad de la medida no cambia el vector con que empezo`() {
        val id = project()
        activeShift(id)
        val vm = viewModel(id)
        receiving(vm)
        await(vm) { it.baseShiftName == "V-12" }

        vm.measure()
        await(vm) { it.measuring }
        vm.deactivateBaseShift()
        await(vm) { it.baseShiftName == null }
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.savedName == "1" }

        runBlocking { points.points(id).first() }.single().measurement.shouldNotBeNull().shift.shouldNotBeNull().vector shouldBe vector
    }

    @Test
    fun `S020 RF-11 tras desactivar los puntos siguientes ya no llevan desplazamiento`() {
        val id = project()
        activeShift(id)
        val vm = viewModel(id)
        receiving(vm)
        await(vm) { it.baseShiftName == "V-12" }
        vm.deactivateBaseShift()
        await(vm) { it.baseShiftName == null }
        await(vm) { it.blocker == null }

        vm.measure()
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.savedName == "1" }

        val measurement = runBlocking { points.points(id).first() }.single().measurement.shouldNotBeNull()
        measurement.shift.shouldBeNull()
        measurement.coordinates.ground.latitudeDeg shouldBe (tip.latitudeDeg plusOrMinus 1e-12)
    }

    @Test
    fun `S020 RF-11 sin desplazamiento activo el recolector mide como siempre`() {
        val id = project()
        val vm = viewModel(id)
        receiving(vm)

        vm.state.value.baseShiftName.shouldBeNull()
        vm.measure()
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.savedName == "1" }

        runBlocking { points.points(id).first() }.single().measurement.shouldNotBeNull().shift.shouldBeNull()
    }

    @Test
    fun `S020 RF-11 el panel enseña Base shift activo con el punto conocido y permite desactivarlo`() {
        var deactivated = 0
        val state = CollectorUiState(
            loaded = true,
            name = "P12",
            poleHeightText = "1,800",
            live = LiveQuality(SolutionStatus.FIX, 0.01, 0.015),
            baseShiftName = "V-12",
        )
        compose.setContentExpanded {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CollectorSheet(state, LinearUnit.METRE, CollectorActions(onDeactivateBaseShift = { deactivated++ }), onClose = {})
            }
        }

        compose.onNodeWithTag(BASE_SHIFT_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText("Base shift activo · V-12").assertIsDisplayed()
        compose.onNodeWithTag(BASE_SHIFT_BANNER_DEACTIVATE_TAG).performClick()

        deactivated shouldBe 1
    }
}
