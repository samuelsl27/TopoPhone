package es.topophone.app.ui.offline

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.LAYERS_BUTTON_TAG
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoBox
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

/** «Mapas sin conexión» en la hoja «Capas» del mapa y lo que ve la hoja del encuadre (S037/RF-1, RF-2). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectOfflineEntryTest {

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

    private val seen = GeoBox(40.4160, -3.7050, 40.4180, -3.7020)
    private var visibleProvider: (() -> GeoBox?)? = null

    private fun show(controller: FakeMapController) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(controller),
                    offlineSheet = { visible, _ ->
                        visibleProvider = visible
                        Text("Hoja de mapas sin conexión")
                    },
                )
            }
        }
        compose.onNodeWithTag(LAYERS_BUTTON_TAG).performClick()
    }

    @Test
    fun `S037 RF-1 la hoja Capas ofrece Mapas sin conexion y la abre`() {
        show(FakeMapController())

        compose.onNodeWithTag(OFFLINE_ENTRY_TAG).performScrollTo().assertHeightIsAtLeast(56.dp).assertIsDisplayed().performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Hoja de mapas sin conexión").assertIsDisplayed()
    }

    @Test
    fun `S037 RF-2 la hoja recibe el encuadre que se ve en el mapa`() {
        val controller = FakeMapController().apply { visible = seen }
        show(controller)

        compose.onNodeWithTag(OFFLINE_ENTRY_TAG).performScrollTo().performClick()
        compose.waitForIdle()

        visibleProvider!!() shouldBe seen
    }
}
