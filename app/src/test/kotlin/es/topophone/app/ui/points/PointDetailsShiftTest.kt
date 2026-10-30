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
import es.topophone.core.model.point.AppliedShift
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
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

/** El desplazamiento de base con que se midió un punto, en su ficha (S020/RF-13). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsShiftTest {

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

    private val applied = AppliedShift("s1", "V-12", BaseShiftVector(-2.1472, -1.9654, 0.5163), GeodeticPosition(40.41668, -3.70371, 699.0))

    private fun withShift(shift: AppliedShift?): Point = PointFixtures.measured("12").let {
        it.copy(measurement = it.measurement!!.copy(shift = shift))
    }

    private fun show(point: Point, unit: LinearUnit = LinearUnit.METRE) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            PointDetailsScreen(ThemeState.DEFAULT, {
            }, PointDetailsUiState(project.copy(linearUnit = unit), point), PointDetailsActions(), {})
        }
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    private fun SemanticsNodeInteraction.shows(text: String) = assert(
        hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)),
    )

    @Test
    fun `S020 RF-13 la ficha de un punto con base shift muestra el punto conocido y el vector aplicado`() {
        show(withShift(applied))

        row("point_shift_known").shows("V-12")
        row("point_shift_dx").shows("-2,1472 m")
        row("point_shift_dy").shows("-1,9654 m")
        row("point_shift_dz").shows("0,5163 m")
    }

    @Test
    fun `S020 RF-13 un punto medido sin base shift lo dice`() {
        show(withShift(null))

        row("point_shift_none").shows("Sin base shift")
        compose.onNodeWithTag("point_shift_dx").assertDoesNotExist()
    }

    @Test
    fun `S020 RF-13 el vector sale en la unidad del proyecto`() {
        show(withShift(applied), LinearUnit.INTERNATIONAL_FOOT)

        row("point_shift_dx").shows("-7,0446 ft")
    }
}
