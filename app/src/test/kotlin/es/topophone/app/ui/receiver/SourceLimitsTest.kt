package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.status.STATUS_INDICATOR_PHONE_TAG
import es.topophone.app.ui.status.StatusIndicator
import es.topophone.app.ui.status.StatusIndicatorState
import es.topophone.app.ui.status.StatusSource
import es.topophone.app.ui.status.toStatusIndicatorState
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.scenario.ScenarioId
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import es.topophone.receiver.session.ScenarioInfo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El GNSS del móvil no ofrece NTRIP, inclinación ni base y el receptor virtual no ofrece NTRIP: se dice el motivo en lugar del estado;
 * y una ubicación simulada por otra app se avisa (S022/RF-7, RF-9, RF-18).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SourceLimitsTest {

    @get:Rule
    val compose = createComposeRule()

    private val fix = GgaFix("101530.00", 40.4, -3.7, SolutionStatus.SINGLE, 9, null, 650.0, 0.0, null, "")

    private fun snapshot(source: PositionSource, scenario: ScenarioInfo? = null) = ReceiverSnapshot(
        connection = ConnectionState.Receiving("fuente"),
        gga = fix,
        source = source,
        scenario = scenario,
    )

    private fun show(snapshot: ReceiverSnapshot) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverScreen(ThemeState.DEFAULT, {}, ReceiverUiState(snapshot = snapshot), ReceiverActions())
            }
        }
    }

    private fun showPage(page: ReceiverPage, snapshot: ReceiverSnapshot) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverPageScreen(page, ThemeState.DEFAULT, {}, ReceiverUiState(snapshot = snapshot), ReceiverActions(), {})
            }
        }
    }

    private val virtualScenario = ScenarioInfo(ScenarioId.STABLE_FIX, ReplaySpeed.X1, 120, 5)

    @Test
    fun `S022 RF-9 con el movil el menu dice que no hay inclinacion ni correcciones`() {
        show(snapshot(PositionSource.PHONE))

        compose.onNodeWithTag(MENU_TILT_TAG).performScrollTo().assert(hasText(text(R.string.source_phone_no_tilt)))
        compose.onNodeWithTag(MENU_CORRECTIONS_TAG).performScrollTo().assert(hasText(text(R.string.ntrip_unavailable_phone)))
    }

    @Test
    fun `S022 RF-18 con el virtual el menu dice que no hay correcciones pero la inclinacion sigue`() {
        show(snapshot(PositionSource.VIRTUAL, virtualScenario))

        compose.onNodeWithTag(MENU_CORRECTIONS_TAG).performScrollTo().assert(hasText(text(R.string.ntrip_unavailable_virtual)))
        compose.onNodeWithTag(MENU_TILT_TAG).performScrollTo().assert(hasText(text(R.string.source_phone_no_tilt)).not())
    }

    @Test
    fun `S022 RF-9 con un receptor de siempre el menu no cambia`() {
        show(snapshot(PositionSource.RECEIVER))

        compose.onNodeWithTag(MENU_TILT_TAG).performScrollTo().assert(hasText(text(R.string.source_phone_no_tilt)).not())
        compose.onNodeWithTag(MENU_CORRECTIONS_TAG).performScrollTo().assert(hasText(text(R.string.ntrip_unavailable_phone)).not())
    }

    @Test
    fun `S022 RF-9 la pagina Inclinacion con el movil explica por que no hay inclinacion`() {
        showPage(ReceiverPage.TILT, snapshot(PositionSource.PHONE))

        compose.onNodeWithTag(TILT_UNAVAILABLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("El GNSS del móvil no da inclinación", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(TILT_MODE_STATE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S022 RF-9 la pagina Correcciones con el movil o el virtual dice el motivo en el estado`() {
        showPage(ReceiverPage.CORRECTIONS, snapshot(PositionSource.PHONE))

        compose.onNodeWithTag(CORRECTIONS_STATUS_TAG).assert(hasText(text(R.string.ntrip_unavailable_phone)))
    }

    @Test
    fun `S022 RF-18 la pagina Correcciones con el virtual dice que no admite correcciones`() {
        showPage(ReceiverPage.CORRECTIONS, snapshot(PositionSource.VIRTUAL, virtualScenario))

        compose.onNodeWithTag(CORRECTIONS_STATUS_TAG).assert(hasText(text(R.string.ntrip_unavailable_virtual)))
    }

    @Test
    fun `S022 RF-7 una ubicacion simulada por otra app se avisa en la cabecera`() {
        show(snapshot(PositionSource.PHONE_MOCK))

        compose.onNodeWithTag(MOCK_WARNING_TAG).assertIsDisplayed()
        compose.onNodeWithText("Android indica que otra aplicación simula esta posición", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S022 RF-7 con la ubicacion real del movil no hay aviso de simulada`() {
        show(snapshot(PositionSource.PHONE))

        compose.onNodeWithTag(MOCK_WARNING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S022 RF-7 el indicador dice Movil simulado con una ubicacion simulada`() {
        val state = toStatusIndicatorState(snapshot(PositionSource.PHONE_MOCK))
        state.source shouldBe StatusSource.PHONE
        state.mock shouldBe true
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { StatusIndicator(state, {}) } }

        compose.onNodeWithTag(STATUS_INDICATOR_PHONE_TAG, useUnmergedTree = true).assert(hasText("Móvil simulado"))
    }

    @Test
    fun `S022 RF-7 el indicador con el movil real dice solo Movil`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                StatusIndicator(StatusIndicatorState(source = StatusSource.PHONE, solution = SolutionStatus.SINGLE), {})
            }
        }

        compose.onNodeWithTag(STATUS_INDICATOR_PHONE_TAG, useUnmergedTree = true).assert(hasText("Móvil"))
        toStatusIndicatorState(snapshot(PositionSource.PHONE)).mock shouldBe false
    }

    private val noSatellites = GgaFix("101530.00", 40.4, -3.7, SolutionStatus.SINGLE, 0, null, 650.0, 0.0, null, "")

    @Test
    fun `S022 RF-6 con el movil el menu no ensena los nombres internos del equipo`() {
        show(snapshot(PositionSource.PHONE))

        compose.onNodeWithTag(MENU_GEAR_TAG).performScrollTo().assert(hasText(text(R.string.source_phone_no_gear)))
    }

    @Test
    fun `S022 RF-6 la pagina Antena y jalon con el movil explica que no hay jalon ni antena`() {
        showPage(ReceiverPage.GEAR, snapshot(PositionSource.PHONE))

        compose.onNodeWithTag(GEAR_UNAVAILABLE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S022 RF-5 con el movil sin numero de satelites no se dice 0 sat y con un receptor si`() {
        val phone = snapshot(PositionSource.PHONE).copy(gga = noSatellites)
        toStatusIndicatorState(phone).satellites shouldBe null
        toStatusIndicatorState(snapshot(PositionSource.PHONE).copy(gga = fix)).satellites shouldBe 9
        toStatusIndicatorState(snapshot(PositionSource.RECEIVER).copy(gga = noSatellites)).satellites shouldBe 0

        show(phone)
        compose.onNodeWithTag(MENU_STATUS_TAG).performScrollTo().assert(hasText(text(R.string.status_bar_satellites, 0)).not())
    }

    @Test
    fun `S022 RF-9 el movil pierde la inclinacion y el movil y el virtual pierden el NTRIP`() {
        for (source in PositionSource.entries) {
            val phone = source == PositionSource.PHONE || source == PositionSource.PHONE_MOCK
            source.isPhone() shouldBe phone
            source.supportsTilt() shouldBe !phone
            (source.ntripMotive() != null) shouldBe (phone || source == PositionSource.VIRTUAL)
        }
    }
}
