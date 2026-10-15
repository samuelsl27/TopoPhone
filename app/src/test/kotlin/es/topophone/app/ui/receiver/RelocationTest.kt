package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.sessions.SESSIONS_LIST_TAG
import es.topophone.app.ui.sessions.SHARE_SESSION_TAG
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cada función de la antigua sección Receptor de S002 a S004, en su sitio nuevo (S005/RF-19). */
@RunWith(AndroidJUnit4::class)
class RelocationTest {

    @get:Rule
    val compose = createComposeRule()

    private val fix = GgaFix("101530.00", 40.4, -3.7, SolutionStatus.FIX, 24, 0.6, 650.0, 51.0, 1.0, "")
    private val connected = ReceiverUiState(
        bluetooth = BluetoothUi.Ready(listOf(RS3_DEVICE)),
        snapshot = ReceiverSnapshot(ConnectionState.Receiving("reach-RS3"), gga = fix, recording = true, nmeaMissing = true),
        poleHeightText = "1,800",
        pairingSupported = true,
    )
    private var state by mutableStateOf(connected)

    @Before
    fun openReceiver() {
        compose.setContent { StatefulTestApp(receiverState = state) }
        compose.navItem(TopLevelDestination.RECEIVER).performClick()
    }

    private fun inside(tag: String, container: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(container)))

    private fun open(entry: String) = compose.onNodeWithTag(entry).performScrollTo().performClick()

    @Test
    fun `S005 RF-19 sin conexion la lista tiene el Bluetooth, los emparejados, Anadir receptor, los fallos y las sesiones`() {
        state = connected.copy(snapshot = ReceiverSnapshot(ConnectionState.Failed(ConnectFailure.TIMEOUT)))
        compose.waitForIdle()

        listOf(CONNECTION_STATUS_TAG, deviceTag(RS3_DEVICE.address), ADD_RECEIVER_TAG, LIST_SESSIONS_TAG).forEach {
            inside(it, RECEIVER_LIST_TAG).assertExists()
        }
    }

    @Test
    fun `S005 RF-19 con conexion la cabecera tiene el estado, Desconectar, los avisos y Grabando`() {
        listOf(CONNECTION_STATUS_TAG, DISCONNECT_TAG, NMEA_MISSING_TAG, RECORDING_TAG).forEach {
            inside(it, RECEIVER_HEADER_TAG).assertExists()
        }
    }

    @Test
    fun `S005 RF-19 la solucion, las precisiones y la edad estan en la pantalla Estado`() {
        compose.onNodeWithTag(SOLUTION_STATUS_TAG).assertDoesNotExist()

        open(MENU_STATUS_TAG)

        listOf(SOLUTION_STATUS_TAG, STATUS_SATELLITES_TAG, STATUS_CORRECTION_AGE_TAG, STATUS_SIGMA_H_TAG).forEach {
            inside(it, RECEIVER_STATUS_SCREEN_TAG).assertExists()
        }
    }

    @Test
    fun `S005 RF-19 la altura del jalon y las coordenadas estan en la pantalla Coordenadas`() {
        compose.onNodeWithTag(POLE_HEIGHT_TAG).assertDoesNotExist()

        open(MENU_COORDINATES_TAG)

        inside(POLE_HEIGHT_TAG, RECEIVER_COORDINATES_SCREEN_TAG).assertExists()
    }

    @Test
    fun `S005 RF-19 el NTRIP esta en la pantalla Correcciones y su edad en el resumen de la entrada`() {
        compose.onNodeWithTag(NTRIP_HOST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MENU_CORRECTIONS_TAG).performScrollTo().assert(hasText("1,0", substring = true))

        open(MENU_CORRECTIONS_TAG)

        listOf(NTRIP_HOST_TAG, NTRIP_MOUNTPOINT_TAG, NTRIP_STATUS_TAG, NTRIP_CONNECT_TAG).forEach {
            inside(it, RECEIVER_CORRECTIONS_SCREEN_TAG).assertExists()
        }
    }

    @Test
    fun `S005 RF-19 compartir la ultima sesion esta en la pantalla Sesiones grabadas, desde el menu y desde la lista`() {
        compose.onNodeWithTag(SHARE_SESSION_TAG).assertDoesNotExist()

        open(MENU_SESSIONS_TAG)
        inside(SHARE_SESSION_TAG, SESSIONS_LIST_TAG).assertExists()

        compose.onNodeWithTag(es.topophone.core.designsystem.component.NAVIGATE_BACK_TAG).performClick()
        state = connected.copy(snapshot = ReceiverSnapshot())
        compose.waitForIdle()
        open(LIST_SESSIONS_TAG)
        inside(SHARE_SESSION_TAG, SESSIONS_LIST_TAG).assertExists()
    }
}
