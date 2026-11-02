package es.topophone.app.ui.linear

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.survey.linear.SurveyFlag
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El alzado de un perfil con estados ya hechos: el texto del punto elegido con su desmonte o terraplén, la marca de los puntos fuera del
 * perfil, el zoom y el desplazamiento con botones y la frase para TalkBack (S052/RF-13, RF-19; RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProfileViewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun point(x: Double, ground: Double, reference: Double?, inProfile: Boolean = true, flag: SurveyFlag = SurveyFlag.NONE) =
        ProfileChartPoint("P", x, ground, reference, reference?.let { ground - it }, inProfile, flag)

    private val longitudinal = ProfileChart(
        ProfileAxis.CHAINAGE,
        listOf(
            point(0.0, 100.0, 99.5),
            point(100.0, 101.0, 101.0),
            point(200.0, 100.5, 101.2),
            point(300.0, 105.0, null, inProfile = false, flag = SurveyFlag.OUT_OF_AXIS),
        ),
        reference = listOf(0.0 to 99.5, 200.0 to 101.2),
    )

    private fun show(chart: ProfileChart, unit: LinearUnit = LinearUnit.METRE) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) { ProfileView(chart, unit) }
    }

    /** Toca el lienzo a la fracción [x] de su anchura. */
    private fun tapAt(x: Float) {
        compose.onNodeWithTag(PROFILE_CANVAS_TAG).performTouchInput { click(Offset(width * x, height / 2f)) }
    }

    @Test
    fun `S052 RF-13 sin puntos en el perfil dice que no hay y no dibuja el grafico`() {
        show(ProfileChart(ProfileAxis.CHAINAGE, emptyList(), emptyList()))

        compose.onNodeWithTag(PROFILE_EMPTY_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(PROFILE_CANVAS_TAG).assertCountEquals(0)
    }

    @Test
    fun `S052 RF-13 el lienzo lleva la frase para TalkBack con los puntos y los extremos del perfil`() {
        show(longitudinal)

        compose.onNodeWithTag(PROFILE_CANVAS_TAG).assertIsDisplayed()
        val description = compose.onNodeWithTag(PROFILE_CANVAS_TAG).fetchSemanticsNode().config.toString()
        description.contains("Perfil con 3 puntos") shouldBe true
        description.contains("0+000") shouldBe true
        description.contains("0+200") shouldBe true
    }

    @Test
    fun `S052 RF-13 antes de tocar nada el texto invita a tocar un punto`() {
        show(longitudinal)

        compose.onNodeWithTag(PROFILE_SELECTED_TAG).assertTextContains("Toca un punto", substring = true)
    }

    @Test
    fun `S052 RF-13 tocar el extremo izquierdo elige el primer punto con su desmonte`() {
        show(longitudinal)

        tapAt(0.05f)

        val text = compose.onNodeWithTag(PROFILE_SELECTED_TAG)
        text.assertTextContains("PK 0+00", substring = true)
        text.assertTextContains("Terreno 100,000 m", substring = true)
        text.assertTextContains("Rasante 99,500 m", substring = true)
        text.assertTextContains("Desmonte 0,500 m", substring = true)
    }

    @Test
    fun `S052 RF-13 un punto bajo la rasante es terraplen y uno a su cota no es ninguno de los dos`() {
        show(longitudinal)

        tapAt(0.95f)
        compose.onNodeWithTag(PROFILE_SELECTED_TAG).assertTextContains("Terraplén 0,700 m", substring = true)
        tapAt(0.5f)
        compose.onNodeWithTag(PROFILE_SELECTED_TAG).assertTextContains("A la cota de referencia", substring = true)
    }

    @Test
    fun `S052 RF-12 los puntos con marca no se pueden elegir`() {
        show(longitudinal)

        tapAt(0.99f)

        compose.onNodeWithTag(PROFILE_SELECTED_TAG).assertTextContains("PK 0+200", substring = true)
        compose.onNodeWithTag(PROFILE_SELECTED_TAG).assertTextContains("Terreno 100,500 m", substring = true)
    }

    @Test
    fun `S052 RF-13 el zoom y el desplazamiento con botones cambian el nivel de zoom y miden 56 dp`() {
        show(longitudinal)
        compose.onNodeWithTag(PROFILE_ZOOM_TAG).assertTextEquals("Zoom ×1,0")

        compose.onNodeWithTag(PROFILE_ZOOM_IN_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(PROFILE_ZOOM_TAG).assertTextEquals("Zoom ×2,0")
        compose.onNodeWithTag(PROFILE_ZOOM_IN_TAG).performClick()
        compose.onNodeWithTag(PROFILE_ZOOM_TAG).assertTextEquals("Zoom ×4,0")
        compose.onNodeWithTag(PROFILE_PAN_LEFT_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(PROFILE_PAN_RIGHT_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(PROFILE_ZOOM_OUT_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(PROFILE_ZOOM_TAG).assertTextEquals("Zoom ×2,0")
        compose.onNodeWithTag(PROFILE_FIT_TAG).assertHeightIsAtLeast(56.dp).performClick()
        compose.onNodeWithTag(PROFILE_ZOOM_TAG).assertTextEquals("Zoom ×1,0")
    }

    @Test
    fun `S052 RF-13 con zoom el toque elige el punto que hay bajo el dedo en la ventana visible`() {
        show(longitudinal)
        compose.onNodeWithTag(PROFILE_ZOOM_IN_TAG).performClick()
        compose.onNodeWithTag(PROFILE_ZOOM_IN_TAG).performClick() // ventana de 50 m centrada en 100

        tapAt(0.5f)

        compose.onNodeWithTag(PROFILE_SELECTED_TAG).assertTextContains("PK 0+100", substring = true)
    }

    @Test
    fun `S052 RF-19 en un transversal el eje horizontal es el desplazamiento con su lado y la referencia es la seccion de diseno`() {
        val cross = ProfileChart(
            ProfileAxis.OFFSET,
            listOf(point(-6.0, 99.0, 99.6), point(0.0, 100.45, 100.5), point(8.0, 99.4, 99.3)),
            reference = listOf(-10.0 to 99.0, 0.0 to 100.5, 10.0 to 99.0),
        )
        show(cross)

        tapAt(0.05f)

        val text = compose.onNodeWithTag(PROFILE_SELECTED_TAG)
        text.assertTextContains("izquierda", substring = true)
        text.assertTextContains("Sección de diseño 99,600 m", substring = true)
        text.assertTextContains("Terraplén 0,600 m", substring = true)
        tapAt(0.95f)
        compose.onNodeWithTag(PROFILE_SELECTED_TAG).assertTextContains("derecha", substring = true)
    }

    @Test
    fun `S052 RF-13 las cotas salen en la unidad del proyecto`() {
        show(longitudinal, LinearUnit.INTERNATIONAL_FOOT)

        tapAt(0.05f)

        compose.onNodeWithTag(PROFILE_SELECTED_TAG).assertTextContains("328,084 ft", substring = true) // 100 m
    }

    @Test
    fun `S052 RF-13 las marcas de los ejes salen de pasos de 1 2 o 5`() {
        ticks(0.0, 100.0, 5) shouldBe listOf(0.0, 20.0, 40.0, 60.0, 80.0, 100.0)
        ticks(3.0, 4.0, 4) shouldBe listOf(3.0, 3.5, 4.0)
        ticks(0.0, 0.0, 4) shouldBe emptyList()
        ticks(7.0, 9.4, 5).first() shouldBe 7.0
    }
}
