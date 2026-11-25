package es.topophone.app.ui.collector

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.localization.ActiveLocalization
import es.topophone.data.localization.ApplyResult
import es.topophone.data.localization.RoomLocalizationRepository
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

/** El recolector localiza los puntos que mide con la localización activa y lo indica (S039/RF-14, RF-17, RF-19). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorLocalizationTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val localizations = RoomLocalizationRepository(db)
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

    /** Una localización de un solo par: traslación de +100 m en Este y -50 m en Norte y +0,25 m en cota. */
    private fun activeLocalization(projectId: String) = runBlocking {
        localizations.addPair(projectId, LocalizationPair("q1", "x", "X", 440_000.0, 4_474_000.0, 650.0, 440_100.0, 4_473_950.0, 650.25))
        localizations.apply(projectId) as ApplyResult.Applied
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
            localization = ActiveLocalization(localizations),
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
    fun `S039 RF-17 y RF-19 el punto medido sale localizado y guarda la medida sin localizar`() {
        val id = project()
        activeLocalization(id)
        val vm = viewModel(id)
        receiving(vm)
        await(vm) { it.localized }

        vm.measure()
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.savedName == "1" }

        val point = runBlocking { points.points(id).first() }.single()
        val measurement = point.measurement.shouldNotBeNull()
        val sc = measurement.coordinates
        point.coordinates.easting.shouldNotBeNull() shouldBe (sc.easting!! + 100.0 plusOrMinus 1e-6)
        point.coordinates.northing.shouldNotBeNull() shouldBe (sc.northing!! - 50.0 plusOrMinus 1e-6)
        point.coordinates.height.shouldNotBeNull() shouldBe (sc.height!! + 0.25 plusOrMinus 1e-6)
        point.coordinates.ground shouldBe sc.ground
        point.localizationId.shouldNotBeNull()
        val position = calculator.fromGround(sc.ground, ProjectCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)
        sc.easting shouldBe position.easting
    }

    @Test
    fun `S039 RF-14 sin localizacion el recolector mide como siempre`() {
        val id = project()
        val vm = viewModel(id)
        receiving(vm)

        vm.state.value.localized shouldBe false
        vm.measure()
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.savedName == "1" }

        val point = runBlocking { points.points(id).first() }.single()
        point.localizationId.shouldBeNull()
        point.coordinates shouldBe point.measurement.shouldNotBeNull().coordinates
    }

    @Test
    fun `S039 RF-17 tras quitar la localizacion los puntos siguientes salen sin localizar`() {
        val id = project()
        activeLocalization(id)
        val vm = viewModel(id)
        receiving(vm)
        await(vm) { it.localized }
        runBlocking { localizations.remove(id) }
        await(vm) { !it.localized }
        await(vm) { it.blocker == null }

        vm.measure()
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { it.savedName == "1" }

        val point = runBlocking { points.points(id).first() }.single()
        point.localizationId.shouldBeNull()
        point.coordinates shouldBe point.measurement.shouldNotBeNull().coordinates
    }

    @Test
    fun `S039 RF-14 y S060 RF-14 el panel enseña la localizacion activa con texto`() {
        val state = CollectorUiState(
            loaded = true,
            name = "P12",
            poleHeightText = "1,800",
            live = LiveQuality(SolutionStatus.FIX, 0.01, 0.015),
            localized = true,
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CollectorSheet(state, LinearUnit.METRE, CollectorActions(), onClose = {})
            }
        }

        // S060/RF-14: el indicador de la localización activa es una pastilla informativa con su texto.
        compose.onNodeWithTag(infoPillTag(InfoPillKind.LOCALIZATION)).assertIsDisplayed()
            .assertTextContains("Localización", substring = true)
            .assertTextContains("Activa", substring = true)
    }
}
