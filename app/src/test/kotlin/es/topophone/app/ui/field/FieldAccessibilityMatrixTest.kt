package es.topophone.app.ui.field

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECTOR_CANCEL_TAG
import es.topophone.app.ui.collector.COLLECTOR_FINISH_TAG
import es.topophone.app.ui.collector.COLLECTOR_MEASURE_TAG
import es.topophone.app.ui.collector.COLLECTOR_MODE_PILL_TAG
import es.topophone.app.ui.collector.COLLECTOR_POLE_PILL_TAG
import es.topophone.app.ui.collector.COLLECTOR_REASON_ACTION_TAG
import es.topophone.app.ui.collector.COLLECTOR_TILT_PILL_TAG
import es.topophone.app.ui.collector.COLLECTOR_TIME_PILL_TAG
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorSheet
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.InfoPillKind
import es.topophone.app.ui.collector.LiveQuality
import es.topophone.app.ui.collector.MeasureBlocker
import es.topophone.app.ui.collector.MeasureProgress
import es.topophone.app.ui.collector.infoPillTag
import es.topophone.app.ui.stakeout.STAKEOUT_NEXT_TAG
import es.topophone.app.ui.stakeout.STAKEOUT_PILL_ELEVATION_TAG
import es.topophone.app.ui.stakeout.STAKEOUT_PILL_MODE_TAG
import es.topophone.app.ui.stakeout.STAKEOUT_PILL_TOLERANCE_TAG
import es.topophone.app.ui.stakeout.STAKEOUT_PILL_TOLERANCE_V_TAG
import es.topophone.app.ui.stakeout.STAKEOUT_PREVIOUS_TAG
import es.topophone.app.ui.stakeout.StakeNavigation
import es.topophone.app.ui.stakeout.StakeoutPills
import es.topophone.app.ui.stakeout.StakeoutSheet
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La matriz de tamaño y accesibilidad de recolectar y replantear: con 360 y 411 dp de ancho y la letra al 100 % y al 130 %, cada
 * pastilla, motivo y botón existe, mide 56 dp, cabe en el ancho y ningún texto se corta; y cada control que se toca tiene un nombre para
 * TalkBack (S060/RNF-T8 y los dos RNF específicos). Generada por tamaño y escenario; los gráficos nativos miden el texto de verdad.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FieldAccessibilityMatrixTest {

    @get:Rule
    val compose = createComposeRule()

    private val ready = CollectorUiState(
        loaded = true,
        name = "P1",
        poleHeightText = "1,800",
        tiltMode = TiltMode.APP,
        live = LiveQuality(SolutionStatus.FIX, 0.012, 0.02),
    )

    private val withInfo = ready.copy(
        settings = CollectorSettings(
            precision = PrecisionControl(
                admitted = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT)),
                horizontalLimitM = 0.03,
            ),
        ),
        localized = true,
        baseShiftName = "Vértice 4",
    )

    private val blocked = ready.copy(blocker = MeasureBlocker.NO_GEAR, live = null)

    private val measuring = ready.copy(
        settings = CollectorSettings(mode = MeasurementMode.AVERAGED),
        measuring = true,
        progress = MeasureProgress(elapsedS = 3.0, requiredS = 5.0, epochsUsed = 3, epochsDiscarded = 1),
    )

    private fun setContent(
        fontScale: Float,
        content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TopoPhoneTheme(EffectiveTheme.LIGHT) { Box(Modifier.fillMaxSize(), content = content) }
            }
        }
    }

    private fun collector(state: CollectorUiState, fontScale: Float) = setContent(fontScale) {
        CollectorSheet(state, LinearUnit.METRE, CollectorActions(), onClose = {}, modifier = Modifier.align(Alignment.BottomCenter))
    }

    private fun stakeout(fontScale: Float) = setContent(fontScale) {
        StakeoutSheet(
            title = "Replantear P3",
            onClose = {},
            live = ready.live,
            collector = ready,
            collectorActions = CollectorActions(),
            navigation = StakeNavigation(hasPrevious = true, hasNext = true, onPrevious = {}, onNext = {}),
            plate = {},
            pills = {
                StakeoutPills(LinearUnit.METRE, StakeoutSettings(), true, true, "102,680 m", { true }, { true }, {}, {})
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    /** Cada control existe, mide 56 dp y cabe en el ancho (S060/RNF-T8). */
    private fun assertControls(widthDp: Int, tags: List<String>) {
        tags.forEach { tag ->
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            node.assertHeightIsAtLeast(56.dp)
            val bounds = node.getUnclippedBoundsInRoot()
            (bounds.left >= 0.dp && bounds.right <= widthDp.dp) shouldBe true
        }
    }

    private fun walk(node: SemanticsNode, visit: (SemanticsNode) -> Unit) {
        visit(node)
        node.children.forEach { walk(it, visit) }
    }

    /** Ningún texto se corta (S060/RNF específicos). */
    private fun assertNoClippedText() {
        walk(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()) { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            // hasVisualOverflow no es fiable aquí: se comprueba que ninguna línea se elide y que el texto cabe en sus límites.
            results.forEach { result ->
                (0 until result.lineCount).none { result.isLineEllipsized(it) } shouldBe true
                (result.size.width <= result.layoutInput.constraints.maxWidth) shouldBe true
                (result.size.height <= result.layoutInput.constraints.maxHeight) shouldBe true
            }
        }
    }

    /** Cada control que se toca tiene un texto o una descripción para TalkBack (S060/RNF-T8). */
    private fun assertTalkBackLabels() {
        walk(compose.onRoot().fetchSemanticsNode()) { node ->
            if (node.config.getOrNull(SemanticsActions.OnClick) != null) {
                val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("")
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString("")
                (text.isNotBlank() || description.isNotBlank()) shouldBe true
            }
        }
    }

    private val collectorPills = listOf(COLLECTOR_POLE_PILL_TAG, COLLECTOR_TIME_PILL_TAG, COLLECTOR_MODE_PILL_TAG, COLLECTOR_TILT_PILL_TAG)
    private val stakeoutPills = listOf(
        STAKEOUT_PILL_TOLERANCE_TAG,
        STAKEOUT_PILL_TOLERANCE_V_TAG,
        STAKEOUT_PILL_ELEVATION_TAG,
        STAKEOUT_PILL_MODE_TAG,
    )

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S060 RNF-T8 collector con un motivo de bloqueo a 360 dp con la letra al 100 por cien`() {
        collector(blocked, 1.0f)

        assertControls(360, collectorPills + COLLECTOR_MEASURE_TAG + COLLECTOR_REASON_ACTION_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S060 RNF-T8 collector con las pastillas informativas a 360 dp con la letra al 100 por cien`() {
        collector(withInfo, 1.0f)

        assertControls(360, collectorPills + COLLECTOR_MEASURE_TAG + InfoPillKind.entries.map { infoPillTag(it) })
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S060 RNF-T8 collector midiendo a 360 dp con la letra al 100 por cien`() {
        collector(measuring, 1.0f)

        assertControls(360, collectorPills + COLLECTOR_FINISH_TAG + COLLECTOR_CANCEL_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S060 RNF-T8 replanteo a 360 dp con la letra al 100 por cien`() {
        stakeout(1.0f)

        assertControls(360, stakeoutPills + COLLECTOR_MEASURE_TAG + STAKEOUT_PREVIOUS_TAG + STAKEOUT_NEXT_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S060 RNF-T8 collector con un motivo de bloqueo a 360 dp con la letra al 130 por cien`() {
        collector(blocked, 1.3f)

        assertControls(360, collectorPills + COLLECTOR_MEASURE_TAG + COLLECTOR_REASON_ACTION_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S060 RNF-T8 collector con las pastillas informativas a 360 dp con la letra al 130 por cien`() {
        collector(withInfo, 1.3f)

        assertControls(360, collectorPills + COLLECTOR_MEASURE_TAG + InfoPillKind.entries.map { infoPillTag(it) })
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S060 RNF-T8 collector midiendo a 360 dp con la letra al 130 por cien`() {
        collector(measuring, 1.3f)

        assertControls(360, collectorPills + COLLECTOR_FINISH_TAG + COLLECTOR_CANCEL_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w360dp-h800dp")
    fun `S060 RNF-T8 replanteo a 360 dp con la letra al 130 por cien`() {
        stakeout(1.3f)

        assertControls(360, stakeoutPills + COLLECTOR_MEASURE_TAG + STAKEOUT_PREVIOUS_TAG + STAKEOUT_NEXT_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h800dp")
    fun `S060 RNF-T8 collector con un motivo de bloqueo a 411 dp con la letra al 100 por cien`() {
        collector(blocked, 1.0f)

        assertControls(411, collectorPills + COLLECTOR_MEASURE_TAG + COLLECTOR_REASON_ACTION_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h800dp")
    fun `S060 RNF-T8 collector con las pastillas informativas a 411 dp con la letra al 100 por cien`() {
        collector(withInfo, 1.0f)

        assertControls(411, collectorPills + COLLECTOR_MEASURE_TAG + InfoPillKind.entries.map { infoPillTag(it) })
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h800dp")
    fun `S060 RNF-T8 collector midiendo a 411 dp con la letra al 100 por cien`() {
        collector(measuring, 1.0f)

        assertControls(411, collectorPills + COLLECTOR_FINISH_TAG + COLLECTOR_CANCEL_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h800dp")
    fun `S060 RNF-T8 replanteo a 411 dp con la letra al 100 por cien`() {
        stakeout(1.0f)

        assertControls(411, stakeoutPills + COLLECTOR_MEASURE_TAG + STAKEOUT_PREVIOUS_TAG + STAKEOUT_NEXT_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h800dp")
    fun `S060 RNF-T8 collector con un motivo de bloqueo a 411 dp con la letra al 130 por cien`() {
        collector(blocked, 1.3f)

        assertControls(411, collectorPills + COLLECTOR_MEASURE_TAG + COLLECTOR_REASON_ACTION_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h800dp")
    fun `S060 RNF-T8 collector con las pastillas informativas a 411 dp con la letra al 130 por cien`() {
        collector(withInfo, 1.3f)

        assertControls(411, collectorPills + COLLECTOR_MEASURE_TAG + InfoPillKind.entries.map { infoPillTag(it) })
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h800dp")
    fun `S060 RNF-T8 collector midiendo a 411 dp con la letra al 130 por cien`() {
        collector(measuring, 1.3f)

        assertControls(411, collectorPills + COLLECTOR_FINISH_TAG + COLLECTOR_CANCEL_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }

    @Test
    @Config(qualifiers = "es-rES-w411dp-h800dp")
    fun `S060 RNF-T8 replanteo a 411 dp con la letra al 130 por cien`() {
        stakeout(1.3f)

        assertControls(411, stakeoutPills + COLLECTOR_MEASURE_TAG + STAKEOUT_PREVIOUS_TAG + STAKEOUT_NEXT_TAG)
        assertNoClippedText()
        assertTalkBackLabels()
    }
}
