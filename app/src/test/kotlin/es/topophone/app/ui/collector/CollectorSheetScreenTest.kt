package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.field.FIELD_SHEET_HANDLE_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** El recolector como hoja de campo sobre el mapa: cuánto tapa y a dónde llevan sus acciones (S060/RF-1, RF-2, RF-3, RF-6, RF-16). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-rES-w360dp-h640dp")
class CollectorSheetScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = CollectorActions(
        onOpenReceiver = { calls += "receiver" },
        onOpenProjectCrs = { calls += "crs" },
        onOpenGear = { calls += "gear" },
        onOpenLocalization = { calls += "localization" },
    )

    private val ready = CollectorUiState(
        loaded = true,
        name = "P1",
        poleHeightText = "1,800",
        tiltMode = TiltMode.VERTICAL,
        live = LiveQuality(SolutionStatus.FIX, 0.012, 0.02),
    )

    /** El caso más cargado: motivo de bloqueo, aviso, control de precisión y localización a la vez. */
    private val worst = ready.copy(
        blocker = MeasureBlocker.NO_GEAR,
        savedName = "P0",
        settings = CollectorSettings(
            precision = PrecisionControl(admitted = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT))),
        ),
        localized = true,
        baseShiftName = "Vértice 4",
    )

    /** Midiendo con aviso, control de precisión y localización a la vez. */
    private val worstMeasuring = worst.copy(
        blocker = null,
        measuring = true,
        settings = worst.settings.copy(mode = es.topophone.core.model.point.MeasurementMode.AVERAGED),
        progress = MeasureProgress(elapsedS = 3.0, requiredS = 5.0, epochsUsed = 3, epochsDiscarded = 1),
        solutionLost = true,
    )

    private fun show(state: CollectorUiState, onClose: () -> Unit = { calls += "close" }) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    CollectorSheet(state, LinearUnit.METRE, actions, onClose, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    private fun sheetFraction(): Float {
        val sheet = compose.onNodeWithTag(COLLECTOR_PANEL_TAG).getUnclippedBoundsInRoot().height.value
        val screen = compose.onRoot().getUnclippedBoundsInRoot().height.value
        return sheet / screen
    }

    @Test
    fun `S060 RF-3 el recolector abre plegado y sin la parte expandida`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(COLLECTOR_NAME_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-2 a 360 por 640 dp el caso normal tapa menos del 45 por ciento`() {
        show(ready)

        sheetFraction() shouldBeLessThanOrEqualTo 0.45f
    }

    @Test
    fun `S060 RF-2 a 360 por 640 dp el caso mas cargado deja libre al menos el 55 por ciento`() {
        show(worst)

        sheetFraction() shouldBeLessThanOrEqualTo 0.45f
    }

    @Test
    fun `S060 RF-2 a 360 por 640 dp midiendo con aviso deja libre al menos el 55 por ciento`() {
        show(worstMeasuring)

        sheetFraction() shouldBeLessThanOrEqualTo 0.45f
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S060 RF-2 a 411 por 891 dp midiendo con aviso deja libre al menos el 60 por ciento`() {
        show(worstMeasuring)

        sheetFraction() shouldBeLessThanOrEqualTo 0.40f
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h891dp")
    fun `S060 RF-2 a 411 por 891 dp el caso mas cargado deja libre al menos el 60 por ciento`() {
        show(worst)

        sheetFraction() shouldBeLessThanOrEqualTo 0.40f
    }

    @Test
    fun `S060 RF-4 tocar el asa expande y deja ver el nombre del punto`() {
        show(ready)

        compose.onNodeWithTag(FIELD_SHEET_HANDLE_TAG).performClick()

        compose.onNodeWithTag(COLLECTOR_NAME_TAG).assertIsDisplayed()
    }

    @Test
    fun `S060 RF-6 Cerrar cierra el recolector`() {
        show(ready)

        compose.onNodeWithTag(COLLECTOR_CLOSE_TAG).performClick()

        calls shouldBe listOf("close")
    }

    @Test
    fun `S060 RF-16 la accion del motivo llega a la ruta que lo resuelve`() {
        show(ready.copy(blocker = MeasureBlocker.NO_POSITION, live = null))
        compose.onNodeWithTag(COLLECTOR_REASON_ACTION_TAG).performClick()

        calls shouldBe listOf("receiver")
    }

    @Test
    fun `S060 RF-14 la pastilla de la localizacion lleva a la pantalla de localizacion`() {
        show(ready.copy(localized = true))
        compose.onNodeWithTag(infoPillTag(InfoPillKind.LOCALIZATION)).performClick()

        calls shouldBe listOf("localization")
    }
}
