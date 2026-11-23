package es.topophone.app.ui.collector

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.baseshift.BASE_SHIFT_BASE_CHANGED_DEACTIVATE_TAG
import es.topophone.app.ui.baseshift.BASE_SHIFT_BASE_CHANGED_REPEAT_TAG
import es.topophone.app.ui.baseshift.BASE_SHIFT_BASE_CHANGED_TAG
import es.topophone.app.ui.baseshift.BASE_SHIFT_DATUM_ACK_TAG
import es.topophone.app.ui.baseshift.BASE_SHIFT_DATUM_NOTICE_TAG
import es.topophone.app.ui.field.setContentExpanded
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.BaseShift
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.baseshift.ActiveBaseShift
import es.topophone.data.baseshift.RoomBaseShiftRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.nulls.shouldBeNull
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

/** El aviso de que un base shift activo puede haber dejado de valer por un cambio de base o de datum (S020/RF-15, RF-16). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-w411dp-h900dp")
class CollectorBaseShiftNoticeTest {

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
    private val baseA = "4025.0000000"
    private val baseB = "4025.0010000"

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
        (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
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
                vector = BaseShiftVector(2.1, -1.7, 0.4),
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
            { ProjectPositionCalculator() },
            MeasurementInProgress(),
            baseShift = ActiveBaseShift(shifts),
        )
        viewModels += vm
        vm.start(projectId)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        await(vm) { it.loaded }
        return vm
    }

    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState =
        runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    private fun sendEpoch(second: Int, baseLat: String? = null) {
        val time = "1015%02d.00".format(30 + second)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        baseLat?.let { toApp.write(nmea("GNEBP,$it,N,00342.0000000,W,700.000,M").toByteArray()) }
        toApp.flush()
    }

    private fun connectWithBase() {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0, baseA)
        sendEpoch(1, baseA)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
    }

    @Test
    fun `S020 RF-15 si la base cambia con un base shift activo avisa de que puede haber dejado de valer`() {
        val id = project()
        activeShift(id)
        val vm = viewModel(id)
        connectWithBase()
        await(vm) { it.baseShiftName == "V-12" }
        vm.state.value.baseShiftBaseChanged shouldBe false

        sendEpoch(2, baseB)
        sendEpoch(3, baseB)

        await(vm) { it.baseShiftBaseChanged }
    }

    @Test
    fun `S020 RF-15 un cambio de base sin base shift activo no lleva ese aviso`() {
        val id = project()
        val vm = viewModel(id)
        connectWithBase()

        sendEpoch(2, baseB)
        sendEpoch(3, baseB)
        await(vm) { it.notices.change != null }

        vm.state.value.baseShiftBaseChanged shouldBe false
    }

    @Test
    fun `S020 RF-15 desactivarlo quita el aviso`() {
        val id = project()
        activeShift(id)
        val vm = viewModel(id)
        connectWithBase()
        await(vm) { it.baseShiftName == "V-12" }
        sendEpoch(2, baseB)
        sendEpoch(3, baseB)
        await(vm) { it.baseShiftBaseChanged }

        vm.deactivateBaseShift()

        await(vm) { it.baseShiftName == null && !it.baseShiftBaseChanged }
    }

    @Test
    fun `S020 RF-16 un base shift desactivado por un cambio de datum se avisa hasta que el usuario lo ve`() {
        val id = project()
        activeShift(id)
        val vm = viewModel(id)
        await(vm) { it.baseShiftName == "V-12" }

        val current = runBlocking { projects.project(id)!! }
        runBlocking {
            projects.update(id, ProjectDefaults().toDraft().copy(name = current.name, correctionsDatum = CorrectionsDatum.ITRF_WGS84))
        }

        await(vm) { it.baseShiftName == null && it.baseShiftDatumNotice == "V-12" }
        vm.acknowledgeBaseShiftDatum()
        await(vm) { it.baseShiftDatumNotice == null }
    }

    @Test
    fun `S020 RF-15 el panel ofrece desactivar y repetir`() {
        var calls = mutableListOf<String>()
        val state = CollectorUiState(loaded = true, baseShiftName = "V-12", baseShiftBaseChanged = true)
        compose.setContentExpanded {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CollectorSheet(
                    state,
                    LinearUnit.METRE,
                    CollectorActions(onDeactivateBaseShift = { calls += "deactivate" }, onRepeatBaseShift = { calls += "repeat" }),
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(BASE_SHIFT_BASE_CHANGED_TAG).assertExists()
        compose.onNodeWithTag(BASE_SHIFT_BASE_CHANGED_DEACTIVATE_TAG).performClick()
        compose.onNodeWithTag(BASE_SHIFT_BASE_CHANGED_REPEAT_TAG).performClick()

        calls shouldBe listOf("deactivate", "repeat")
    }

    @Test
    fun `S020 RF-16 el panel enseña el aviso del datum y se cierra`() {
        val calls = mutableListOf<String>()
        val state = CollectorUiState(loaded = true, baseShiftDatumNotice = "V-12")
        compose.setContentExpanded {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CollectorSheet(state, LinearUnit.METRE, CollectorActions(onAcknowledgeBaseShiftDatum = { calls += "ack" }), onClose = {})
            }
        }

        compose.onNodeWithTag(BASE_SHIFT_DATUM_NOTICE_TAG).assertExists()
        compose.onNodeWithTag(BASE_SHIFT_DATUM_ACK_TAG).performClick()

        calls shouldBe listOf("ack")
    }

    @Test
    fun `S020 RF-15 sin avisos el panel no enseña ninguno`() {
        val state = CollectorUiState(loaded = true)
        compose.setContentExpanded {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CollectorSheet(state, LinearUnit.METRE, CollectorActions(), onClose = {}) }
        }

        compose.onAllNodesWithTag(BASE_SHIFT_BASE_CHANGED_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(BASE_SHIFT_DATUM_NOTICE_TAG).assertCountEquals(0)
        state.baseShiftDatumNotice.shouldBeNull()
    }
}
