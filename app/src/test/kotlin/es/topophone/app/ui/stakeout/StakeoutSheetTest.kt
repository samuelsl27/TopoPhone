package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECTOR_BLOCKER_TAG
import es.topophone.app.ui.collector.COLLECTOR_CANCEL_TAG
import es.topophone.app.ui.collector.COLLECTOR_MEASURE_TAG
import es.topophone.app.ui.collector.COLLECTOR_REASON_ACTION_TAG
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.LiveQuality
import es.topophone.app.ui.collector.MeasureBlocker
import es.topophone.app.ui.field.FIELD_ENTRY_CONFIRM_TAG
import es.topophone.app.ui.field.FIELD_ENTRY_TAG
import es.topophone.app.ui.field.shortListOptionTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La hoja común del replanteo: cabecera, placa, pastillas, Anterior y Siguiente y Medir (S060/RF-23, RF-27, RF-30). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class StakeoutSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = CollectorActions(
        onMeasure = { calls += "measure" },
        onCancel = { calls += "cancel" },
        onOpenReceiver = { calls += "receiver" },
    )

    private val ready = CollectorUiState(
        loaded = true,
        name = "P1",
        live = LiveQuality(SolutionStatus.FIX, 0.012, 0.02),
    )

    private fun show(
        collector: CollectorUiState? = ready,
        hasPrevious: Boolean = true,
        hasNext: Boolean = true,
        withElevation: Boolean = true,
        settings: StakeoutSettings = StakeoutSettings(),
    ) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                StakeoutSheet(
                    title = "Replantear P3",
                    onClose = { calls += "close" },
                    live = collector?.live,
                    collector = collector,
                    collectorActions = actions,
                    navigation = StakeNavigation(hasPrevious, hasNext, { calls += "previous" }, { calls += "next" }),
                    plate = {},
                    pills = {
                        StakeoutPills(
                            unit = LinearUnit.METRE,
                            settings = settings,
                            enabled = collector?.measuring != true,
                            showToleranceV = withElevation,
                            elevationText = if (withElevation) "102,680 m" else null,
                            onTolerance = {
                                calls += "tol:$it"
                                true
                            },
                            onToleranceV = {
                                calls += "tolv:$it"
                                true
                            },
                            onMode = { calls += "mode:${it.name}" },
                            onElevation = { calls += "elevation" },
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `S060 RF-23 la hoja del replanteo lleva titulo estado pastillas y Medir`() {
        show()

        compose.onNodeWithTag(STAKEOUT_TITLE_TAG).assertTextContains("Replantear P3")
        compose.onNodeWithTag(STAKEOUT_PILL_TOLERANCE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_PILL_TOLERANCE_V_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_PILL_ELEVATION_TAG).assertIsDisplayed().assertTextContains("102,680", substring = true)
        compose.onNodeWithTag(STAKEOUT_PILL_MODE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S060 RF-27 sin cota de diseno no sale Tolerancia V`() {
        show(withElevation = false)

        compose.onAllNodesWithTag(STAKEOUT_PILL_TOLERANCE_V_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onNodeWithTag(STAKEOUT_PILL_TOLERANCE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S060 RF-27 Tolerancia H abre un teclado numerico y aplica el valor con la accion de siempre`() {
        show()

        compose.onNodeWithTag(STAKEOUT_PILL_TOLERANCE_TAG).assertTextContains("0,025 m", substring = true).performClick()
        compose.onNodeWithTag(FIELD_ENTRY_TAG).performTextReplacement("0,05")
        compose.onNodeWithTag(FIELD_ENTRY_CONFIRM_TAG).performClick()

        calls shouldBe listOf("tol:0,05")
    }

    @Test
    fun `S060 RF-27 una tolerancia fuera de rango dice el rango y no se aplica`() {
        show()

        compose.onNodeWithTag(STAKEOUT_PILL_TOLERANCE_TAG).performClick()
        compose.onNodeWithTag(FIELD_ENTRY_TAG).performTextReplacement("5")

        compose.onNodeWithTag(FIELD_ENTRY_CONFIRM_TAG).assertIsNotEnabled()
        calls shouldBe emptyList()
    }

    @Test
    fun `S060 RF-27 Tolerancia V abre su teclado y llega a su accion`() {
        show()

        compose.onNodeWithTag(STAKEOUT_PILL_TOLERANCE_V_TAG).performClick()
        compose.onNodeWithTag(FIELD_ENTRY_TAG).performTextReplacement("0,04")
        compose.onNodeWithTag(FIELD_ENTRY_CONFIRM_TAG).performClick()

        calls shouldBe listOf("tolv:0,04")
    }

    @Test
    fun `S060 RF-27 la pastilla Cota abre el dialogo actual de la cota de diseno`() {
        show()

        compose.onNodeWithTag(STAKEOUT_PILL_ELEVATION_TAG).performClick()

        calls shouldBe listOf("elevation")
    }

    @Test
    fun `S060 RF-27 la pastilla Modo ofrece por direccion y por distancia y marca el vigente`() {
        show(settings = StakeoutSettings(mode = StakeMode.BY_DIRECTION))

        compose.onNodeWithTag(STAKEOUT_PILL_MODE_TAG).performClick()
        compose.onNodeWithTag(shortListOptionTag(StakeMode.BY_DIRECTION)).assertIsDisplayed()
        compose.onNodeWithTag(shortListOptionTag(StakeMode.BY_DISTANCE)).performClick()

        calls shouldBe listOf("mode:BY_DISTANCE")
    }

    @Test
    fun `S060 RF-30 Anterior y Siguiente miden al menos 56 dp y llaman a su navegacion`() {
        show()

        compose.onNodeWithTag(STAKEOUT_PREVIOUS_TAG).assertHeightIsAtLeast(56.dp).assertIsEnabled().performClick()
        compose.onNodeWithTag(STAKEOUT_NEXT_TAG).assertHeightIsAtLeast(56.dp).assertIsEnabled().performClick()

        calls shouldBe listOf("previous", "next")
    }

    @Test
    fun `S060 RF-30 sin anterior o sin siguiente su boton esta desactivado`() {
        show(hasPrevious = false, hasNext = false)

        compose.onNodeWithTag(STAKEOUT_PREVIOUS_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(STAKEOUT_NEXT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S060 RF-15 midiendo se desactivan las pastillas y la navegacion y salen el progreso y Cancelar`() {
        show(collector = ready.copy(measuring = true))

        compose.onNodeWithTag(STAKEOUT_PILL_TOLERANCE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(STAKEOUT_PILL_MODE_TAG).assertIsNotEnabled()
        compose.onAllNodesWithTag(STAKEOUT_PREVIOUS_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onNodeWithTag(COLLECTOR_CANCEL_TAG).performClick()

        calls shouldBe listOf("cancel")
    }

    @Test
    fun `S060 RF-16 con un bloqueo Medir dice por que y su accion llega a la ruta`() {
        show(collector = ready.copy(blocker = MeasureBlocker.NO_POSITION, live = null))

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COLLECTOR_BLOCKER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_REASON_ACTION_TAG).performClick()

        calls shouldBe listOf("receiver")
    }

    @Test
    fun `S060 RF-6 Cerrar cierra el replanteo`() {
        show()

        compose.onNodeWithTag(STAKEOUT_CLOSE_TAG).performClick()

        calls shouldBe listOf("close")
    }
}
