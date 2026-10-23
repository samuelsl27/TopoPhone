package es.topophone.app.ui.receiver

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.component.StatusToneKey
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ReplayInfo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Menú de la pantalla del receptor y sus resúmenes (S005/RF-9 a RF-13). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class ReceiverMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val fix = GgaFix("101530.00", 40.4, -3.7, SolutionStatus.FIX, 24, 0.6, 650.0, 51.0, 1.0, "0001")
    private val receiving = ReceiverSnapshot(ConnectionState.Receiving("reach-RS3"), gga = fix)

    private fun show(snapshot: ReceiverSnapshot, poleHeightText: String = "1,800", actions: ReceiverActions = ReceiverActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(ThemeState.DEFAULT, {}, ReceiverUiState(snapshot = snapshot, poleHeightText = poleHeightText), actions)
            }
        }
    }

    private fun entry(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun `S005 RF-9 bajo la cabecera hay cinco entradas en orden, con Inclinacion tras Coordenadas`() {
        show(receiving)

        val header = compose.onNodeWithTag(RECEIVER_HEADER_TAG).getBoundsInRoot()
        val tops = listOf(
            MENU_STATUS_TAG to R.string.receiver_menu_status,
            MENU_COORDINATES_TAG to R.string.receiver_menu_coordinates,
            MENU_TILT_TAG to R.string.receiver_menu_tilt,
            MENU_CORRECTIONS_TAG to R.string.receiver_menu_corrections,
            MENU_SESSIONS_TAG to R.string.receiver_menu_sessions,
        ).map { (tag, title) ->
            val node = entry(tag).assert(hasText(text(title)))
            val bounds = node.getBoundsInRoot()
            (bounds.bottom - bounds.top >= 56.dp) shouldBe true // RNF de la spec
            bounds.top
        }
        (header.bottom <= tops.first()) shouldBe true
        tops shouldBe tops.sorted()
    }

    @Test
    fun `S005 RF-10 la entrada Estado resume la solucion con su color y los satelites usados`() {
        show(receiving)

        entry(MENU_STATUS_TAG).assert(hasText(text(R.string.status_bar_satellites, 24)))
        // El chip de la solución es un nodo propio, con su color y su nombre (RNF-T8).
        compose.onNode(hasText(text(R.string.solution_fix)) and hasAnyAncestor(hasTestTag(MENU_STATUS_TAG)))
            .assert(SemanticsMatcher.expectValue(StatusToneKey, StatusTone.POSITIVE))
    }

    @Test
    fun `S005 RF-10 sin epocas la entrada Estado dice Sin datos`() {
        show(ReceiverSnapshot(ConnectionState.NoData("reach-RS3"), gga = fix))

        entry(MENU_STATUS_TAG).assert(hasText(text(R.string.status_bar_no_data)))
    }

    @Test
    fun `S005 RF-11 la entrada Coordenadas resume la altura del jalon en uso`() {
        show(receiving, poleHeightText = "2,000")

        entry(MENU_COORDINATES_TAG).assert(hasText(text(R.string.receiver_menu_pole_height, "2,000")))
    }

    @Test
    fun `S005 RF-12 la entrada Correcciones resume el estado del NTRIP y la edad de las correcciones`() {
        show(receiving.copy(ntrip = NtripState.Streaming(bytesReceived = 1_024, lastCorrectionAtMs = 0)))

        entry(
            MENU_CORRECTIONS_TAG,
        ).assert(hasText(text(R.string.receiver_menu_corrections_age, text(R.string.ntrip_state_streaming), "1,0")))
    }

    @Test
    fun `S005 RF-12 sin correcciones la entrada Correcciones solo da el estado del NTRIP`() {
        show(receiving.copy(gga = fix.copy(correctionAgeS = null), ntrip = NtripState.Paused))
        entry(MENU_CORRECTIONS_TAG).assert(hasText(text(R.string.ntrip_paused)))
    }

    @Test
    fun `S005 RF-12 durante una reproduccion Correcciones no esta disponible y no da la edad grabada`() {
        val replay = ReplayInfo("sesion", ReplaySpeed.X1, loop = false)
        show(ReceiverSnapshot(ConnectionState.Receiving("sesion"), gga = fix, replay = replay))

        entry(MENU_CORRECTIONS_TAG)
            .assert(hasText(text(R.string.ntrip_unavailable_in_replay)))
            .assert(hasText("1,0", substring = true).not())
    }

    @Test
    fun `S005 RF-13 mientras se graba la entrada Sesiones grabadas dice Grabando`() {
        show(receiving.copy(recording = true))
        entry(MENU_SESSIONS_TAG).assert(hasText(text(R.string.status_bar_recording)))
    }

    @Test
    fun `S005 RF-13 sin grabar la entrada Sesiones grabadas no dice Grabando`() {
        show(receiving)
        entry(MENU_SESSIONS_TAG).assert(hasText(text(R.string.status_bar_recording)).not())
    }

    @Test
    fun `S005 RF-9 cada entrada pide abrir su pantalla`() {
        val opened = mutableListOf<String>()
        show(
            receiving,
            actions = ReceiverActions(
                onOpenStatus = { opened += "estado" },
                onOpenCoordinates = { opened += "coordenadas" },
                onOpenCorrections = { opened += "correcciones" },
                onOpenSessions = { opened += "sesiones" },
            ),
        )

        listOf(MENU_STATUS_TAG, MENU_COORDINATES_TAG, MENU_CORRECTIONS_TAG, MENU_SESSIONS_TAG).forEach { entry(it).performClick() }

        opened shouldBe listOf("estado", "coordenadas", "correcciones", "sesiones")
    }

    @Test
    fun `S016 RF-1 la entrada Inclinacion resume el modo y abre su apartado`() {
        var opened = false
        val tilt = es.topophone.app.receiver.CurrentTiltState(
            key = "00:11:22:33:44:55",
            settings = es.topophone.data.receiver.ReceiverTiltSettings(es.topophone.core.model.point.TiltMode.APP, verified = false),
        )
        val actions = ReceiverActions(onOpenTilt = { opened = true })
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(ThemeState.DEFAULT, {}, ReceiverUiState(snapshot = receiving, tilt = tilt), actions)
            }
        }

        entry(MENU_TILT_TAG).assert(hasText(text(R.string.tilt_mode_unverified, text(R.string.tilt_mode_app)))).performClick()

        opened shouldBe true
    }

    @Test
    fun `S016 RF-2 sin modo la entrada Inclinacion dice Sin elegir`() {
        show(receiving)

        entry(MENU_TILT_TAG).assert(hasText(text(R.string.tilt_mode_none)))
    }
}
