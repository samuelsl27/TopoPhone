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
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.StakeoutDiff
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La ficha de un punto replanteado contra la superficie solo en cota: la superficie y la cota, sin planta (S062/RF-20). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfaceStakeRefTextTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
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

    private fun surfacePoint() = measured("SUP1", "p1").let { point ->
        val ref = StakeoutDiff.surfaceRef(point.id, "explanacion", point.coordinates, point.coordinates.height!! + 0.052, 0.03).copy(
            elevationSource = ElevationSource.SURFACE,
            surfaceName = "explanacion",
            surfaceImportedAtMs = 1_790_000_000_000L,
            surfaceElevationM = point.coordinates.height!! - 0.048,
            verticalOffsetM = 0.1,
        )
        point.copy(origin = PointOrigin.STAKED, staked = ref)
    }

    private fun show() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(ThemeState.DEFAULT, {}, PointDetailsUiState(project, surfacePoint()), PointDetailsActions(), onBack = {})
            }
        }
    }

    @Test
    fun `S062 RF-20 la ficha da la superficie su cota el desplazamiento y el terraplen`() {
        show()

        compose.onNodeWithTag("point_stakeout_surface").performScrollTo().shows("explanacion")
        compose.onNodeWithTag("point_stakeout_vertical_offset").performScrollTo().shows("+0,100 m")
        compose.onNodeWithTag("point_stakeout_level").performScrollTo().shows("Terraplén 0,052 m")
        compose.onNodeWithTag("point_stakeout_tolerances").performScrollTo().shows("Tolerancia vertical").shows("0,030 m")
        compose.onNodeWithTag("point_stakeout_within").performScrollTo().shows("Fuera de la tolerancia")
    }

    @Test
    fun `S062 RF-20 sin punto de diseno ni diferencias en planta`() {
        show()

        listOf(
            "point_stakeout_design",
            "point_stakeout_design_e",
            "point_stakeout_design_n",
            "point_stakeout_diff_n",
            "point_stakeout_diff_e",
        )
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithTag("point_stakeout_diff_d").assertDoesNotExist()
    }
}
