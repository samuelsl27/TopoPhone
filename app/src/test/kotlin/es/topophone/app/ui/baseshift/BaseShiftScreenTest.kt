package es.topophone.app.ui.baseshift

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.MeasureProgress
import es.topophone.app.ui.options.ProjectOption
import es.topophone.app.ui.options.chooseOption
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.BaseShift
import es.topophone.core.model.point.BaseShiftClass
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla del desplazamiento de base: punto conocido, medida, resultado, confirmaciones y lista (S020/RF-1 a RF-6, RF-12; RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class BaseShiftScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val candidates = listOf(
        KnownCandidate("p1", "V-1", 440_290.0, 4_474_257.0, 650.0),
        KnownCandidate("p2", "V-2", 440_300.0, 4_474_260.0, null),
    )

    private val setup = BaseShiftUiState(
        loaded = true,
        projectName = "Camino de la Dehesa",
        candidates = candidates,
        knownValid = true,
    )

    private val vector = BaseShiftVector(-2.147_2, -1.965_4, 0.516_3)

    private val resultUi = BaseShiftResultUi(vector, -2.0854, 1.7159, 0.5, horizontalOnly = false, BaseShiftClass.OK)

    private fun show(state: BaseShiftUiState, actions: BaseShiftActions = BaseShiftActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { BaseShiftScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    private fun shift(id: String, name: String, active: Boolean) = BaseShift(
        id = id,
        projectId = "p",
        createdAtMs = 1,
        fromReplay = false,
        knownName = name,
        knownPointId = null,
        knownEasting = 1.0,
        knownNorthing = 2.0,
        knownHeightM = null,
        measured = GeodeticPosition(40.0, -3.0, 700.0),
        epochsUsed = 5,
        vector = vector,
        horizontalOnly = true,
        correctionsDatum = CorrectionsDatum.ETRS89,
        active = active,
        base = null,
    )

    @Test
    fun `S020 RF-1 la pantalla ofrece los puntos del proyecto para elegir el conocido`() {
        var chosen: String? = null
        show(setup, BaseShiftActions(onSelectPoint = { chosen = it }))

        compose.onNodeWithText("Camino de la Dehesa").assertIsDisplayed()
        compose.onNodeWithText("V-1").assertIsDisplayed()
        compose.onNodeWithTag(baseShiftCandidateTag("p2")).performClick()

        chosen shouldBe "p2"
    }

    @Test
    fun `S020 RF-1 tambien se pueden escribir las coordenadas del punto conocido`() {
        var source: KnownSource? = null
        val typed = mutableListOf<String>()
        show(
            setup.copy(source = KnownSource.TYPED),
            BaseShiftActions(onSource = { source = it }, onEasting = { typed += "E$it" }, onNorthing = { typed += "N$it" }, onHeight = {
                typed +=
                    "H$it"
            }),
        )

        compose.onNodeWithTag(BASE_SHIFT_EASTING_TAG).performTextInput("440290")
        compose.onNodeWithTag(BASE_SHIFT_NORTHING_TAG).performTextInput("4474257")
        compose.onNodeWithTag(BASE_SHIFT_HEIGHT_TAG).performTextInput("650")
        compose.onNodeWithTag(BASE_SHIFT_SOURCE_POINT_TAG).performClick()

        typed.filter { it.length > 1 } shouldBe listOf("E440290", "N4474257", "H650")
        source shouldBe KnownSource.POINT
    }

    @Test
    fun `S020 RF-1 con un SC geografico las coordenadas se piden en grados`() {
        show(setup.copy(source = KnownSource.TYPED, geographic = true))

        compose.onNodeWithText("Latitud (°)").assertIsDisplayed()
        compose.onNodeWithText("Longitud (°)").assertIsDisplayed()
    }

    @Test
    fun `S020 RF-2 Medir se activa solo sin bloqueo y dice por que no`() {
        var measured = 0
        show(setup.copy(blocker = BaseShiftBlocker.NO_POSITION), BaseShiftActions(onMeasure = { measured++ }))

        compose.onNodeWithText("Sin posición del receptor.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(BASE_SHIFT_MEASURE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S020 RF-2 sin bloqueo Medir empieza la medida`() {
        var measured = 0
        show(setup, BaseShiftActions(onMeasure = { measured++ }))

        compose.onNodeWithTag(BASE_SHIFT_MEASURE_TAG).performScrollTo().assertIsEnabled().performClick()

        measured shouldBe 1
    }

    @Test
    fun `S020 RF-2 mientras mide se ve el progreso y se puede cancelar`() {
        var cancelled = 0
        show(
            setup.copy(step = BaseShiftStep.MEASURING, progress = MeasureProgress(12.0, 30.0, 12)),
            BaseShiftActions(onCancel = { cancelled++ }),
        )

        compose.onNodeWithText("Midiendo: 12 de 30 s · 12 épocas").assertIsDisplayed()
        compose.onNodeWithTag(BASE_SHIFT_CANCEL_TAG).performClick()

        cancelled shouldBe 1
    }

    @Test
    fun `S020 RF-4 el resultado enseña dX, dY, dZ, Este, Norte, altura y modulo`() {
        show(setup.copy(step = BaseShiftStep.RESULT, result = resultUi))

        compose.onNodeWithText("ΔX -2,1472 · ΔY -1,9654 · ΔZ 0,5163 m").assertIsDisplayed()
        compose.onNodeWithText("ΔEste -2,0854 · ΔNorte 1,7159 · ΔAltura 0,5000 m").assertIsDisplayed()
        compose.onNodeWithText("Módulo 2,9", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S020 RF-4 Activar pide la confirmacion del usuario`() {
        var activated = 0
        show(setup.copy(step = BaseShiftStep.RESULT, result = resultUi), BaseShiftActions(onActivate = { activated++ }))

        compose.onNodeWithTag(BASE_SHIFT_ACTIVATE_TAG).assertIsEnabled().performClick()

        activated shouldBe 1
    }

    @Test
    fun `S020 RF-5 con mas de 10 m avisa en texto y exige una confirmacion aparte`() {
        var confirmed = 0
        val far = resultUi.copy(classification = BaseShiftClass.NEEDS_CONFIRMATION)
        show(setup.copy(step = BaseShiftStep.RESULT, result = far), BaseShiftActions(onConfirmFar = { confirmed++ }))

        compose.onNodeWithTag(BASE_SHIFT_FAR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(BASE_SHIFT_ACTIVATE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(BASE_SHIFT_CONFIRM_FAR_TAG).performClick()

        confirmed shouldBe 1
    }

    @Test
    fun `S020 RF-5 tras confirmar se puede activar`() {
        val far = resultUi.copy(classification = BaseShiftClass.NEEDS_CONFIRMATION)
        show(setup.copy(step = BaseShiftStep.RESULT, result = far, farConfirmed = true))

        compose.onNodeWithText("Desplazamiento confirmado.").assertIsDisplayed()
        compose.onNodeWithTag(BASE_SHIFT_ACTIVATE_TAG).assertIsEnabled()
    }

    @Test
    fun `S020 RF-5 con mas de 1000 m dice el motivo y no ofrece activar`() {
        val rejected = resultUi.copy(classification = BaseShiftClass.REJECTED)
        show(setup.copy(step = BaseShiftStep.RESULT, result = rejected, error = BaseShiftError.TOO_FAR))

        compose.onNodeWithText("El desplazamiento supera 1.000 m: no se puede activar.").assertIsDisplayed()
        compose.onAllNodesWithTagCount(BASE_SHIFT_ACTIVATE_TAG) shouldBe 0
    }

    @Test
    fun `S020 RF-6 sin altura ofrece aplicar solo el horizontal`() {
        var accepted = 0
        show(setup.copy(step = BaseShiftStep.CHOOSE_HORIZONTAL), BaseShiftActions(onAcceptHorizontal = { accepted++ }))

        compose.onNodeWithText("El punto conocido no tiene altura", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(BASE_SHIFT_ACCEPT_HORIZONTAL_TAG).performClick()

        accepted shouldBe 1
    }

    @Test
    fun `S020 RF-6 un resultado horizontal dice que no corrige la altura`() {
        show(setup.copy(step = BaseShiftStep.RESULT, result = resultUi.copy(horizontalOnly = true)))

        compose.onNodeWithTag(BASE_SHIFT_NO_HEIGHT_NOTE_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `S020 RF-12 la lista marca el activo y ofrece desactivar, reactivar y borrar`() {
        var deactivated = 0
        var reactivated: String? = null
        var deleted: String? = null
        show(
            setup.copy(shifts = listOf(shift("a", "V-A", active = true), shift("b", "V-B", active = false))),
            BaseShiftActions(onDeactivate = { deactivated++ }, onReactivate = { reactivated = it }, onDelete = { deleted = it }),
        )

        compose.onNodeWithText("Activo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(baseShiftDeactivateTag("a")).performScrollTo().performClick()
        compose.onNodeWithTag(baseShiftReactivateTag("b")).performScrollTo().performClick()
        compose.onNodeWithTag(baseShiftDeleteTag("b")).performScrollTo().performClick()

        deactivated shouldBe 1
        reactivated shouldBe "b"
        deleted shouldBe "b"
    }

    @Test
    fun `S020 RF-12 sin desplazamientos lo dice`() {
        show(setup)

        compose.onNodeWithText("Este proyecto no tiene desplazamientos.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S020 RNF-T8 los botones miden al menos 56 dp`() {
        show(setup.copy(shifts = listOf(shift("a", "V-A", active = true))))

        compose.onNodeWithTag(BASE_SHIFT_MEASURE_TAG).performScrollTo().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(baseShiftCandidateTag("p1")).performScrollTo().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(baseShiftDeactivateTag("a")).performScrollTo().assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S020 RF-1 el menu de la lista de objetos ofrece Base shift`() {
        var opened = 0
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true),
                    ObjectListActions(onBaseShift = { opened++ }),
                    onBack = {},
                )
            }
        }

        compose.chooseOption(ProjectOption.BASE_SHIFT)

        opened shouldBe 1
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
