package es.topophone.app.ui.baseshift

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.baseshift.RoomBaseShiftRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.LocationFix
import es.topophone.receiver.transport.LocationSource
import es.topophone.receiver.transport.PhoneGnssTransport
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

/** El desplazamiento de base no se puede medir con el GNSS del móvil: no tiene base, y se dice por qué (S022/RF-9). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class BaseShiftPhoneTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val shifts = RoomBaseShiftRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val preferences = object : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings())

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }
    private val viewModels = mutableListOf<BaseShiftViewModel>()
    private val calculator = ProjectPositionCalculator()
    private var callback: ((LocationFix) -> Unit)? = null
    private val location = object : LocationSource {
        override fun hasPermission() = true

        override fun isEnabled() = true

        override fun start(onFix: (LocationFix) -> Unit) {
            callback = onFix
        }

        override fun stop() = Unit
    }

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

    private fun projectId(): String = runBlocking {
        val draft = ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", heightSystem = HeightSystem.ELLIPSOIDAL)
        (projects.create(draft) as SaveResult.Saved).id
    }

    private fun viewModel(projectId: String): BaseShiftViewModel {
        val vm = BaseShiftViewModel(projects, points, shifts, preferences, session, { calculator })
        viewModels += vm
        vm.start(projectId)
        runBlocking { withTimeout(10_000) { vm.state.first { it.loaded } } }
        return vm
    }

    private fun phoneReceiving() {
        session.connect(PhoneGnssTransport("GNSS del móvil", location))
        runBlocking { withTimeout(10_000) { while (callback == null) delay(10) } }
        val t0 = 1_794_736_800_000L
        callback!!.invoke(LocationFix(43.2630126, -2.9349852, 40.0, 3.0, null, t0, mock = false))
        callback!!.invoke(LocationFix(43.2630126, -2.9349852, 40.0, 3.0, null, t0 + 1_000, mock = false))
        runBlocking { withTimeout(10_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
    }

    @Test
    fun `S022 RF-9 con el GNSS del movil el desplazamiento de base queda bloqueado por la fuente`() {
        val vm = viewModel(projectId())
        vm.setSource(KnownSource.TYPED)
        vm.setName("V-12")
        vm.setEasting("440000")
        vm.setNorthing("4474000")

        phoneReceiving()

        runBlocking { withTimeout(10_000) { vm.state.first { it.blocker == BaseShiftBlocker.PHONE_SOURCE } } }.blocker shouldBe
            BaseShiftBlocker.PHONE_SOURCE
        session.state.value.source shouldBe PositionSource.PHONE
    }

    @Test
    fun `S022 RF-9 la pantalla dice que el movil no sirve y Medir no se puede pulsar`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                val state = BaseShiftUiState(loaded = true, blocker = BaseShiftBlocker.PHONE_SOURCE)
                BaseShiftScreen(ThemeState.DEFAULT, {}, state, BaseShiftActions(), {})
            }
        }

        compose.onNodeWithText("El GNSS del móvil no se puede usar para el desplazamiento de base", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(BASE_SHIFT_MEASURE_TAG).performScrollTo().assertIsNotEnabled()
    }
}
