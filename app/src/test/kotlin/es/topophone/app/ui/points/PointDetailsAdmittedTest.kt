package es.topophone.app.ui.points

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.SolutionType
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

/** El tipo de solución y los tipos admitidos con los que se midió un punto, en su ficha (S047/RF-16). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsAdmittedTest {

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

    private fun measured(admitted: AdmittedSolutions?, byType: Int?): Point = PointFixtures.measured("12").let {
        it.copy(
            measurement = it.measurement!!.copy(
                quality = it.measurement!!.quality.copy(solution = "FLOAT", admitted = admitted, epochsDiscardedByType = byType),
            ),
        )
    }

    private fun show(point: Point) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            PointDetailsScreen(ThemeState.DEFAULT, {}, PointDetailsUiState(project, point), PointDetailsActions(), {})
        }
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    private fun SemanticsNodeInteraction.shows(text: String) = assert(
        hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)),
    )

    @Test
    fun `S047 RF-16 la ficha muestra el tipo de solucion y los tipos admitidos al medir`() {
        show(measured(AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT)), 4))

        row("point_admitted").shows("FIX, FLOAT")
        row("point_epochs_by_type").shows("4")
    }

    @Test
    fun `S047 RF-16 un punto de antes de S047 no tiene filas de tipos admitidos`() {
        show(measured(null, null))

        compose.onNodeWithTag("point_admitted").assertDoesNotExist()
        compose.onNodeWithTag("point_epochs_by_type").assertDoesNotExist()
    }

    @Test
    fun `S047 RF-16 un punto manual no tiene tipos admitidos`() {
        show(PointFixtures.manual("M1"))

        compose.onNodeWithTag("point_admitted").assertDoesNotExist()
    }
}
