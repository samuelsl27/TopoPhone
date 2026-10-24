package es.topophone.app.ui.points

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.points.PointFixtures.staked
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La ficha de un punto replanteado: de qué diseño viene y cuánto se desvió (S018/RF-32). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsStakeoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun project(unit: LinearUnit = LinearUnit.METRE) = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = unit,
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun SemanticsNodeInteraction.shows(text: String): SemanticsNodeInteraction =
        assert(hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

    private fun show(state: PointDetailsUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PointDetailsScreen(ThemeState.DEFAULT, {}, state, PointDetailsActions(), onBack = {}) }
        }
    }

    @Test
    fun `S018 RF-32 la ficha del replanteado da el punto de diseno, sus coordenadas y las diferencias`() {
        show(PointDetailsUiState(project(), staked("12_stkd")))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo()
            .shows("12")
            .shows("440.290,458 m")
            .shows("4.474.257,382 m")
            .shows("648,190 m")
            .shows("+0,012 m")
            .shows("-0,004 m")
            .shows("0,013 m")
            .shows("-0,021 m")
    }

    @Test
    fun `S018 RF-32 las diferencias y el diseno van en las unidades del proyecto`() {
        show(PointDetailsUiState(project(LinearUnit.INTERNATIONAL_FOOT), staked("12_stkd")))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo()
            .shows("1.444.522,")
            .shows("+0,039 ft")
            .shows("-0,013 ft")
            .shows("-0,069 ft")
    }

    @Test
    fun `S018 RF-32 sin cota de diseno la ficha lo dice en lugar de la diferencia de cota`() {
        show(PointDetailsUiState(project(), staked("12_stkd", dHeightM = null)))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo().shows("Sin cota")
    }

    @Test
    fun `S018 RF-32 un punto medido no tiene tarjeta de replanteo`() {
        show(PointDetailsUiState(project(), measured("12")))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).assertDoesNotExist()
    }
}
