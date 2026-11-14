package es.topophone.app.ui.cogo

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoPoint
import es.topophone.core.survey.cogo.CogoScale
import es.topophone.core.survey.cogo.Inverse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de la inversa: filas del resultado, distancia activa con texto, copiar e intercambiar (S038/RF-5 a RF-9). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class InverseScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val a = CogoPoint(440_000.0, 4_474_000.0, 650.0, 40.4144604937, -3.7071991234, 699.5)
    private val b = CogoPoint(440_100.0, 4_474_100.0, 660.0, 40.4153, -3.7060, 709.5)

    private fun ui(
        mode: DistanceMode = DistanceMode.GRID,
        linear: LinearUnit = LinearUnit.METRE,
        angular: AngularUnit = AngularUnit.DMS,
        from: CogoPoint = a,
        to: CogoPoint = b,
    ) = InverseUi(
        loaded = true,
        mode = mode,
        linearUnit = linear,
        angularUnit = angular,
        a = CogoSlotValue.Found(from, "P1"),
        b = CogoSlotValue.Found(to, "P2"),
        result = Inverse.of(from, to, CogoScale.forFirst(from, ProjectCrs.ETRS89_UTM30, mode)),
    )

    private fun show(state: InverseUi, actions: InverseActions = InverseActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { InverseScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    @Test
    fun `S038 RF-6 las filas del resultado salen en las unidades del proyecto`() {
        show(ui())

        compose.onNodeWithTag(inverseRowTag("grid")).performScrollTo().assertTextContains("141,421 m", substring = true)
        compose.onNodeWithTag(inverseRowTag("azimuth")).performScrollTo().assertTextContains("45° 00′ 00,00″", substring = true)
        compose.onNodeWithTag(inverseRowTag("reverse")).performScrollTo().assertTextContains("225° 00′ 00,00″", substring = true)
        compose.onNodeWithTag(inverseRowTag("de")).performScrollTo().assertTextContains("100,000 m", substring = true)
        compose.onNodeWithTag(inverseRowTag("dh")).performScrollTo().assertTextContains("10,000 m", substring = true)
    }

    @Test
    fun `S038 RF-6 en pies y en gon las cifras cambian de unidad`() {
        show(ui(linear = LinearUnit.INTERNATIONAL_FOOT, angular = AngularUnit.GON))

        compose.onNodeWithTag(inverseRowTag("de")).performScrollTo().assertTextContains("328,084", substring = true)
        compose.onNodeWithTag(inverseRowTag("azimuth")).performScrollTo().assertTextContains("50,000000 gon", substring = true)
    }

    @Test
    fun `S038 RF-7 con altura salen la distancia inclinada y la pendiente`() {
        show(ui())

        compose.onNodeWithTag(inverseRowTag("slant")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(inverseRowTag("slope")).performScrollTo().assertTextContains("%", substring = true)
    }

    @Test
    fun `S038 RF-7 sin altura en un punto no salen ni inclinada ni pendiente y la diferencia de altura lo dice con un guion`() {
        show(ui(to = CogoPoint(440_100.0, 4_474_100.0)))

        compose.onNodeWithTag(inverseRowTag("dh")).performScrollTo().assertTextContains("—", substring = true)
        compose.onAllNodesWithTag(inverseRowTag("slant")).fetchSemanticsNodes().size shouldBe 0
        compose.onAllNodesWithTag(inverseRowTag("slope")).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S038 RF-5 la distancia activa se dice con texto en cuadricula y en terreno`() {
        show(ui(mode = DistanceMode.GRID))
        compose.onNodeWithTag(inverseRowTag("grid")).performScrollTo().assertTextContains("(activa)", substring = true)
        compose.onAllNodesWithTag(inverseRowTag("ground")).fetchSemanticsNodes().size shouldBe 1
    }

    @Test
    fun `S038 RF-5 en terreno la activa es la de terreno`() {
        show(ui(mode = DistanceMode.GROUND))

        compose.onNodeWithTag(inverseRowTag("ground")).performScrollTo().assertTextContains("(activa)", substring = true)
    }

    @Test
    fun `S038 RF-8 puntos coincidentes dan distancia 0, azimut indefinido y un aviso`() {
        show(ui(to = a))

        compose.onNodeWithTag(INVERSE_COINCIDENT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(inverseRowTag("azimuth")).performScrollTo().assertTextContains("Indefinido", substring = true)
        compose.onNodeWithTag(inverseRowTag("grid")).performScrollTo().assertTextContains("0,000 m", substring = true)
    }

    @Test
    fun `S038 RF-9 copiar da el resultado como texto y intercambiar avisa`() {
        var copied = ""
        var swaps = 0
        show(ui(), InverseActions(onCopy = { copied = it }, onSwap = { swaps++ }))

        compose.onNodeWithTag(INVERSE_COPY_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(INVERSE_SWAP_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        copied shouldContain "Distancia de cuadrícula (activa): 141,421 m"
        copied shouldContain "Azimut de A a B: 45° 00′ 00,00″"
        copied shouldContain "Azimut de B a A: 225° 00′ 00,00″"
        swaps shouldBe 1
    }

    @Test
    fun `S038 RF-6 sin los dos puntos la pantalla pide elegirlos`() {
        show(InverseUi(loaded = true))

        compose.onNodeWithTag(INVERSE_HINT_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(INVERSE_COPY_TAG).fetchSemanticsNodes().size shouldBe 0
    }
}
