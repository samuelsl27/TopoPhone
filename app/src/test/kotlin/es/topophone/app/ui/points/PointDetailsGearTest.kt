package es.topophone.app.ui.points

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.GearUsed
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PoleKind
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

/** El jalón y la antena con los que se midió un punto, en su ficha (S046/RF-24, RF-29). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsGearTest {

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

    private fun withGear(gear: GearUsed?): Point = PointFixtures.measured("12").let {
        it.copy(measurement = it.measurement!!.copy(gear = gear))
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
    fun `S046 RF-24 la ficha de un punto medido con jalon vertical muestra el jalon, el perfil y la altura de antena`() {
        show(withGear(GearUsed("Jalón corto", PoleKind.VERTICAL, 1.2, "Reach RS4/RS4 Pro", 0.095, 1.295)))

        row("point_gear_pole").shows(text(R.string.point_gear_pole_vertical, "Jalón corto", "1,200 m"))
        row("point_gear_profile").shows(text(R.string.point_gear_profile_value, "Reach RS4/RS4 Pro", "0,095 m"))
        row("point_gear_antenna_height").shows("1,295 m")
    }

    @Test
    fun `S046 RF-16 y RF-24 un jalon inclinado se muestra como inclinada con lo escrito`() {
        show(withGear(GearUsed("Inclinado", PoleKind.SLANT, 1.95, "Propio", 0.12, 2.069076)))

        row("point_gear_pole").shows(text(R.string.point_gear_pole_slant, "Inclinado", "1,950 m"))
    }

    @Test
    fun `S046 RF-29 un punto de antes de S046 no tiene filas de equipo y conserva la altura del jalon`() {
        show(withGear(null))

        row("point_pole_height").shows("1,800 m")
        compose.onNodeWithTag("point_gear_pole").assertDoesNotExist()
        compose.onNodeWithTag("point_gear_profile").assertDoesNotExist()
        compose.onNodeWithTag("point_gear_antenna_height").assertDoesNotExist()
    }

    @Test
    fun `S046 RF-24 un punto manual no tiene jalon ni equipo`() {
        show(PointFixtures.manual("M1"))

        compose.onNodeWithTag("point_gear_pole").assertDoesNotExist()
    }
}
