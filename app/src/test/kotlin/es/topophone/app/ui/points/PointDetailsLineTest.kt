package es.topophone.app.ui.points

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.PointFixtures.staked
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.StakeSource
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

/** La ficha de un punto replanteado de una línea del proyecto: de qué línea viene, el modo, el PK y el desplazamiento (S027/RF-13). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsLineTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
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

    private fun show(point: Point) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(ThemeState.DEFAULT, {}, PointDetailsUiState(project, point), PointDetailsActions(), onBack = {})
            }
        }
    }

    private fun fromLine(): Point {
        val base = staked("BORD_1_1_stkd")
        val ref = base.staked!!.copy(
            designId = "line:l1",
            designName = "BORD_1",
            source = StakeSource.ProjectLine("l1", "BORD_1"),
            mode = LineStakeoutMode.ALONG,
            chainageM = 45.123,
            lateralOffsetM = -1.5,
            dLateralM = 0.012,
        )
        return base.copy(staked = ref)
    }

    @Test
    fun `S027 RF-13 la ficha dice de que linea viene el punto, el modo, el PK, el desplazamiento y el error lateral`() {
        show(fromLine())

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo()
        compose.onNodeWithTag("point_stakeout_line").performScrollTo().shows("BORD_1")
        compose.onNodeWithTag("point_stakeout_mode").performScrollTo().shows("A lo largo de la línea")
        compose.onNodeWithTag("point_stakeout_chainage").performScrollTo().shows("45,123 m")
        compose.onNodeWithTag("point_stakeout_offset").performScrollTo().shows("-1,500 m")
        compose.onNodeWithTag("point_stakeout_diff_lateral").performScrollTo().shows("+0,012 m")
    }

    @Test
    fun `S027 RF-13 una linea no tiene filas de dibujo, capa ni eje`() {
        show(fromLine())

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo()
        compose.onNodeWithTag("point_stakeout_drawing").assertDoesNotExist()
        compose.onNodeWithTag("point_stakeout_layer").assertDoesNotExist()
        compose.onNodeWithTag("point_stakeout_axis").assertDoesNotExist()
    }

    @Test
    fun `S027 RF-13 un replanteo de un punto no tiene fila de linea`() {
        show(staked("12_stkd"))

        compose.onNodeWithTag(POINT_STAKEOUT_INFO_TAG).performScrollTo()
        compose.onNodeWithTag("point_stakeout_line").assertDoesNotExist()
    }

    @Test
    fun `S027 RF-16 si la linea ya no existe la ficha conserva su nombre`() {
        // La fuente guarda el nombre, no una referencia: borrar la linea no cambia lo que se lee.
        show(fromLine())

        compose.onNodeWithTag("point_stakeout_line").performScrollTo().shows("BORD_1")
    }
}
