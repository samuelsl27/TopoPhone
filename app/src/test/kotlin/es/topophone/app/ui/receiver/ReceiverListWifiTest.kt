package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.receiver.ReceiverConnections
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
import es.topophone.data.receiver.SavedReceiver
import es.topophone.data.receiver.WifiReceiverRepository
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayController
import io.kotest.matchers.shouldBe
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Los receptores Wi-Fi en la lista de receptores y la hoja «Bluetooth / Wi-Fi (TCP)» de «Añadir receptor» (S021/RF-1, RF-4). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ReceiverListWifiTest {

    @get:Rule
    val compose = createComposeRule()

    private val hotspot = WifiReceiver("w1", "RS3 hotspot", "192.168.42.1", 9001, null)
    private val casa = WifiReceiver("w2", "RS3 casa", "reach.local", 9001, 9002)

    private fun show(state: ReceiverUiState, actions: ReceiverActions = ReceiverActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private fun list(bluetooth: BluetoothUi = BluetoothUi.Ready(listOf(RS3_DEVICE)), last: String? = null) = ReceiverUiState(
        bluetooth = bluetooth,
        snapshot = ReceiverSnapshot(ConnectionState.Disconnected),
        lastReceiverAddress = last,
        pairingSupported = true,
        wifiReceivers = listOf(hotspot, casa),
    )

    @Test
    fun `S021 RF-4 la lista muestra los receptores Wi-Fi con su direccion y puerto junto a los emparejados`() {
        show(list())

        compose.onNodeWithTag(deviceTag(RS3_DEVICE.address)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(wifiReceiverTag("w1")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("RS3 hotspot").assertIsDisplayed()
        compose.onNodeWithText("192.168.42.1:9001").assertIsDisplayed()
        compose.onNodeWithText("RS3 casa").assertIsDisplayed()
        compose.onNodeWithText("reach.local:9001 · ${text(R.string.receiver_wifi_corrections_port, 9002)}").assertIsDisplayed()
    }

    @Test
    fun `S021 RF-4 los receptores Wi-Fi salen tambien con el Bluetooth apagado, sin permiso o sin Bluetooth`() {
        var shown by mutableStateOf(list(BluetoothUi.Disabled))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverScreen(ThemeState.DEFAULT, {}, shown, ReceiverActions()) }
        }

        for (bluetooth in listOf(BluetoothUi.Disabled, BluetoothUi.PermissionNeeded, BluetoothUi.Unsupported)) {
            shown = list(bluetooth)
            compose.waitForIdle()

            compose.onNodeWithTag(wifiReceiverTag("w1")).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `S021 RF-4 sin receptores Wi-Fi no hay seccion de Wi-Fi`() {
        show(list().copy(wifiReceivers = emptyList()))

        compose.onNodeWithText(text(R.string.receiver_wifi_title)).assertDoesNotExist()
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S021 RF-7 pulsar un receptor Wi-Fi conecta con su clave wifi`() {
        var connected: String? = null
        show(list(), ReceiverActions(onConnect = { connected = it }))

        compose.onNodeWithTag(wifiReceiverTag("w2")).performScrollTo().performClick()

        connected shouldBe ReceiverKey.wifi("w2")
    }

    @Test
    fun `S021 RF-4 Editar abre el formulario de ese receptor`() {
        var edited: String? = null
        show(list(), ReceiverActions(onEditWifiReceiver = { edited = it }))

        compose.onNodeWithTag(wifiEditTag("w1")).performScrollTo().performClick()

        edited shouldBe "w1"
    }

    @Test
    fun `S021 RF-4 el ultimo receptor Wi-Fi usado va marcado`() {
        show(list(last = ReceiverKey.wifi("w2")))

        compose.onNodeWithTag(wifiReceiverTag("w2")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.receiver_last_used)).assertIsDisplayed() // solo uno: el de casa
    }

    @Test
    fun `S021 RF-1 Anadir receptor deja elegir entre Bluetooth y Wi-Fi`() {
        show(list())

        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()

        compose.onAllNodesWithText(text(R.string.receiver_add_type_title)).assertCountEquals(2) // el botón y el título de la hoja
        compose.onNodeWithTag(ADD_BLUETOOTH_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ADD_WIFI_TAG).assertIsDisplayed()
    }

    @Test
    fun `S021 RF-1 elegir Wi-Fi abre el formulario y no empareja`() {
        var wifi = 0
        var pairing = 0
        show(list(), ReceiverActions(onAddWifiReceiver = { wifi++ }, onAddReceiver = { pairing++ }))
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(ADD_WIFI_TAG).performClick()

        wifi shouldBe 1
        pairing shouldBe 0
        compose.onNodeWithTag(ADD_WIFI_TAG).assertDoesNotExist() // la hoja se cierra
    }

    @Test
    fun `S021 RF-1 elegir Bluetooth sigue como hasta ahora, con la busqueda del sistema`() {
        var wifi = 0
        var pairing = 0
        show(list(), ReceiverActions(onAddWifiReceiver = { wifi++ }, onAddReceiver = { pairing++ }))
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(ADD_BLUETOOTH_TAG).performClick()

        pairing shouldBe 1
        wifi shouldBe 0
    }

    @Test
    fun `S021 RF-1 sin busqueda del sistema Bluetooth abre los ajustes de Bluetooth`() {
        var settings = 0
        var pairing = 0
        show(
            list().copy(pairingSupported = false),
            ReceiverActions(onAddReceiver = {
                pairing++
            }, onOpenBluetoothSettings = { settings++ }),
        )
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(ADD_BLUETOOTH_TAG).performClick()

        settings shouldBe 1
        pairing shouldBe 0
    }

    @Test
    fun `S021 RF-1 Cancelar cierra la hoja sin hacer nada`() {
        var wifi = 0
        var pairing = 0
        show(list(), ReceiverActions(onAddWifiReceiver = { wifi++ }, onAddReceiver = { pairing++ }))
        compose.onNodeWithTag(ADD_RECEIVER_TAG).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.receiver_add_cancel)).performClick()

        wifi shouldBe 0
        pairing shouldBe 0
        compose.onNodeWithTag(ADD_WIFI_TAG).assertDoesNotExist()
    }

    /** Un repositorio en memoria para el ViewModel. */
    private class MemoryWifiReceivers : WifiReceiverRepository {
        val list = MutableStateFlow<List<WifiReceiver>>(emptyList())

        override fun receivers(): Flow<List<WifiReceiver>> = list

        override fun observe(id: String): Flow<WifiReceiver?> = list.map { rows -> rows.firstOrNull { it.id == id } }

        override suspend fun byId(id: String): WifiReceiver? = list.value.firstOrNull { it.id == id }

        override suspend fun add(input: WifiReceiverInput): String {
            val id = "m${list.value.size + 1}"
            list.value += WifiReceiver(id, input.name, input.host, input.positionPort, input.correctionsPort)
            return id
        }

        override suspend fun update(id: String, input: WifiReceiverInput): Boolean = false

        override suspend fun delete(id: String): Boolean {
            val had = list.value.any { it.id == id }
            list.value = list.value.filterNot { it.id == id }
            return had
        }
    }

    @Test
    fun `S021 RF-4 el ViewModel da los receptores Wi-Fi guardados y el ultimo usado`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = MemoryWifiReceivers()
            val preferences = FakePreferences().apply { lastReceiverFlow.value = SavedReceiver(ReceiverKey.wifi("m1"), "RS3 (x)") }
            val session = ReceiverSession(scope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
            val bluetooth = FakeBluetooth()
            val vm = ReceiverViewModel(
                session = session,
                bluetooth = bluetooth,
                preferences = preferences,
                calculator = { GroundPointCalculator() },
                network = { true },
                alerts = NoAlerts,
                clock = { System.nanoTime() / 1_000_000 },
                samplePeriodMs = 0,
                replay = ReplayController(session) { emptyList() },
                connections = ReceiverConnections(session, bluetooth, preferences, CoroutineScope(Dispatchers.Default)),
                wifiReceivers = repository,
            )
            fun await(predicate: (ReceiverUiState) -> Boolean) = runBlocking {
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.uiState.collect {} }
                try {
                    withTimeout(5_000) { vm.uiState.first(predicate) }
                } finally {
                    collector.cancel()
                }
            }

            await { true }.wifiReceivers shouldBe emptyList()
            runBlocking { repository.add(WifiReceiverInput("RS3", "192.168.42.1", 9001, null)) }

            val state = await { it.wifiReceivers.isNotEmpty() }
            state.wifiReceivers.single().name shouldBe "RS3"
            state.lastReceiverAddress shouldBe ReceiverKey.wifi("m1")
            runBlocking { repository.delete("m1") }
            await { it.wifiReceivers.isEmpty() }
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
