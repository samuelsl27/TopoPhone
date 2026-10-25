package es.topophone.app.ui.points

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.MapPoints
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Los puntos importados se ven con el origen "Importado" en la lista, la ficha y el mapa (S028/RF-31). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ImportedPointsTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun imported(name: String = "I1"): Point = manual(name).copy(origin = PointOrigin.IMPORTED, importId = "imp-1")

    private fun showList(vararg points: Point) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true, points = points.map { it.summary() }, total = points.size),
                    ObjectListActions(),
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `S028 RF-31 la lista de objetos pone Importado en el origen de un punto importado`() {
        showList(imported("I1").copy(description = "Esquina"))

        compose.onNodeWithText("Importado · Esquina").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-31 el origen Importado es distinto de Manual y de Medido en la lista`() {
        showList(imported("I1").copy(description = "a"), manual("M1", description = "b"), measured("P1", description = "c"))

        compose.onNodeWithText("Importado · a").assertIsDisplayed()
        compose.onNodeWithText("Manual · b").assertIsDisplayed()
        compose.onNodeWithText("Medido · c").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-31 la ficha de un punto importado dice su origen`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(ThemeState.DEFAULT, {}, PointDetailsUiState(project, imported()), PointDetailsActions(), onBack = {})
            }
        }

        compose.onNodeWithTag(POINT_ORIGIN_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Origen: Importado").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-31 la ficha dice tambien el origen de los demas puntos`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(ThemeState.DEFAULT, {}, PointDetailsUiState(project, manual("M1")), PointDetailsActions(), onBack = {})
            }
        }

        compose.onNodeWithText("Origen: Manual").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S028 RF-31 el origen Importado tambien esta en ingles`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(ThemeState.DEFAULT, {}, PointDetailsUiState(project, imported()), PointDetailsActions(), onBack = {})
            }
        }

        compose.onNodeWithText("Origin: Imported").assertIsDisplayed()
    }

    @Test
    fun `S028 RF-31 un punto importado sale en el mapa como un punto cualquiera, sin el simbolo del replanteo`() {
        val onMap = MapPoints.from(listOf(imported("I1").summary(), imported("I2").summary().copy(hidden = true)))

        onMap.map { it.name } shouldBe listOf("I1")
        onMap.single().staked shouldBe false
    }
}
