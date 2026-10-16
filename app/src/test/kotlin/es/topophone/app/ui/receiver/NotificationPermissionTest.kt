package es.topophone.app.ui.receiver

import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.receiver.NotificationPermission
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.receiver.notificationSettingsIntent
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
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

/** Permiso de notificaciones de Android 13+ (S004/RF-5, RF-6). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NotificationPermissionTest {

    @get:Rule
    val compose = createComposeRule()

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toApp = PipedOutputStream()
    private val bluetooth = FakeBluetooth()
    private val preferences = FakePreferences()
    private val connector = NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE }
    private val session = ReceiverSession(sessionScope, connector, noDataTimeoutMs = 60_000, watchdogPeriodMs = 1_000)

    private class FakeNotificationPermission(override val required: Boolean, var granted: Boolean = false) : NotificationPermission {
        override fun isGranted() = granted
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        bluetooth.transports[RS3_DEVICE.address] = StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream())
    }

    @After
    fun tearDown() {
        session.disconnect()
        runCatching { toApp.close() }
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(permission: NotificationPermission) = ReceiverViewModel(
        session = session,
        bluetooth = bluetooth,
        preferences = preferences,
        calculator = { CALCULATOR },
        network = { true },
        alerts = NoAlerts,
        clock = { System.nanoTime() / 1_000_000 },
        samplePeriodMs = 0,
        replay = ReplayController(session) { emptyList() },
        connections = ReceiverConnections(session, bluetooth, preferences, CoroutineScope(Dispatchers.Default)),
        notifications = permission,
    )

    private fun ReceiverViewModel.awaitState(predicate: (ReceiverUiState) -> Boolean): ReceiverUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { uiState.collect {} }
        try {
            withTimeout(5_000) { uiState.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private fun ReceiverViewModel.connectReceiver(): ReceiverUiState {
        awaitState { true }
        connect(RS3_DEVICE.address)
        toApp.write(nmea("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.flush()
        return awaitState { it.snapshot.connection is ConnectionState.Receiving }
    }

    @Test
    fun `S004 RF-5 al conectar un receptor por primera vez en Android 13+ se pide el permiso`() {
        val vm = viewModel(FakeNotificationPermission(required = true))

        vm.awaitState { true }.askNotifications shouldBe false
        vm.connectReceiver()

        vm.awaitState { it.askNotifications }.notificationsDenied shouldBe false
    }

    @Test
    fun `S004 RF-5 el permiso se pide una sola vez`() {
        val vm = viewModel(FakeNotificationPermission(required = true))
        vm.connectReceiver()
        vm.awaitState { it.askNotifications }

        vm.notificationsAnswered()

        vm.awaitState { !it.askNotifications }
        preferences.notificationsAskedFlow.value shouldBe true
    }

    @Test
    fun `S004 RF-5 en Android 12 o anterior no se pide ni se avisa`() {
        val vm = viewModel(FakeNotificationPermission(required = false, granted = true))

        val state = vm.connectReceiver()

        state.askNotifications shouldBe false
        state.notificationsDenied shouldBe false
    }

    @Test
    fun `S004 RF-5 con el permiso ya concedido no se pide`() {
        val vm = viewModel(FakeNotificationPermission(required = true, granted = true))

        val state = vm.connectReceiver()

        state.askNotifications shouldBe false
        state.notificationsDenied shouldBe false
    }

    @Test
    fun `S004 RF-6 si se ha denegado la conexion sigue y Receptor avisa de como concederlo`() {
        preferences.notificationsAskedFlow.value = true
        val vm = viewModel(FakeNotificationPermission(required = true))

        vm.connectReceiver()

        val state = vm.awaitState { it.notificationsDenied }
        state.askNotifications shouldBe false
        state.snapshot.connection shouldBe ConnectionState.Receiving("reach-RS3")
    }

    @Test
    fun `S004 RF-6 al conceder el permiso desde los ajustes desaparece el aviso`() {
        preferences.notificationsAskedFlow.value = true
        val permission = FakeNotificationPermission(required = true)
        val vm = viewModel(permission)
        vm.connectReceiver()
        vm.awaitState { it.notificationsDenied }

        permission.granted = true
        vm.refreshNotifications()

        vm.awaitState { !it.notificationsDenied }
    }

    private fun show(state: ReceiverUiState, actions: ReceiverActions) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private val receiving = ReceiverUiState(
        bluetooth = BluetoothUi.Ready(listOf(RS3_DEVICE)),
        snapshot = ReceiverSnapshot(ConnectionState.Receiving("reach-RS3")),
    )

    @Test
    fun `S004 RF-5 la peticion explica para que sirve el permiso y Continuar lo pide`() {
        var allowed = 0
        var declined = 0
        show(
            receiving.copy(askNotifications = true),
            ReceiverActions(onAllowNotifications = { allowed++ }, onDeclineNotifications = { declined++ }),
        )

        compose.onNodeWithText(text(R.string.notifications_permission_rationale)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.notifications_permission_continue)).performClick()

        allowed shouldBe 1
        declined shouldBe 0
    }

    @Test
    fun `S004 RF-5 Ahora no cierra la peticion sin pedir el permiso`() {
        var allowed = 0
        var declined = 0
        show(
            receiving.copy(askNotifications = true),
            ReceiverActions(onAllowNotifications = { allowed++ }, onDeclineNotifications = { declined++ }),
        )

        compose.onNodeWithText(text(R.string.notifications_permission_not_now)).performClick()

        allowed shouldBe 0
        declined shouldBe 1
    }

    @Test
    fun `S004 RF-6 el aviso de permiso denegado ofrece Activar`() {
        var enabled = 0
        show(receiving.copy(notificationsDenied = true), ReceiverActions(onEnableNotifications = { enabled++ }))

        compose.onNodeWithText(text(R.string.receiver_notifications_denied)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ENABLE_NOTIFICATIONS_TAG).performScrollTo().performClick()

        enabled shouldBe 1
    }

    @Test
    fun `S004 RF-6 Activar abre los ajustes de notificaciones de la app`() {
        val intent = notificationSettingsIntent("es.topophone.app")

        intent.action shouldBe Settings.ACTION_APP_NOTIFICATION_SETTINGS
        intent.getStringExtra(Settings.EXTRA_APP_PACKAGE) shouldBe "es.topophone.app"
    }

    private companion object {
        val CALCULATOR = GroundPointCalculator()
    }
}
