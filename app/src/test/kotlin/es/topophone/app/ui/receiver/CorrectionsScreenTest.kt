package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.rtcm.RtcmStats
import es.topophone.core.model.ntrip.NtripProfile
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Pantalla Correcciones con perfiles (S007/RF-1, RF-6, RF-7, RF-22, RF-25; S002/RF-21, RF-25). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CorrectionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val ign = NtripProfile(
        "ign",
        NtripProfileDraft("ERGNSS (IGN): red", "ergnss-tr.ign.es", 2101, false, "sgarcia", "VRS3M", true),
        hasPassword = true,
        createdAtMs = 1,
        modifiedAtMs = 1,
    )
    private val station = NtripProfile(
        "rgapa",
        NtripProfileDraft("RGAPA", "rgapa.cartografia.asturias.es", 2101, false, "anonimo", "", null),
        hasPassword = true,
        createdAtMs = 1,
        modifiedAtMs = 1,
    )

    private val connected = ReceiverUiState(snapshot = ReceiverSnapshot(ConnectionState.Receiving("reach-RS3")))

    private val calls = mutableListOf<String>()
    private val actions = CorrectionsActions(
        onConnect = { calls += "connect:$it" },
        onStop = { calls += "stop" },
        onNewProfile = { calls += "new" },
        onEditProfile = { calls += "edit:$it" },
        onDeleteProfile = { calls += "delete:$it" },
        onSubmitPassword = { id, password -> calls += "password:$id:$password" },
        onDismissPassword = { calls += "dismiss" },
    )

    private fun show(state: CorrectionsUiState, receiver: ReceiverUiState = connected) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CorrectionsScreen(ThemeState.DEFAULT, {}, {}, receiver, state, actions) }
        }
    }

    @Test
    fun `S007 RF-1 la pantalla lista los perfiles con su nombre, servidor, puerto y punto de montaje`() {
        show(CorrectionsUiState(profiles = listOf(ign, station)))

        compose.onNodeWithTag(profileTag("ign"))
            .assert(hasText("ERGNSS (IGN): red", substring = true))
            .assert(hasText("ergnss-tr.ign.es:2101", substring = true))
            .assert(hasText("VRS3M", substring = true))
        compose.onNodeWithTag(profileTag("rgapa")).assert(hasText(text(R.string.corrections_no_mountpoint), substring = true))
    }

    @Test
    fun `S007 RF-1 sin perfiles se dice y se ofrece crear uno`() {
        show(CorrectionsUiState())

        compose.onNodeWithTag(NO_PROFILES_TAG).assert(hasText(text(R.string.corrections_no_profiles)))
        compose.onNodeWithTag(NEW_PROFILE_TAG).performClick()
        calls shouldBe listOf("new")
    }

    @Test
    fun `S007 RF-22 al pulsar un perfil se conecta con el`() {
        show(CorrectionsUiState(profiles = listOf(ign, station)))

        compose.onNodeWithTag(profileTag("rgapa")).performScrollTo().performClick()

        calls shouldBe listOf("connect:rgapa")
    }

    @Test
    fun `S007 RF-25 mientras se reintenta se ve el intento y Detener la para`() {
        val receiver = connected.copy(snapshot = connected.snapshot.copy(ntrip = NtripState.Reconnecting(3, NtripFailure.NO_DATA, 0)))
        show(CorrectionsUiState(profiles = listOf(ign), activeProfileId = "ign"), receiver)

        compose.onNodeWithTag(CORRECTIONS_STATUS_TAG).assert(hasText(text(R.string.ntrip_reconnecting, 3)))
        compose.onNodeWithTag(CORRECTIONS_ACTIVE_PROFILE_TAG).assert(hasText("ERGNSS (IGN): red", substring = true))
        compose.onNodeWithTag(CORRECTIONS_STOP_TAG).performClick()

        calls shouldBe listOf("stop")
    }

    @Test
    fun `S002 RF-21 S002 RF-25 el estado dice los rechazos y, recibiendo, los bytes y la ultima correccion`() {
        var receiver by mutableStateOf(
            connected.copy(snapshot = connected.snapshot.copy(ntrip = NtripState.Stopped(NtripFailure.UNAUTHORIZED))),
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CorrectionsScreen(ThemeState.DEFAULT, {}, {}, receiver, CorrectionsUiState(), actions) }
        }
        compose.onNodeWithTag(CORRECTIONS_STATUS_TAG).assert(hasText(text(R.string.ntrip_error_unauthorized)))
        compose.onNodeWithTag(CORRECTIONS_STOP_TAG).assertDoesNotExist()

        receiver = connected.copy(snapshot = connected.snapshot.copy(ntrip = NtripState.Streaming(12_345, 1_000)), nowMs = 4_000)

        compose.onNodeWithTag(NTRIP_BYTES_TAG).assert(hasText("12.345", substring = true))
        compose.onNodeWithTag(NTRIP_LAST_CORRECTION_TAG).assert(hasText(text(R.string.ntrip_last_correction, 3)))
        compose.onNodeWithTag(CORRECTIONS_STOP_TAG).assertIsDisplayed()
    }

    @Test
    fun `S007 RF-7 borrar un perfil pide confirmacion`() {
        show(CorrectionsUiState(profiles = listOf(ign)))

        compose.onNodeWithTag(profileMenuTag("ign")).performClick()
        compose.onNodeWithText(text(R.string.corrections_delete)).performClick()
        compose.onNodeWithText(text(R.string.corrections_delete_confirm, "ERGNSS (IGN): red")).assertIsDisplayed()
        calls shouldBe emptyList()

        compose.onNodeWithTag(CONFIRM_TAG).performClick()

        calls shouldBe listOf("delete:ign")
    }

    @Test
    fun `S007 RF-2 Editar abre el perfil`() {
        show(CorrectionsUiState(profiles = listOf(ign)))

        compose.onNodeWithTag(profileMenuTag("ign")).performClick()
        compose.onNodeWithText(text(R.string.corrections_edit)).performClick()

        calls shouldBe listOf("edit:ign")
    }

    @Test
    fun `S007 RF-6 si la contrasena guardada no se puede leer se pide de nuevo`() {
        show(CorrectionsUiState(profiles = listOf(ign), passwordNeededFor = "ign"))

        compose.onNodeWithText(text(R.string.corrections_password_lost)).assertIsDisplayed()
        compose.onNodeWithTag(PASSWORD_PROMPT_FIELD_TAG).performTextReplacement("nueva")
        compose.onNodeWithTag(PASSWORD_PROMPT_CONNECT_TAG).performClick()

        calls shouldBe listOf("password:ign:nueva")
    }

    @Test
    fun `S005 RF-16 sin receptor conectado la pantalla lo dice`() {
        show(CorrectionsUiState(profiles = listOf(ign)), receiver = ReceiverUiState())

        compose.onNodeWithTag(NO_RECEIVER_TAG).assertIsDisplayed()
    }

    private fun withNtrip(ntrip: NtripState, internet: Boolean = true) =
        connected.copy(snapshot = connected.snapshot.copy(ntrip = ntrip), internetAvailable = internet)

    @Test
    fun `S002 RF-22 si el punto de montaje no existe se dice`() {
        show(CorrectionsUiState(), withNtrip(NtripState.Stopped(NtripFailure.MOUNTPOINT_NOT_FOUND)))

        compose.onNodeWithTag(CORRECTIONS_STATUS_TAG).assert(hasText(text(R.string.ntrip_error_mountpoint)))
    }

    @Test
    fun `S002 RF-23 sin poder contactar con el caster y sin internet se dice que falta internet`() {
        show(CorrectionsUiState(), withNtrip(NtripState.Reconnecting(1, NtripFailure.UNREACHABLE, 0), internet = false))

        compose.onNodeWithText(text(R.string.ntrip_no_internet)).assertIsDisplayed()
    }

    @Test
    fun `S002 RF-23 con internet no se culpa a la red`() {
        show(CorrectionsUiState(), withNtrip(NtripState.Reconnecting(1, NtripFailure.UNREACHABLE, 0), internet = true))

        compose.onNodeWithText(text(R.string.ntrip_no_internet)).assertDoesNotExist()
    }

    @Test
    fun `S002 RF-24 si dejan de llegar correcciones se avisa y se reconecta`() {
        show(CorrectionsUiState(), withNtrip(NtripState.Reconnecting(1, NtripFailure.NO_DATA, 0)))

        compose.onNodeWithTag(CORRECTIONS_STATUS_TAG).assert(hasText(text(R.string.ntrip_reconnecting, 1)))
        compose.onNodeWithTag(CORRECTIONS_STOP_TAG).assertIsDisplayed()
    }

    @Test
    fun `S002 RF-28 sin receptor se dice que antes hay que conectarlo`() {
        show(CorrectionsUiState(), withNtrip(NtripState.ReceiverNotConnected))

        compose.onNodeWithTag(CORRECTIONS_STATUS_TAG).assert(hasText(text(R.string.ntrip_receiver_not_connected)))
    }

    @Test
    fun `S004 RF-18 mientras se reconecta el receptor el NTRIP esta en pausa y se puede detener`() {
        show(CorrectionsUiState(), withNtrip(NtripState.Paused))

        compose.onNodeWithTag(CORRECTIONS_STATUS_TAG).assert(hasText(text(R.string.ntrip_paused)))
        compose.onNodeWithTag(CORRECTIONS_STOP_TAG).performClick()
        calls shouldBe listOf("stop")
    }

    @Test
    fun `S007 RF-32 recibiendo se ve cada tipo de mensaje RTCM con su numero, su nombre si se conoce y cuantos`() {
        val rtcm = RtcmStats(mapOf(1006 to 2L, 1077 to 1_024L, 4091 to 1L))
        show(CorrectionsUiState(), withNtrip(NtripState.Streaming(5_000, 1_000, rtcm)))

        compose.onNodeWithTag(rtcmTag(1077)).performScrollTo()
            .assert(hasText("1077", substring = true))
            .assert(hasText(text(R.string.rtcm_msm, 7, text(R.string.gnss_gps)), substring = true))
            .assert(hasText("1.024", substring = true))
        compose.onNodeWithTag(rtcmTag(1006)).assert(hasText(text(R.string.rtcm_reference_station), substring = true))
        compose.onNodeWithTag(rtcmTag(4091)).assert(hasText("4091", substring = true)).assert(hasText("1", substring = true))
    }

    @Test
    fun `S007 RF-33 se ve el total de mensajes con el CRC erroneo`() {
        show(CorrectionsUiState(), withNtrip(NtripState.Streaming(5_000, 1_000, RtcmStats(mapOf(1077 to 10L), crcErrors = 3))))

        compose.onNodeWithTag(RTCM_ERRORS_TAG).performScrollTo().assert(hasText(text(R.string.rtcm_crc_errors, 3)))
    }

    @Test
    fun `S007 RF-32 sin correcciones no hay tarjeta de mensajes RTCM`() {
        show(CorrectionsUiState(), withNtrip(NtripState.Idle))

        compose.onNodeWithTag(RTCM_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun `S007 RF-32 cada tipo conocido tiene su nombre`() {
        val kinds = listOf(1001, 1009, 1005, 1007, 1033, 1019, 1020, 1042, 1044, 1045, 1230, 1071, 1081, 1091, 1101, 1111, 1121, 1131)
        val rtcm = RtcmStats(kinds.associateWith { 1L })
        show(CorrectionsUiState(), withNtrip(NtripState.Streaming(1, 1, rtcm)))

        compose.onNodeWithTag(
            rtcmTag(1020),
        ).performScrollTo().assert(hasText(text(R.string.rtcm_ephemeris, text(R.string.gnss_glonass)), substring = true))
        compose.onNodeWithTag(
            rtcmTag(1131),
        ).performScrollTo().assert(hasText(text(R.string.rtcm_msm, 1, text(R.string.gnss_navic)), substring = true))
        compose.onNodeWithTag(rtcmTag(1230)).performScrollTo().assert(hasText(text(R.string.rtcm_glonass_biases), substring = true))
    }
}
