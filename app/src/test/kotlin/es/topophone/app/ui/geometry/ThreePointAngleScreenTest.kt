package es.topophone.app.ui.geometry

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.geometry.AnglePoint
import es.topophone.core.survey.geometry.AngleResult
import es.topophone.core.survey.geometry.GeometryNotice
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla del ángulo entre tres puntos: elegir A, B y C, y el resultado (S026/RF-13, RF-14, RF-15). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ThreePointAngleScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val a = AnglePointOption("a", "P1")
    private val b = AnglePointOption("b", "P2")
    private val c = AnglePointOption("c", "P3")

    private fun ui(
        result: AngleResult? = null,
        picked: Boolean = true,
        picking: AnglePoint? = null,
        mode: DistanceMode = DistanceMode.GRID,
        angular: AngularUnit = AngularUnit.DECIMAL_DEGREES,
    ) = ThreePointAngleUi(
        loaded = true,
        mode = mode,
        linearUnit = LinearUnit.METRE,
        angularUnit = angular,
        a = a.takeIf { picked },
        b = b.takeIf { picked },
        c = c.takeIf { picked },
        picking = picking,
        candidates = listOf(a, b, c),
        result = result,
    )

    private val angle = AngleResult.Angle(
        angleRad = Math.PI / 2,
        azimuthBaRad = 0.0,
        azimuthBcRad = Math.PI / 2,
        distanceBa = 100.0,
        distanceBc = 123.4567,
        notices = emptySet(),
    )

    private fun show(state: ThreePointAngleUi, actions: ThreePointAngleActions = ThreePointAngleActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ThreePointAngleScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    @Test
    fun `S026 RF-13 sin puntos elegidos dice que hay que elegir tres y los tres botones miden 56 dp`() {
        show(ui(picked = false))

        compose.onNodeWithTag(ANGLE_HINT_TAG).assertIsDisplayed()
        for (slot in AnglePoint.entries) compose.onNodeWithTag(angleSlotTag(slot)).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S026 RF-13 cada boton abre el selector de su punto`() {
        val opened = mutableListOf<AnglePoint>()
        show(ui(picked = false), ThreePointAngleActions(onPick = { opened += it }))

        compose.onNodeWithTag(angleSlotTag(AnglePoint.B)).performClick()
        compose.onNodeWithTag(angleSlotTag(AnglePoint.A)).performClick()

        opened shouldBe listOf(AnglePoint.B, AnglePoint.A)
    }

    @Test
    fun `S026 RF-13 el selector lista los puntos y elige uno`() {
        var chosen: String? = null
        show(ui(picked = false, picking = AnglePoint.A), ThreePointAngleActions(onChoose = { chosen = it }))

        compose.onNodeWithTag(ANGLE_PICKER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(anglePickerPointTag("P2")).performClick()

        chosen shouldBe "b"
    }

    @Test
    fun `S026 RF-14 muestra el angulo en B, los azimuts y las distancias de BA y BC`() {
        show(ui(angle))

        compose.onNodeWithTag(ANGLE_RESULT_TAG).assertTextContains("90,", substring = true)
        compose.onNodeWithTag(ANGLE_AZIMUTH_BA_TAG).assertTextContains("0,", substring = true)
        compose.onNodeWithTag(ANGLE_AZIMUTH_BC_TAG).assertTextContains("90,", substring = true)
        compose.onNodeWithTag(ANGLE_DISTANCE_BA_TAG).assertTextContains("100,000", substring = true)
        compose.onNodeWithTag(ANGLE_DISTANCE_BC_TAG).assertTextContains("123,457", substring = true)
    }

    @Test
    fun `S026 RF-12 el angulo va en la unidad angular del proyecto`() {
        show(ui(angle, angular = AngularUnit.GON))

        compose.onNodeWithTag(ANGLE_RESULT_TAG).assertTextContains("100,", substring = true)
    }

    @Test
    fun `S026 RF-9 muestra cual es la distancia activa y deja cambiarla`() {
        var chosen: DistanceMode? = null
        show(ui(angle, mode = DistanceMode.GROUND), ThreePointAngleActions(onMode = { chosen = it }))

        compose.onNodeWithTag(GEOMETRY_ACTIVE_MODE_TAG).assertTextContains("terreno", substring = true, ignoreCase = true)
        compose.onNodeWithTag(GEOMETRY_MODE_GRID_TAG).performClick()
        chosen shouldBe DistanceMode.GRID
    }

    @Test
    fun `S026 RF-15 con dos puntos coincidentes no hay angulo y se dice cuales`() {
        show(ui(AngleResult.Coincident(listOf(AnglePoint.A to AnglePoint.B))))

        compose.onNodeWithTag(ANGLE_COINCIDENT_TAG).assertTextContains("A (P1) y B (P2)", substring = true)
        compose.onNodeWithTag(ANGLE_COINCIDENT_TAG).assertTextContains("P1", substring = true)
        compose.onNodeWithTag(ANGLE_RESULT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S026 RF-10 los avisos del factor se muestran tambien en el angulo`() {
        show(ui(angle.copy(notices = setOf(GeometryNotice.FACTOR_WITHOUT_HEIGHT)), mode = DistanceMode.GROUND))

        compose.onNodeWithTag(GEOMETRY_NOTICE_FACTOR_NO_HEIGHT_TAG).assertExists()
    }
}
