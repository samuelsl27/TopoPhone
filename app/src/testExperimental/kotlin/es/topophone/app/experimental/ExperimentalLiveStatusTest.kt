package es.topophone.app.experimental

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.receiver.experimental.ReachLiveState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** S044/RF-13, RF-16, RF-17: batería y logs en la pantalla del receptor, «Sin estado del receptor» y el reintento. Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ExperimentalLiveStatusTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = TestScope()
    private val key = MutableStateFlow<String?>("wifi:a")
    private val gateway = FakeReachGateway()
    private val session = ExperimentalSession(
        active = MutableStateFlow(true),
        key = key,
        wifi = FakeWifiRepository(WifiReceiver("a", "RS3", "192.168.42.1", 9001, null)),
        equipment = FakeEquipmentRepository(),
        gateway = gateway,
        scope = scope.backgroundScope,
    )

    private val open = """0{"sid":"x","pingInterval":25000,"pingTimeout":60000}"""
    private val battery = """42["broadcast",{"name":"battery_status","payload":{"state_of_charge":87,"charger_status":"discharging"}}]"""
    private val logsOn = """42["broadcast",{"name":"active_logs","payload":{"raw":{"is_writing":true}}}]"""
    private val logsOff = """42["broadcast",{"name":"active_logs","payload":{"raw":{"is_writing":false}}}]"""

    private fun settle() {
        scope.runCurrent()
        compose.waitForIdle()
    }

    /** Espera a que las preferencias reales (asíncronas) digan que el módulo está activo y la cabecera lo pinte. */
    private fun awaitActiveHeader() {
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(EXPERIMENTAL_MARK_TAG).fetchSemanticsNodes().isNotEmpty() }
        settle()
    }

    private fun connection(index: Int = 0) = gateway.transports.single().second.connections[index]

    private fun deliver(index: Int, vararg frames: String) {
        frames.forEach { connection(index).listener.onFrame(it) }
        settle()
    }

    @Test
    fun `S044 RF-13 el estado en directo enseña la bateria y si hay logs escribiendo`() {
        compose.setContent { LiveStatusLines(ReachLiveState(connected = true, batteryPercent = 87, charger = "x", logsWriting = true)) }

        compose.onNodeWithTag(LIVE_BATTERY_TAG).assertTextContains(text(R.string.experimental_battery, 87))
        compose.onNodeWithTag(LIVE_LOGS_TAG).assertTextContains(text(R.string.experimental_logs_writing))
        compose.onAllNodesWithTag(LIVE_LOST_TAG).assertCountEquals(0)
    }

    @Test
    fun `S044 RF-13 los logs parados y lo que aun no se sabe se distinguen`() {
        var state by mutableStateOf(ReachLiveState(connected = true, batteryPercent = 5, logsWriting = false))
        compose.setContent { LiveStatusLines(state) }

        compose.onNodeWithTag(LIVE_LOGS_TAG).assertTextContains(text(R.string.experimental_logs_stopped))

        state = ReachLiveState(connected = true)
        compose.waitForIdle()
        compose.onNodeWithTag(LIVE_BATTERY_TAG).assertTextContains(text(R.string.experimental_battery_unknown))
        compose.onNodeWithTag(LIVE_LOGS_TAG).assertTextContains(text(R.string.experimental_logs_unknown))
    }

    @Test
    fun `S044 RF-17 sin directo se dice Sin estado del receptor y no se enseña ningun dato`() {
        compose.setContent { LiveStatusLines(ReachLiveState()) }

        compose.onNodeWithTag(LIVE_LOST_TAG).assertTextContains(text(R.string.experimental_live_lost))
        compose.onAllNodesWithTag(LIVE_BATTERY_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(LIVE_LOGS_TAG).assertCountEquals(0)
    }

    @Test
    fun `S044 RF-13 la cabecera del receptor sigue el directo y al cortarse dice Sin estado y reintenta`() {
        TestPreferences(folder.root, active = true).use { on ->
            val feature = ExperimentalFeatureImpl(on.preferences, session) { 1L }
            compose.setContent { feature.ReceiverHeaderSlot() }
            awaitActiveHeader()
            settle()
            gateway.transports.size shouldBe 1

            deliver(0, open, "40", battery, logsOn)
            compose.onNodeWithTag(LIVE_BATTERY_TAG).assertTextContains(text(R.string.experimental_battery, 87))
            compose.onNodeWithTag(LIVE_LOGS_TAG).assertTextContains(text(R.string.experimental_logs_writing))

            deliver(0, logsOff)
            compose.onNodeWithTag(LIVE_LOGS_TAG).assertTextContains(text(R.string.experimental_logs_stopped))

            connection(0).listener.onEnded()
            settle()
            compose.onNodeWithTag(LIVE_LOST_TAG).assertTextContains(text(R.string.experimental_live_lost))
            gateway.transports.single().second.connections.size shouldBe 1

            scope.advanceTimeBy(1_000)
            settle()
            gateway.transports.single().second.connections.size shouldBe 2
            deliver(1, open, "40", battery)
            compose.onNodeWithTag(LIVE_BATTERY_TAG).assertTextContains(text(R.string.experimental_battery, 87))
            compose.onAllNodesWithTag(LIVE_LOST_TAG).assertCountEquals(0)
        }
    }

    @Test
    fun `S044 RF-16 con el movil o sin direccion la cabecera solo lleva la marca y no abre ningun directo`() {
        key.value = "phone"
        TestPreferences(folder.root, active = true).use { on ->
            val feature = ExperimentalFeatureImpl(on.preferences, session) { 1L }
            compose.setContent { feature.ReceiverHeaderSlot() }
            awaitActiveHeader()
            settle()

            compose.onNodeWithTag(EXPERIMENTAL_MARK_TAG).assertTextContains(text(R.string.experimental_mark))
            compose.onAllNodesWithTag(LIVE_LOST_TAG).assertCountEquals(0)
            gateway.transports.size shouldBe 0
        }
    }

    @Test
    fun `S044 RF-16 al salir de la pantalla el directo se cierra y deja de reintentar`() {
        var visible by mutableStateOf(true)
        TestPreferences(folder.root, active = true).use { on ->
            val feature = ExperimentalFeatureImpl(on.preferences, session) { 1L }
            compose.setContent { if (visible) feature.ReceiverHeaderSlot() }
            awaitActiveHeader()
            settle()
            deliver(0, open, "40", battery)

            visible = false
            settle()
            scope.advanceTimeBy(60_000)
            settle()

            connection(0).closed shouldBe true
            gateway.transports.size shouldBe 1
            session.live.value shouldBe ReachLiveState()
        }
    }

    @Test
    fun `S044 RF-16 dos pantallas a la vez comparten un solo directo y se cierra con la ultima`() {
        session.startLive()
        session.startLive()
        settle()
        gateway.transports.size shouldBe 1

        session.stopLive()
        settle()
        connection(0).closed shouldBe false

        session.stopLive()
        settle()
        connection(0).closed shouldBe true
    }

    @Test
    fun `S044 RF-16 parar mas veces de las que se arranco no deja el contador en negativo`() {
        session.stopLive()
        session.stopLive()
        session.startLive()
        settle()

        gateway.transports.size shouldBe 1
    }
}
