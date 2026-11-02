package es.topophone.app.ui.alignment

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El panel «Ejes»: la lista, el activo en texto, activar, mostrar u ocultar, renombrar y borrar con confirmación (S049/RF-14, RF-15, RF-19). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class AlignmentsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun summary(id: String, name: String, visible: Boolean = true, profile: Boolean = true) = AlignmentSummary(
        id, "p", name, "ejes.xml", 1_792_346_000_000L, AlignmentSource.LANDXML, 1000.5, 380.0, visible, profile, false,
        AlignmentExtent(0.0, 1.0, 0.0, 1.0),
    )

    private val state = AlignmentsUiState(
        alignments = listOf(summary("a", "Eje A"), summary("b", "Eje B", visible = false, profile = false)),
        activeId = "a",
    )

    private fun show(state: AlignmentsUiState = this.state, actions: AlignmentsActions = AlignmentsActions()) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { AlignmentsSheet(state, actions) {} } }
    }

    @Test
    fun `S049 RF-14 cada eje muestra su nombre su fichero sus PK y si lleva rasante`() {
        show()

        compose.onNodeWithTag(ALIGNMENTS_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithText("Eje A").assertIsDisplayed()
        compose.onAllNodesWithText("380,0 m · del PK 1+000,50 al 1+380,50").fetchSemanticsNodes().size shouldBe 2
        compose.onNodeWithText("Con rasante").assertExists()
        compose.onNodeWithText("Sin rasante").assertExists()
    }

    @Test
    fun `S049 RF-14 el eje activo se dice con texto y los demas figuran inactivos`() {
        show()

        compose.onNodeWithTag(alignmentStateTag("a")).assertIsDisplayed()
        compose.onNodeWithText("Eje activo").assertExists()
        compose.onNodeWithText("Inactivo").assertExists()
    }

    @Test
    fun `S049 RF-14 sin ejes el panel lo dice`() {
        show(AlignmentsUiState())

        compose.onNodeWithTag(ALIGNMENTS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `S049 RF-14 activar un eje y desactivar el activo`() {
        val calls = mutableListOf<String?>()
        show(actions = AlignmentsActions(onActivate = { calls += it }))

        compose.onNodeWithTag(alignmentActivateTag("b")).performScrollTo().performClick()
        compose.onNodeWithTag(alignmentDeactivateTag("a")).performScrollTo().performClick()

        calls shouldBe listOf("b", null)
    }

    @Test
    fun `S049 RF-19 mostrar u ocultar cada eje segun su estado y decirlo con texto`() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        show(actions = AlignmentsActions(onVisible = { id, visible -> calls += id to visible }))

        compose.onNodeWithText("Visible en el mapa").assertExists()
        compose.onNodeWithText("Oculto en el mapa").assertExists()
        compose.onNodeWithTag(alignmentVisibilityTag("a")).performScrollTo().performClick()
        compose.onNodeWithTag(alignmentVisibilityTag("b")).performScrollTo().performClick()

        calls shouldBe listOf("a" to false, "b" to true)
    }

    @Test
    fun `S049 RF-14 renombrar pide el nombre nuevo y lo envia`() {
        var renamed: Pair<String, String>? = null
        show(
            actions = AlignmentsActions(onRename = { id, name ->
                renamed = id to name
                true
            }),
        )

        compose.onNodeWithTag(alignmentRenameTag("a")).performScrollTo().performClick()
        compose.onNodeWithTag(ALIGNMENT_RENAME_FIELD_TAG).performTextClearance()
        compose.onNodeWithTag(ALIGNMENT_RENAME_FIELD_TAG).performTextInput("Carretera")
        compose.onNodeWithTag(ALIGNMENT_RENAME_CONFIRM_TAG).performClick()

        renamed shouldBe ("a" to "Carretera")
        compose.onNodeWithTag(ALIGNMENT_RENAME_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun `S049 RF-14 borrar pide confirmacion y cancelar no borra nada`() {
        val deleted = mutableListOf<String>()
        show(actions = AlignmentsActions(onDelete = { deleted += it }))

        compose.onNodeWithTag(alignmentDeleteTag("a")).performScrollTo().performClick()
        compose.onNodeWithText("Se borrará el eje «Eje A» con su rasante. Los puntos del proyecto no se tocan.").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        deleted shouldBe emptyList()

        compose.onNodeWithTag(alignmentDeleteTag("a")).performScrollTo().performClick()
        compose.onNodeWithTag(ALIGNMENT_DELETE_CONFIRM_TAG).performClick()
        deleted shouldBe listOf("a")
    }

    @Test
    fun `S054 RF-1 cada eje ofrece Replantear eje y lleva su identificador a la accion`() {
        val staked = mutableListOf<String>()
        show(actions = AlignmentsActions(onStake = { staked += it }))

        compose.onNodeWithTag(alignmentStakeTag("a")).performScrollTo().assertTextContains("Replantear eje")
        compose.onNodeWithTag(alignmentStakeTag("a")).performClick()
        compose.onNodeWithTag(alignmentStakeTag("b")).performScrollTo().performClick()

        staked shouldBe listOf("a", "b")
    }

    @Test
    fun `S054 RF-12 cada eje ofrece Analizar y lleva su identificador a la accion`() {
        val analysed = mutableListOf<String>()
        show(actions = AlignmentsActions(onAnalyze = { analysed += it }))

        compose.onNodeWithTag(alignmentAnalyzeTag("a")).performScrollTo().assertTextContains("Analizar")
        compose.onNodeWithTag(alignmentAnalyzeTag("a")).performClick()
        compose.onNodeWithTag(alignmentAnalyzeTag("b")).performScrollTo().performClick()

        analysed shouldBe listOf("a", "b")
    }

    @Test
    fun `S050 RF-1 cada eje ofrece Secciones y lleva su identificador a la accion`() {
        val calls = mutableListOf<String>()
        show(actions = AlignmentsActions(onSections = { calls += it }))

        compose.onNodeWithTag(alignmentSectionsTag("a")).performScrollTo().performClick()
        compose.onNodeWithTag(alignmentSectionsTag("b")).performScrollTo().performClick()

        calls shouldBe listOf("a", "b")
    }

    @Test
    fun `S051 RF-1 cada eje ofrece Taludes y lleva su identificador a la accion`() {
        val calls = mutableListOf<String>()
        show(actions = AlignmentsActions(onSlopes = { calls += it }))

        compose.onNodeWithTag(alignmentSlopesTag("a")).performScrollTo().performClick()
        compose.onNodeWithTag(alignmentSlopesTag("b")).performScrollTo().performClick()

        calls shouldBe listOf("a", "b")
    }

    @Test
    fun `S051 RF-9 cada eje ofrece Replantear taludes y lleva su identificador a la accion`() {
        val calls = mutableListOf<String>()
        show(actions = AlignmentsActions(onStakeSlopes = { calls += it }))

        compose.onNodeWithTag(alignmentStakeSlopesTag("a")).performScrollTo().performClick()
        compose.onNodeWithTag(alignmentStakeSlopesTag("b")).performScrollTo().performClick()

        calls shouldBe listOf("a", "b")
    }

    @Test
    fun `S052 RF-1 la hoja ofrece Tomar eje aunque no haya ejes y avisa al pulsarlo`() {
        val calls = mutableListOf<String>()
        show(AlignmentsUiState(), AlignmentsActions(onTakeAxis = { calls += "take" }))

        compose.onNodeWithTag(ALIGNMENTS_TAKE_AXIS_TAG).performClick()

        calls shouldBe listOf("take")
    }

    @Test
    fun `S052 RF-10 cada eje ofrece Perfil longitudinal y lleva su identificador a la accion`() {
        val calls = mutableListOf<String>()
        show(actions = AlignmentsActions(onLongitudinal = { calls += it }))

        compose.onNodeWithTag(alignmentLongitudinalTag("a")).performScrollTo().performClick()
        compose.onNodeWithTag(alignmentLongitudinalTag("b")).performScrollTo().performClick()

        calls shouldBe listOf("a", "b")
    }

    @Test
    fun `S052 RF-16 cada eje ofrece Perfil transversal y lleva su identificador a la accion`() {
        val calls = mutableListOf<String>()
        show(actions = AlignmentsActions(onCross = { calls += it }))

        compose.onNodeWithTag(alignmentCrossTag("a")).performScrollTo().performClick()
        compose.onNodeWithTag(alignmentCrossTag("b")).performScrollTo().performClick()

        calls shouldBe listOf("a", "b")
    }

    @Test
    fun `S052 RF-22 la hoja ofrece Perfiles tomados aunque no haya ejes y avisa al pulsarlo`() {
        val calls = mutableListOf<String>()
        show(AlignmentsUiState(), AlignmentsActions(onProfiles = { calls += "profiles" }))

        compose.onNodeWithTag(ALIGNMENTS_PROFILES_TAG).performClick()

        calls shouldBe listOf("profiles")
    }
}
