package es.topophone.app.ui.collector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Medición en curso en la hoja plegada: pastillas desactivadas, progreso, Terminar y Cancelar y un solo aviso (S060/RF-15, RF-22). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorMeasuringSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = CollectorActions(onFinish = { calls += "finish" }, onCancel = { calls += "cancel" })

    private val ready = CollectorUiState(
        loaded = true,
        name = "P1",
        poleHeightText = "1,800",
        tiltMode = TiltMode.VERTICAL,
        live = LiveQuality(SolutionStatus.FIX, 0.012, 0.02),
    )
    private val averaging = ready.copy(
        settings = CollectorSettings(mode = MeasurementMode.AVERAGED),
        measuring = true,
        progress = MeasureProgress(elapsedS = 3.0, requiredS = 5.0, epochsUsed = 3, epochsDiscarded = 1),
    )

    private val noticeTags = listOf(
        COLLECTOR_SOLUTION_LOST_TAG,
        COLLECTOR_TILT_LOST_TAG,
        COLLECTOR_PAUSED_TAG,
        COLLECTOR_NO_EPOCHS_TAG,
        COLLECTOR_RESTARTED_TAG,
        COLLECTOR_ERROR_TAG,
        COLLECTOR_SAVED_TAG,
    )

    private fun show(state: CollectorUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CollectorFolded(state, LinearUnit.METRE, actions, onExpand = {}) }
        }
    }

    private fun noticesShown() = noticeTags.count { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `S060 RF-15 midiendo las cuatro pastillas estan desactivadas y se ve el progreso`() {
        show(averaging)

        listOf(COLLECTOR_POLE_PILL_TAG, COLLECTOR_TIME_PILL_TAG, COLLECTOR_MODE_PILL_TAG, COLLECTOR_TILT_PILL_TAG)
            .forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
        compose.onNodeWithTag(COLLECTOR_PROGRESS_TAG).assertIsDisplayed().assertTextContains("0:03", substring = true)
        compose.onAllNodesWithTag(COLLECTOR_MEASURE_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-15 en promediado salen Terminar y Cancelar y llaman a sus acciones`() {
        show(averaging)

        compose.onNodeWithTag(COLLECTOR_FINISH_TAG).performClick()
        compose.onNodeWithTag(COLLECTOR_CANCEL_TAG).performClick()

        calls shouldBe listOf("finish", "cancel")
    }

    @Test
    fun `S060 RF-15 en instantaneo solo sale Cancelar`() {
        show(ready.copy(measuring = true))

        compose.onAllNodesWithTag(COLLECTOR_FINISH_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onNodeWithTag(COLLECTOR_CANCEL_TAG).assertIsDisplayed()
    }

    // ---- Un solo aviso a la vez (RF-22) ----

    @Test
    fun `S060 RF-22 el aviso de mayor prioridad gana y los demas no se apilan`() {
        val many = averaging.copy(
            solutionLost = true,
            tiltLost = es.topophone.core.survey.TiltGuide.entries.first(),
            restarted = true,
            noEpochsYet = true,
            paused = PauseState.PAUSED,
        )

        CollectorNotices.current(many) shouldBe CollectorNotice.SolutionLost
        CollectorNotices.current(many.copy(solutionLost = false))?.let { it is CollectorNotice.TiltLost } shouldBe true
        CollectorNotices.current(many.copy(solutionLost = false, tiltLost = null)) shouldBe CollectorNotice.Paused
        CollectorNotices.current(many.copy(solutionLost = false, tiltLost = null, paused = null)) shouldBe CollectorNotice.NoEpochs
        CollectorNotices.current(many.copy(solutionLost = false, tiltLost = null, paused = null, noEpochsYet = false)) shouldBe
            CollectorNotice.Restarted(null)
    }

    @Test
    fun `S060 RF-22 el error de una medida cancelada manda sobre todo y el punto guardado es lo ultimo`() {
        CollectorNotices.current(ready.copy(error = CollectorError.NO_DATA, savedName = "P0")) shouldBe
            CollectorNotice.Error(CollectorError.NO_DATA)
        CollectorNotices.current(ready.copy(savedName = "P0")) shouldBe CollectorNotice.Saved("P0")
        CollectorNotices.current(ready) shouldBe null
    }

    @Test
    fun `S060 RF-22 los avisos de la medida solo cuentan mientras se mide`() {
        CollectorNotices.current(ready.copy(solutionLost = true, paused = PauseState.PAUSED, restarted = true)) shouldBe null
    }

    @Test
    fun `S060 RF-22 con varios avisos a la vez solo se dibuja una linea de aviso`() {
        show(averaging.copy(solutionLost = true, restarted = true, noEpochsYet = true, paused = PauseState.PAUSED))

        noticesShown() shouldBe 1
        compose.onNodeWithTag(COLLECTOR_SOLUTION_LOST_TAG).assertIsDisplayed()
    }

    @Test
    fun `S060 RF-22 el aviso se ve con su texto actual`() {
        show(averaging.copy(paused = PauseState.PAUSED))

        compose.onNodeWithTag(COLLECTOR_PAUSED_TAG).assertTextContains("pausa", substring = true)
    }
}
