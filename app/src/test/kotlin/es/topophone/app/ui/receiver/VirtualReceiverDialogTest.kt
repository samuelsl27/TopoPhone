package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.receiver.VirtualOrigins
import es.topophone.app.receiver.VirtualProject
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.ScenarioId
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import org.robolectric.annotation.Config

/** La elección del receptor virtual: escenario con su descripción y duración, origen y velocidad (S022/RF-13, RF-14, RF-16). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class VirtualReceiverDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetooth = FakeBluetooth()
    private val preferences = FakePreferences()
    private val connector = NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE }
    private val session = ReceiverSession(sessionScope, connector, noDataTimeoutMs = 60_000, watchdogPeriodMs = 1_000)
    private val connections = ReceiverConnections(
        session,
        bluetooth,
        preferences,
        CoroutineScope(Dispatchers.Default),
        utcNowMs = { 1_794_736_800_000L },
    )
    private var project: VirtualProject? = VirtualProject("Calle Mayor", CentralPoint(43.2630126, -2.9349852))
    private val origins = object : VirtualOrigins {
        override suspend fun latestProject(): VirtualProject? = project
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        session.disconnect()
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ReceiverViewModel(
        session = session,
        bluetooth = bluetooth,
        preferences = preferences,
        calculator = { CALCULATOR },
        network = { true },
        alerts = NoAlerts,
        clock = { System.nanoTime() / 1_000_000 },
        samplePeriodMs = 0,
        replay = ReplayController(session) { emptyList() },
        connections = connections,
        virtualOrigins = origins,
    )

    private fun ReceiverViewModel.awaitState(predicate: (ReceiverUiState) -> Boolean): ReceiverUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { uiState.collect {} }
        try {
            withTimeout(5_000) { uiState.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private fun ReceiverViewModel.open(): VirtualUi {
        openVirtual()
        return awaitState { it.virtual != null }.virtual.shouldNotBeNull()
    }

    @Test
    fun `S022 RF-13 por defecto el origen es el centro del proyecto mas reciente, x1 y el primer escenario`() {
        val vm = viewModel()

        val choice = vm.open()

        choice.originMode shouldBe VirtualOriginMode.PROJECT
        choice.projectName shouldBe "Calle Mayor"
        choice.scenario shouldBe ScenarioId.STABLE_FIX
        choice.speed shouldBe ReplaySpeed.X1
        choice.canContinue shouldBe true
    }

    @Test
    fun `S022 RF-13 conectar arranca el escenario elegido en el centro del proyecto y a la velocidad elegida`() {
        val vm = viewModel()
        vm.open()
        vm.virtualScenario(ScenarioId.DATA_GAP)
        vm.virtualSpeed(ReplaySpeed.X5)

        vm.confirmVirtual()

        val state = vm.awaitState { it.virtual == null && it.snapshot.scenario != null }
        state.snapshot.source shouldBe PositionSource.VIRTUAL
        state.snapshot.scenario.shouldNotBeNull().id shouldBe ScenarioId.DATA_GAP
        state.snapshot.scenario.shouldNotBeNull().speed shouldBe ReplaySpeed.X5
        val fix = vm.awaitState { it.snapshot.gga != null }.snapshot.gga.shouldNotBeNull()
        fix.latitudeDeg shouldBe (43.2630126 plusOrMinus 1e-6)
        fix.longitudeDeg shouldBe (-2.9349852 plusOrMinus 1e-6)
        connections.currentAddress.value shouldBe ReceiverKey.VIRTUAL
    }

    @Test
    fun `S022 RF-13 con unas coordenadas escritas, con coma o con punto, el escenario ocurre ahi`() {
        val vm = viewModel()
        vm.open()
        vm.virtualOriginMode(VirtualOriginMode.COORDINATES)
        vm.virtualLatitude("39,4699123")
        vm.virtualLongitude("-0.3763123")
        vm.virtualSpeed(ReplaySpeed.X5)

        vm.confirmVirtual()

        val fix = vm.awaitState { it.snapshot.gga != null }.snapshot.gga.shouldNotBeNull()
        fix.latitudeDeg shouldBe (39.4699123 plusOrMinus 1e-6)
        fix.longitudeDeg shouldBe (-0.3763123 plusOrMinus 1e-6)
    }

    @Test
    fun `S022 RF-13 unas coordenadas imposibles no dejan continuar y el dialogo sigue abierto`() {
        val vm = viewModel()
        vm.open()
        vm.virtualOriginMode(VirtualOriginMode.COORDINATES)
        vm.virtualLatitude("91")
        vm.virtualLongitude("-3")

        val state = vm.awaitState { it.virtual?.latitudeText == "91" }

        state.virtual.shouldNotBeNull().canContinue shouldBe false
        state.virtual.shouldNotBeNull().latitudeInvalid shouldBe true
        vm.confirmVirtual()
        vm.awaitState { it.virtual != null }.snapshot.connection shouldBe ConnectionState.Disconnected
    }

    @Test
    fun `S022 RF-13 sin proyecto y sin coordenadas no se puede continuar`() {
        project = null
        val vm = viewModel()

        val choice = vm.open()

        choice.originMode shouldBe VirtualOriginMode.COORDINATES
        choice.projectCenter.shouldBeNull()
        choice.canContinue shouldBe false
        vm.confirmVirtual()
        vm.awaitState { it.virtual != null }.snapshot.connection shouldBe ConnectionState.Disconnected
        vm.virtualLatitude("43.2")
        vm.virtualLongitude("-2.9")
        vm.awaitState { it.virtual?.canContinue == true }
    }

    @Test
    fun `S022 RF-13 un proyecto sin centro tambien pide coordenadas`() {
        project = VirtualProject("Vacio", null)
        val vm = viewModel()

        val choice = vm.open()

        choice.originMode shouldBe VirtualOriginMode.COORDINATES
        choice.projectName shouldBe "Vacio"
        choice.canContinue shouldBe false
    }

    @Test
    fun `S022 RF-13 cancelar cierra la eleccion sin conectar`() {
        val vm = viewModel()
        vm.open()

        vm.cancelVirtual()

        vm.awaitState { it.virtual == null }.snapshot.connection shouldBe ConnectionState.Disconnected
    }

    @Test
    fun `S022 RF-13 las coordenadas escritas se leen con coma o punto y dentro de rango`() {
        VirtualOriginText.latitude("43,26").shouldNotBeNull()
        VirtualOriginText.latitude(" 43.26 ") shouldBe 43.26
        VirtualOriginText.latitude("90") shouldBe 90.0
        VirtualOriginText.latitude("90.0001").shouldBeNull()
        VirtualOriginText.longitude("-180") shouldBe -180.0
        VirtualOriginText.longitude("180.5").shouldBeNull()
        for (text in listOf("", "abc", "NaN", "Infinity", "1e400")) {
            VirtualOriginText.latitude(text).shouldBeNull()
            VirtualOriginText.longitude(text).shouldBeNull()
        }
        VirtualOriginText.parse("43.2", "-2.9").shouldNotBeNull()
        VirtualOriginText.parse("43.2", "").shouldBeNull()
    }

    // ----- La pantalla -----

    private fun show(choice: VirtualUi, actions: ReceiverActions = ReceiverActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(ThemeState.DEFAULT, {}, ReceiverUiState(bluetooth = BluetoothUi.Disabled, virtual = choice), actions)
            }
        }
    }

    private val projectChoice = VirtualUi.initial("Calle Mayor", CentralPoint(43.2630126, -2.9349852))

    @Test
    fun `S022 RF-14 el dialogo lista los siete escenarios con descripcion y duracion`() {
        show(projectChoice)

        val expected = mapOf(
            ScenarioId.STABLE_FIX to listOf("FIX estable", "Quieto, en FIX, con 22 satélites.", "Duración: 2 min 00 s"),
            ScenarioId.FIX_LOSS_AND_RECOVERY to listOf("Pérdida de FIX y recuperación", "FIX, luego FLOAT", "Duración: 3 min 00 s"),
            ScenarioId.FLOAT_AND_SINGLE to listOf("FLOAT y SINGLE", "Nunca llega a FIX", "Duración: 2 min 30 s"),
            ScenarioId.DATA_GAP to listOf("Corte de datos", "15 s sin ninguna frase", "Duración: 2 min 00 s"),
            ScenarioId.BASE_CHANGE to listOf("Cambio de base", "cambia de base de correcciones", "Duración: 2 min 30 s"),
            ScenarioId.TILT_FIGURE_EIGHT to listOf("Inclinación con el movimiento en ocho", "ocho de 1,2 m", "Duración: 2 min 00 s"),
            ScenarioId.STRAIGHT_WALK to listOf("Paseo en línea recta", "A 1 m/s", "Duración: 2 min 00 s"),
        )
        for ((id, texts) in expected) {
            val row = compose.onNodeWithTag(virtualScenarioTag(id)).performScrollTo()
            row.assertIsDisplayed()
            for (text in texts) row.assert(hasText(text, substring = true))
        }
    }

    @Test
    fun `S022 RF-13 elegir escenario, origen y velocidad avisa de cada cambio`() {
        var scenario: ScenarioId? = null
        var speed: ReplaySpeed? = null
        var mode: VirtualOriginMode? = null
        show(
            projectChoice,
            ReceiverActions(
                onVirtualScenario = { scenario = it },
                onVirtualSpeed = { speed = it },
                onVirtualOriginMode = { mode = it },
            ),
        )

        compose.onNodeWithTag(virtualScenarioTag(ScenarioId.STRAIGHT_WALK)).performScrollTo().performClick()
        compose.onNodeWithTag(virtualSpeedTag(ReplaySpeed.X5)).performScrollTo().performClick()
        compose.onNodeWithTag(VIRTUAL_ORIGIN_COORDINATES_TAG).performScrollTo().performClick()

        scenario shouldBe ScenarioId.STRAIGHT_WALK
        speed shouldBe ReplaySpeed.X5
        mode shouldBe VirtualOriginMode.COORDINATES
    }

    @Test
    fun `S022 RF-13 con el centro del proyecto se ofrece y Conectar esta activo`() {
        var connected = 0
        show(projectChoice, ReceiverActions(onVirtualConfirm = { connected++ }))

        compose.onNodeWithTag(VIRTUAL_ORIGIN_PROJECT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Centro del proyecto «Calle Mayor»").assertIsDisplayed()
        compose.onNodeWithTag(VIRTUAL_CONNECT_TAG).assertIsEnabled().performClick()

        connected shouldBe 1
    }

    @Test
    fun `S022 RF-13 sin proyecto ni coordenadas Conectar no se puede pulsar`() {
        show(VirtualUi.initial(null, null))

        compose.onNodeWithTag(VIRTUAL_ORIGIN_PROJECT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(VIRTUAL_LATITUDE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(VIRTUAL_CONNECT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S022 RF-13 unas coordenadas imposibles muestran el aviso`() {
        show(VirtualUi.initial(null, null).copy(latitudeText = "95", longitudeText = "-3"))

        compose.onNodeWithText("Escribe latitud y longitud en grados decimales", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(VIRTUAL_CONNECT_TAG).assertIsNotEnabled()
    }

    private companion object {
        val CALCULATOR = GroundPointCalculator()
    }
}
