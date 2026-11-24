package es.topophone.app.ui.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.ALIGNMENTS_SHEET_TAG
import es.topophone.app.ui.surface.SURFACES_SHEET_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A dónde lleva cada fila de «Activo en este proyecto» (S059/RF-20). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-h800dp")
class ActiveItemsNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private val navigation = SurveyNavigation(
        onEditProjectTerrain = { calls += "terrain:$it" },
        onLocalization = { calls += "localization:$it" },
        onBaseShift = { calls += "baseshift:$it" },
        onOpenProjectPanel = { id, panel -> calls += "panel:$id:${panel.name}" },
    )

    @Test
    fun `S059 RF-20 cada fila lleva a la pantalla donde se gestiona hoy el elemento`() {
        val destinations = activeDestinations("p1", navigation)

        ActiveKind.entries.forEach { destinations.getValue(it).invoke() }

        calls shouldBe listOf(
            "terrain:p1",
            "localization:p1",
            "baseshift:p1",
            "panel:p1:SURFACES",
            "panel:p1:ALIGNMENTS",
            "panel:p1:LAYERS",
        )
    }

    @Test
    fun `S059 RF-20 la vista del proyecto abre la hoja de ejes que se le pide al llegar`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(ProjectInfoFixtures.project(), null),
                    onBack = {},
                    openPanel = ProjectPanel.ALIGNMENTS,
                )
            }
        }

        compose.onNodeWithTag(ALIGNMENTS_SHEET_TAG).assertIsDisplayed()
    }

    @Test
    fun `S059 RF-20 la vista del proyecto abre la hoja de superficies que se le pide al llegar`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(ProjectInfoFixtures.project(), null),
                    onBack = {},
                    openPanel = ProjectPanel.SURFACES,
                )
            }
        }

        compose.onNodeWithTag(SURFACES_SHEET_TAG).assertIsDisplayed()
    }

    @Test
    fun `S059 RF-20 el formulario abre con la seccion de terreno a la vista`() {
        val project = ProjectInfoFixtures.project()
        val draft = es.topophone.core.model.project.ProjectDefaults().toDraft().copy(name = project.name)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectFormScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectFormUiState(draft = draft, loaded = true, editing = true),
                    ProjectFormActions(),
                    showTerrain = true,
                )
            }
        }

        compose.onNodeWithTag(terrainOptionTag(false)).assertIsDisplayed()
    }
}
