package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.receiver.MemoryTiltPreferences
import es.topophone.app.receiver.currentTilt
import es.topophone.app.ui.receiver.nmea
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.PoleTip
import es.topophone.core.survey.ProjectPosition
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.math.cos
import kotlin.math.sin
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La punta en el panel de posición y en el rover, con la precisión de la inclinación (S016/RF-16, RF-17, RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectViewTiltTest {

    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(scope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val toApp = PipedOutputStream()
    private val calculator = ProjectPositionCalculator()
    private val rs3 = "00:11:22:33:44:55"
    private val antenna = GeodeticPosition(40.0 + 25.008 / 60, -(3.0 + 42.2228 / 60), 650.123 + 51.234)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        runCatching { toApp.close() }
        session.disconnect()
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    /** El ViewModel con un proyecto abierto y el receptor en [mode], recibiendo dos épocas con [etc]. */
    private fun state(mode: TiltMode, etc: String, predicate: (ProjectViewUiState) -> Boolean): ProjectViewUiState {
        val repository = RoomProjectRepository(db)
        val id = runBlocking { (repository.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id }
        val tilt = currentTilt(scope, MemoryTiltPreferences(mapOf(rs3 to ReceiverTiltSettings(mode, verified = true))), address = rs3)
        runBlocking { withTimeout(5_000) { tilt.state.first { it.settings.mode == mode } } }
        val vm = projectViewModel(repository, session, calculator = calculator, tilt = tilt)
        vm.start(id)
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        listOf("101530.20", "101530.40").forEach { time ->
            toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
            toApp.write(nmea("GNGST,$time,0.012,0.008,0.006,45.0,0.008,0.006,0.015").toByteArray())
            toApp.write(nmea("GNETC,$time,$etc").toByteArray())
        }
        toApp.flush()
        return runBlocking {
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            try {
                withTimeout(5_000) { vm.state.first { it.rover != null && predicate(it) } }
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun `S016 RF-16 en APP el panel y el rover estan en la punta`() {
        val state = state(TiltMode.APP, "30,0,123.40,210.50,15.25,1.013,0.993,1.021") { !it.uncompensated && it.position != null }

        val theta = Math.toRadians(15.25)
        val alpha = Math.toRadians(210.5)
        val tip = PoleTip.fromAntenna(antenna, PoleVector(sin(theta) * sin(alpha), sin(theta) * cos(alpha), cos(theta)), 1.934)
        val rover = state.rover.shouldNotBeNull()
        rover.position.latitudeDeg shouldBe (tip.latitudeDeg plusOrMinus 1e-9)
        rover.position.longitudeDeg shouldBe (tip.longitudeDeg plusOrMinus 1e-9)
        val position = state.position.shouldNotBeNull()
        val expected = calculator.fromGround(tip, ProjectCrs.ETRS89_UTM30)
        position.easting.shouldNotBeNull() shouldBe (expected.easting!! plusOrMinus 1e-4)
        position.northing.shouldNotBeNull() shouldBe (expected.northing!! plusOrMinus 1e-4)
    }

    @Test
    fun `S016 RF-21 el circulo suma la precision de la inclinacion`() {
        val state = state(TiltMode.APP, "30,0,123.40,210.50,15.25,1.013,0.993,1.021") { it.accuracyM != null && !it.uncompensated }

        val gst = GstAccuracy("t", 0.008, 0.006, 0.015).horizontalM!!
        val tilt = Math.hypot(0.993, 1.013) * 1.934 / 1000
        state.accuracyM.shouldNotBeNull() shouldBe ((gst + tilt) plusOrMinus 1e-9)
        state.rover.shouldNotBeNull().accuracyM shouldBe state.accuracyM
    }

    @Test
    fun `S016 RF-17 en APP sin estado 30 la punta va sin compensar`() {
        val state = state(TiltMode.APP, "20,0,,,,,,") { it.uncompensated }

        state.rover.shouldNotBeNull().position.latitudeDeg shouldBe (antenna.latitudeDeg plusOrMinus 1e-12)
    }

    @Test
    fun `S016 RF-17 el panel dice Punta sin compensar`() {
        val project = ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")
        val repository = RoomProjectRepository(db)
        val id = runBlocking { (repository.create(project) as SaveResult.Saved).id }
        val saved = runBlocking { repository.observe(id).first() }.shouldNotBeNull()
        val position = ProjectPosition(440_290.434, 4_474_257.414, 648.34, emptySet())
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(saved, position, uncompensated = true), onBack = {})
            }
        }

        compose.onNodeWithTag(POSITION_UNCOMPENSATED_TAG).performScrollTo().assert(hasText(text(R.string.tilt_tip_uncompensated)))
    }
}
