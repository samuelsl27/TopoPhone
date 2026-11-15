package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.receiver.LocalNetworkPermission
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.receiver.localNetworkSettingsIntent
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.core.model.receiver.WifiReceiverInput
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.data.receiver.WifiReceiverRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

/** El permiso de red local de Android 17+: se pide solo al conectar con un receptor Wi-Fi y, denegado, la app sigue (S021/RF-6). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionTest {

    @get:Rule
    val compose = createComposeRule()

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toApp = PipedOutputStream()
    private val bluetooth = FakeBluetooth()
    private val preferences = FakePreferences()
    private val connector = NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE }
    private val session = ReceiverSession(sessionScope, connector, noDataTimeoutMs = 60_000, watchdogPeriodMs = 1_000)
    private val receivers = OneWifiReceiver()
    private val connections = ReceiverConnections(session, bluetooth, preferences, CoroutineScope(Dispatchers.Default), receivers)

    private class FakeLocalNetwork(override val required: Boolean, var granted: Boolean = false) : LocalNetworkPermission {
        override fun isGranted() = granted
    }

    /** Un único receptor Wi-Fi guardado, en una dirección que no existe: aquí no se llega a abrir ningún socket. */
    private class OneWifiReceiver : WifiReceiverRepository {
        val receiver = WifiReceiver("r1", "RS3 hotspot", "127.0.0.1", 1, null)
        override fun receivers(): Flow<List<WifiReceiver>> = MutableStateFlow(listOf(receiver))

        override fun observe(id: String): Flow<WifiReceiver?> = receivers().map { it.firstOrNull { r -> r.id == id } }

        override suspend fun byId(id: String): WifiReceiver? = receiver.takeIf { it.id == id }

        override suspend fun add(input: WifiReceiverInput): String = error("no se usa")

        override suspend fun update(id: String, input: WifiReceiverInput): Boolean = error("no se usa")

        override suspend fun delete(id: String): Boolean = error("no se usa")
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

    private fun viewModel(permission: LocalNetworkPermission) = ReceiverViewModel(
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
        localNetwork = permission,
    )

    private fun ReceiverViewModel.awaitState(predicate: (ReceiverUiState) -> Boolean): ReceiverUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { uiState.collect {} }
        try {
            withTimeout(5_000) { uiState.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private val wifiKey = ReceiverKey.wifi("r1")

    @Test
    fun `S021 RF-6 con el permiso exigido y sin conceder, conectar con un receptor Wi-Fi lo pide y no conecta aun`() {
        val vm = viewModel(FakeLocalNetwork(required = true))
        vm.awaitState { true }.askLocalNetwork shouldBe false

        vm.connect(wifiKey)

        val state = vm.awaitState { it.askLocalNetwork }
        state.localNetworkDenied shouldBe false
        state.snapshot.connection shouldBe ConnectionState.Disconnected
        connections.currentAddress.value shouldBe null
    }

    @Test
    fun `S021 RF-6 con un receptor Bluetooth no se pide nunca`() {
        val vm = viewModel(FakeLocalNetwork(required = true))
        vm.awaitState { true }

        vm.connect(RS3_DEVICE.address)
        toApp.write(nmea("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.flush()

        val state = vm.awaitState { it.snapshot.connection is ConnectionState.Receiving }
        state.askLocalNetwork shouldBe false
        state.localNetworkDenied shouldBe false
    }

    @Test
    fun `S021 RF-6 si el sistema no lo exige o ya esta concedido no se pide y se conecta`() {
        for (permission in listOf(FakeLocalNetwork(required = false, granted = true), FakeLocalNetwork(required = true, granted = true))) {
            val vm = viewModel(permission)
            vm.awaitState { true }

            vm.connect(wifiKey)

            vm.awaitState { it.lastAddress == wifiKey }.askLocalNetwork shouldBe false
            connections.currentAddress.value shouldBe wifiKey
            session.disconnect()
        }
    }

    @Test
    fun `S021 RF-6 al conceder el permiso se conecta con el receptor que se queria`() {
        val permission = FakeLocalNetwork(required = true)
        val vm = viewModel(permission)
        vm.connect(wifiKey)
        vm.awaitState { it.askLocalNetwork }

        permission.granted = true
        vm.localNetworkAnswered(granted = true)

        val state = vm.awaitState { !it.askLocalNetwork }
        state.localNetworkDenied shouldBe false
        connections.currentAddress.value shouldBe wifiKey
    }

    @Test
    fun `S021 RF-6 al denegarlo no se conecta, se avisa y el resto de la app sigue`() {
        val vm = viewModel(FakeLocalNetwork(required = true))
        vm.connect(wifiKey)
        vm.awaitState { it.askLocalNetwork }

        vm.localNetworkAnswered(granted = false)

        val state = vm.awaitState { it.localNetworkDenied }
        state.askLocalNetwork shouldBe false
        state.snapshot.connection shouldBe ConnectionState.Disconnected
        connections.currentAddress.value shouldBe null
        // El Bluetooth sigue funcionando con el permiso denegado.
        vm.connect(RS3_DEVICE.address)
        toApp.write(nmea("GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.flush()
        vm.awaitState { it.snapshot.connection is ConnectionState.Receiving }
    }

    @Test
    fun `S021 RF-6 el aviso desaparece al conceder el permiso desde los ajustes`() {
        val permission = FakeLocalNetwork(required = true)
        val vm = viewModel(permission)
        vm.connect(wifiKey)
        vm.localNetworkAnswered(granted = false)
        vm.awaitState { it.localNetworkDenied }

        permission.granted = true
        vm.refreshLocalNetwork()

        vm.awaitState { !it.localNetworkDenied }
    }

    @Test
    fun `S021 RF-6 el aviso sigue mientras no se conceda al volver de los ajustes`() {
        val vm = viewModel(FakeLocalNetwork(required = true))
        vm.connect(wifiKey)
        vm.localNetworkAnswered(granted = false)
        vm.awaitState { it.localNetworkDenied }

        vm.refreshLocalNetwork()

        vm.awaitState { true }.localNetworkDenied shouldBe true
    }

    @Test
    fun `S021 RF-6 los ajustes de la app son los de su paquete`() {
        val intent = localNetworkSettingsIntent("es.topophone.app")

        intent.action shouldBe android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        intent.data.toString() shouldBe "package:es.topophone.app"
    }

    private fun show(state: ReceiverUiState, actions: ReceiverActions) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private val list = ReceiverUiState(
        bluetooth = BluetoothUi.Ready(listOf(RS3_DEVICE)),
        snapshot = ReceiverSnapshot(ConnectionState.Disconnected),
    )

    @Test
    fun `S021 RF-6 la peticion explica para que sirve el permiso y Continuar lo pide`() {
        var allowed = 0
        var declined = 0
        show(
            list.copy(askLocalNetwork = true),
            ReceiverActions(onAllowLocalNetwork = { allowed++ }, onDeclineLocalNetwork = { declined++ }),
        )

        compose.onNodeWithText(text(R.string.local_network_permission_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.local_network_permission_rationale)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.local_network_permission_continue)).performClick()
        allowed shouldBe 1
        declined shouldBe 0
    }

    @Test
    fun `S021 RF-6 Ahora no cierra la peticion como una denegacion`() {
        var declined = 0
        show(list.copy(askLocalNetwork = true), ReceiverActions(onDeclineLocalNetwork = { declined++ }))

        compose.onNodeWithText(text(R.string.local_network_permission_not_now)).performClick()

        declined shouldBe 1
    }

    @Test
    fun `S021 RF-6 denegado, la lista explica el motivo y lleva a los ajustes`() {
        var opened = 0
        show(list.copy(localNetworkDenied = true), ReceiverActions(onOpenLocalNetworkSettings = { opened++ }))

        compose.onNodeWithText(text(R.string.local_network_denied)).assertIsDisplayed()
        compose.onNodeWithTag(LOCAL_NETWORK_SETTINGS_TAG).performScrollTo().performClick()
        opened shouldBe 1
    }

    @Test
    fun `S021 RF-6 sin denegar no hay aviso ni peticion`() {
        show(list, ReceiverActions())

        compose.onNodeWithText(text(R.string.local_network_denied)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.local_network_permission_title)).assertDoesNotExist()
    }

    private companion object {
        val CALCULATOR = GroundPointCalculator()
    }
}
