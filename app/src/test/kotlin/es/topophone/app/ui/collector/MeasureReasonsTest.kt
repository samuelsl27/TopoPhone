package es.topophone.app.ui.collector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El motivo por el que «Medir» no se puede pulsar y su acción con un toque (S060/RF-16, RF-17, RF-18, RF-19). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-w411dp-h640dp")
class MeasureReasonsTest {

    @get:Rule
    val compose = createComposeRule()

    private val done = mutableListOf<ReasonAction>()

    @Test
    fun `S060 RF-19 sin bloqueo no sale ningun motivo`() {
        MeasureReasons.of(null) shouldBe null
    }

    @Test
    fun `S060 RF-17 cada motivo de S010 S016 S017 S024 y S046 lleva su accion`() {
        val expected = mapOf(
            MeasureBlocker.NO_POSITION to ReasonAction.OPEN_RECEIVER, // sin receptor o sin posición (S010/RF-12)
            MeasureBlocker.OUTSIDE_CRS_AREA to ReasonAction.OPEN_PROJECT_CRS, // fuera del ámbito del SC (S010/RF-12)
            MeasureBlocker.TILT_MODE_MISSING to ReasonAction.OPEN_TILT, // modo de inclinación sin elegir (S016/RF-2)
            MeasureBlocker.TILT_NOT_READY to ReasonAction.OPEN_TILT, // inclinación no lista (S016/RF-18)
            MeasureBlocker.INVALID_POLE to ReasonAction.EDIT_POLE, // jalón no válido (S046/RF-28, S010/RF-5)
            MeasureBlocker.NO_GEAR to ReasonAction.OPEN_GEAR, // equipo no válido (S046/RF-28)
            MeasureBlocker.ATTRIBUTES to ReasonAction.EXPAND, // atributos obligatorios (S024/RF-9)
            MeasureBlocker.INVALID_NAME to ReasonAction.EXPAND, // nombre (S010/RF-3)
            MeasureBlocker.NAME_TAKEN to ReasonAction.EXPAND,
            MeasureBlocker.INVALID_SECONDS to ReasonAction.EDIT_TIME, // tiempo (S017)
            MeasureBlocker.AUTO_INTERVAL_TOO_SHORT to ReasonAction.EDIT_TIME, // S017/RF-18
            MeasureBlocker.INVALID_AUTO_INTERVAL to ReasonAction.EDIT_TIME,
            MeasureBlocker.INVALID_AUTO_DISTANCE to ReasonAction.EDIT_TIME,
        )

        MeasureBlocker.entries.forEach { blocker ->
            MeasureReasons.of(blocker)?.action shouldBe expected.getValue(blocker)
        }
    }

    @Test
    fun `S060 RF-16 cada motivo tiene un texto propio y el de su accion`() {
        val texts = MeasureBlocker.entries.map { MeasureReasons.of(it)!!.textRes }
        texts.distinct().size shouldBe texts.size
    }

    private fun show(blocker: MeasureBlocker?) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { MeasureReasonLine(MeasureReasons.of(blocker)) { done += it } }
        }
    }

    @Test
    fun `S060 RF-16 el motivo se ve en una linea con su accion y al tocarla la lanza`() {
        show(MeasureBlocker.NO_POSITION)

        compose.onNodeWithTag(COLLECTOR_BLOCKER_TAG).assertIsDisplayed().assertTextContains("Sin posición", substring = true)
        compose.onNodeWithTag(COLLECTOR_REASON_ACTION_TAG).assertTextContains("Ver receptores").performClick()
        done shouldBe listOf(ReasonAction.OPEN_RECEIVER)
    }

    @Test
    fun `S060 RF-19 sin bloqueo no se dibuja ni el texto ni la accion`() {
        show(null)

        compose.onAllNodesWithTag(COLLECTOR_BLOCKER_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onAllNodesWithTag(COLLECTOR_REASON_ACTION_TAG).fetchSemanticsNodes().size shouldBe 0
    }
}
