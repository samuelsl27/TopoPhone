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
import es.topophone.core.model.guide.EquipmentProfile
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

/** El receptor con el que se midió un punto, en su ficha: solo los datos que el punto tiene (S043/RF-20). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointEquipmentDetailsTest {

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

    private fun withEquipment(equipment: EquipmentProfile?): Point = PointFixtures.measured("12").let {
        it.copy(measurement = it.measurement!!.copy(equipment = equipment))
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
    fun `S043 RF-20 la ficha muestra modelo, serie y firmware del receptor`() {
        show(withEquipment(EquipmentProfile("Reach RS3", "RS3-0042", "2026.2 estable")))

        row("point_equipment_model").shows("Reach RS3")
        row("point_equipment_serial").shows("RS3-0042")
        row("point_equipment_firmware").shows("2026.2 estable")
    }

    @Test
    fun `S043 RF-20 solo salen las filas que el punto tiene`() {
        show(withEquipment(EquipmentProfile("Reach RS3", "", "")))

        row("point_equipment_model").shows("Reach RS3")
        compose.onNodeWithTag("point_equipment_serial").assertDoesNotExist()
        compose.onNodeWithTag("point_equipment_firmware").assertDoesNotExist()
    }

    @Test
    fun `S043 RF-20 un punto sin equipo y uno manual no tienen filas de receptor`() {
        show(withEquipment(null))

        compose.onNodeWithTag("point_equipment_model").assertDoesNotExist()
        compose.onNodeWithTag("point_equipment_serial").assertDoesNotExist()
        compose.onNodeWithTag("point_equipment_firmware").assertDoesNotExist()
    }

    @Test
    fun `S043 RF-20 un punto manual no tiene datos del receptor`() {
        show(PointFixtures.manual("M1"))

        compose.onNodeWithTag("point_equipment_model").assertDoesNotExist()
    }
}
