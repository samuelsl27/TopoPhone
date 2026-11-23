package es.topophone.app.ui.collector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.field.FIELD_ENTRY_CONFIRM_TAG
import es.topophone.app.ui.field.FIELD_ENTRY_MINUTES_TAG
import es.topophone.app.ui.field.FIELD_ENTRY_SECONDS_TAG
import es.topophone.app.ui.field.FIELD_ENTRY_TAG
import es.topophone.app.ui.field.shortListOptionTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El recolector plegado: línea de estado, pastillas, motivo y «Medir» (S060/RF-8, RF-9, RF-11, RF-13, RF-16). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorFoldedTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = CollectorActions(
        onPoleHeight = { calls += "pole:$it" },
        onSeconds = { calls += "seconds:$it" },
        onMode = { calls += "mode:${it.name}" },
        onMeasure = { calls += "measure" },
        onOpenTilt = { calls += "tilt" },
        onOpenReceiver = { calls += "receiver" },
    )

    private val ready = CollectorUiState(
        loaded = true,
        name = "P1",
        poleHeightText = "1,800",
        tiltMode = TiltMode.VERTICAL,
        live = LiveQuality(SolutionStatus.FIX, 0.012, 0.02),
    )

    private fun show(state: CollectorUiState, onExpand: () -> Unit = { calls += "expand" }) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CollectorFolded(state, LinearUnit.METRE, actions, onExpand = onExpand) }
        }
    }

    @Test
    fun `S060 RF-8 y RF-9 se ven la linea de estado las cuatro pastillas y Medir con el nombre`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_LIVE_TAG).assertIsDisplayed()
        listOf(COLLECTOR_POLE_PILL_TAG, COLLECTOR_TIME_PILL_TAG, COLLECTOR_MODE_PILL_TAG, COLLECTOR_TILT_PILL_TAG)
            .forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithTag(COLLECTOR_POLE_PILL_TAG).assertTextContains("1,800 m", substring = true)
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertTextContains("P1", substring = true).assertIsEnabled()
    }

    @Test
    fun `S060 RF-11 cambiar el jalon desde la pastilla llega a la accion de siempre`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_POLE_PILL_TAG).performClick()
        compose.onNodeWithTag(FIELD_ENTRY_TAG).performTextClearance()
        compose.onNodeWithTag(FIELD_ENTRY_TAG).performTextInput("2,000")
        compose.onNodeWithTag(FIELD_ENTRY_CONFIRM_TAG).performClick()

        calls shouldBe listOf("pole:2,000")
    }

    @Test
    fun `S060 RF-10 en instantaneo la pastilla Tiempo dice Instantaneo y no tiene selector`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_TIME_PILL_TAG).assertTextContains("Instantáneo", substring = true).assertIsNotEnabled()
    }

    @Test
    fun `S060 RF-11 cambiar el tiempo de promediado escribe minutos y segundos y llega a la accion de siempre`() {
        show(ready.copy(settings = CollectorSettings(mode = MeasurementMode.AVERAGED), averagingSecondsText = "0:05"))

        compose.onNodeWithTag(COLLECTOR_TIME_PILL_TAG).assertTextContains("0:05", substring = true).performClick()
        compose.onNodeWithTag(FIELD_ENTRY_MINUTES_TAG).performTextClearance()
        compose.onNodeWithTag(FIELD_ENTRY_MINUTES_TAG).performTextInput("1")
        compose.onNodeWithTag(FIELD_ENTRY_SECONDS_TAG).performTextClearance()
        compose.onNodeWithTag(FIELD_ENTRY_SECONDS_TAG).performTextInput("30")
        compose.onNodeWithTag(FIELD_ENTRY_CONFIRM_TAG).performClick()

        calls shouldBe listOf("seconds:1:30")
    }

    @Test
    fun `S060 RF-13 la pastilla Modo abre una lista con la vigente marcada y elegir otra la aplica`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_MODE_PILL_TAG).performClick()
        compose.onNodeWithTag(shortListOptionTag(CollectionMode.INSTANT)).assertIsDisplayed()
        compose.onNodeWithTag(shortListOptionTag(CollectionMode.AVERAGED)).performClick()

        calls shouldBe listOf("mode:AVERAGED")
    }

    @Test
    fun `S060 RF-13 la pastilla Inclinacion abre la pantalla de inclinacion del receptor`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_TILT_PILL_TAG).performClick()

        calls shouldBe listOf("tilt")
    }

    @Test
    fun `S060 RF-16 sin posicion Medir esta bloqueado y el motivo dice por que y lleva a los receptores`() {
        show(ready.copy(blocker = MeasureBlocker.NO_POSITION, live = null))

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COLLECTOR_BLOCKER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_REASON_ACTION_TAG).performClick()

        calls shouldBe listOf("receiver")
    }

    @Test
    fun `S060 RF-19 con los ajustes por defecto no hay pastillas informativas ni motivo`() {
        show(ready)

        InfoPillKind.entries.forEach { compose.onAllNodesWithTag(infoPillTag(it)).fetchSemanticsNodes().size shouldBe 0 }
        compose.onAllNodesWithTag(COLLECTOR_BLOCKER_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-14 la pastilla del control de precision lleva a la hoja expandida donde se cambia`() {
        val precision = PrecisionControl(admitted = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT)))
        show(ready.copy(settings = CollectorSettings(precision = precision)))

        compose.onNodeWithTag(infoPillTag(InfoPillKind.PRECISION)).performClick()

        calls shouldBe listOf("expand")
    }

    @Test
    fun `S060 RF-8 pulsar Medir llama a la accion de siempre`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).performClick()

        calls shouldBe listOf("measure")
    }
}
