package es.topophone.app.ui.linear

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.survey.linear.LinearSurveyKind
import es.topophone.core.model.survey.linear.SurveyFlag
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * La lista de tomas del proyecto con estados ya hechos: el tipo, el eje, el PK, la fecha y los puntos de cada una, ver con su alzado y su
 * CSV, y borrar con confirmación (S052/RF-22; RNF-T7, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LinearSurveysScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val created = 1_793_000_000_000L // noviembre de 2026

    private fun item(
        id: String,
        kind: LinearSurveyKind,
        axis: String? = "Eje A",
        chainage: Double? = null,
        to: Double? = null,
        points: Int = 3,
        open: Boolean = false,
    ) = LinearSurveyItem(id, kind, axis, chainage, to, created, if (open) null else created + 1, points, open)

    private val items = listOf(
        item("l", LinearSurveyKind.LONGITUDINAL, chainage = 0.0, to = 200.0),
        item("c", LinearSurveyKind.CROSS, chainage = 1050.0, points = 6),
        item("a", LinearSurveyKind.AXIS, points = 0),
    )

    private val calls = mutableListOf<String>()

    /** Un texto dentro de la tarjeta [tag]: los textos de una tarjeta no se funden en un solo nodo. */
    private fun inCard(tag: String, text: String) = compose.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag)))

    private fun detail(): LinearSurveyDetail {
        val rows = listOf(
            LinearSurveyDetailRow("L1", 10.0, 0.1, 100.0, 99.5, 0.5, null, SurveyFlag.NONE, true),
            LinearSurveyDetailRow("L2", 50.0, 0.2, 101.0, 101.0, 0.0, null, SurveyFlag.NONE, true),
        )
        val chart = ProfileChart(
            ProfileAxis.CHAINAGE,
            rows.map { ProfileChartPoint(it.name, it.chainageM!!, it.groundM!!, it.referenceM, it.dHeightM, true, SurveyFlag.NONE) },
            listOf(10.0 to 99.5, 50.0 to 101.0),
        )
        return LinearSurveyDetail(items.first(), rows, chart)
    }

    private fun show(state: LinearSurveysUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            LinearSurveysScreen(
                ThemeState.DEFAULT,
                {},
                state,
                LinearSurveysActions(
                    onView = { calls += "view:$it" },
                    onCloseDetail = { calls += "closeDetail" },
                    onRequestDelete = { calls += "delete:$it" },
                    onCancelDelete = { calls += "cancel" },
                    onConfirmDelete = { calls += "confirm" },
                ),
                { true },
                {},
            )
        }
    }

    @Test
    fun `S052 RF-22 sin tomas dice que no hay y como hacerlas`() {
        show(LinearSurveysUiState(loading = false))

        compose.onNodeWithTag(LSURVEYS_EMPTY_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(LSURVEYS_LIST_TAG).assertCountEquals(0)
    }

    @Test
    fun `S052 RF-22 cada toma dice su tipo el eje el PK la fecha y los puntos del perfil`() {
        show(LinearSurveysUiState(loading = false, items = items))

        inCard(lsurveyItemTag("c"), "Perfil transversal").assertExists()
        inCard(lsurveyItemTag("c"), "Eje A").assertExists()
        inCard(lsurveyItemTag("c"), "PK 1+050,00").assertExists()
        inCard(lsurveyItemTag("c"), "6 puntos en el perfil").assertExists()
        inCard(lsurveyItemTag("c"), "/10/26").assertExists()
        inCard(lsurveyItemTag("l"), "Perfil longitudinal").assertExists()
        inCard(lsurveyItemTag("l"), "De PK 0+000,00 a PK 0+200,00").assertExists()
        compose.onNodeWithTag(LSURVEYS_LIST_TAG).performScrollToKey("a")
        inCard(lsurveyItemTag("a"), "Eje tomado").assertExists()
    }

    @Test
    fun `S052 RF-22 una toma sin terminar lo dice y una con el eje borrado tambien`() {
        show(LinearSurveysUiState(loading = false, items = listOf(item("l", LinearSurveyKind.LONGITUDINAL, axis = null, open = true))))

        inCard(lsurveyItemTag("l"), "Sin terminar").assertExists()
        inCard(lsurveyItemTag("l"), "Eje borrado").assertExists()
    }

    @Test
    fun `S052 RF-22 ver y borrar avisan con botones de 56 dp y un eje tomado no se puede ver`() {
        show(LinearSurveysUiState(loading = false, items = items))

        compose.onNodeWithTag(lsurveyViewTag("l")).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(LSURVEYS_LIST_TAG).performScrollToKey("c")
        compose.onNodeWithTag(lsurveyDeleteTag("c")).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("view:l", "delete:c")
        compose.onNodeWithTag(LSURVEYS_LIST_TAG).performScrollToKey("a")
        compose.onAllNodesWithTag(lsurveyViewTag("a")).assertCountEquals(0)
        compose.onNodeWithTag(lsurveyDeleteTag("a")).assertIsDisplayed()
    }

    @Test
    fun `S052 RF-22 al ver una toma se abre el alzado con exportar y cerrar`() {
        show(LinearSurveysUiState(loading = false, projectName = "Obra", items = items, detail = detail()))

        compose.onNodeWithTag(LSURVEYS_DETAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PROFILE_VIEW_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PROFILE_EXPORT_FILE_TAG).assertTextContains("Obra_perfil-longitudinal_", substring = true)
        compose.onNodeWithTag(LSURVEYS_DETAIL_CLOSE_TAG).assertHeightIsAtLeast(56.dp).performClick()
        calls shouldBe listOf("closeDetail")
    }

    @Test
    fun `S052 RF-22 borrar pregunta antes y la confirmacion avisa y los puntos no se mencionan como borrados`() {
        show(LinearSurveysUiState(loading = false, items = items, toDelete = items[1]))

        compose.onNodeWithText("¿Borrar la toma?").assertIsDisplayed()
        compose.onNodeWithText("Se borra la toma «Perfil transversal», no los puntos medidos.").assertIsDisplayed()
        compose.onNodeWithTag(LSURVEYS_DELETE_CONFIRM_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(LSURVEYS_DELETE_CANCEL_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("confirm", "cancel")
    }

    @Test
    fun `S052 RF-22 mientras carga no se ve nada`() {
        show(LinearSurveysUiState())

        compose.onAllNodesWithTag(LSURVEYS_LIST_TAG).assertCountEquals(0)
        compose.onAllNodesWithText("Aún no hay tomas", substring = true).assertCountEquals(0)
    }
}
