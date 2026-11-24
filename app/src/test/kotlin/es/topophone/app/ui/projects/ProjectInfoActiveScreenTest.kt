package es.topophone.app.ui.projects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectDefaults
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «Datos del proyecto» con la sección de lo activo: con terreno o localización, sin nada y al activar algo con la pantalla abierta (S059/RF-18, RF-21, RF-22). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-h800dp")
class ProjectInfoActiveScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val project: Project = ProjectInfoFixtures.project()

    private var state by mutableStateOf(ProjectInfoUiState(project = project, pointCount = 3))

    private fun show() = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectInfoScreen(ThemeState.DEFAULT, {}, state, onBack = {}) }
    }

    @Test
    fun `S059 RF-18 con terreno y localizacion activos la pantalla los muestra`() {
        state =
            state.copy(active = ActiveItems.of(ActiveInputs(terrainFactor = 1.000_2, localizationActive = true, localizationRmsM = 0.01)))
        show()

        compose.onNodeWithTag(activeRowTag(ActiveKind.TERRAIN)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(activeRowTag(ActiveKind.LOCALIZATION)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S059 RF-21 sin nada activo la pantalla no muestra la seccion`() {
        show()

        compose.onAllNodesWithTag(ACTIVE_SECTION_TAG).assertCountEquals(0)
    }

    @Test
    fun `S059 RF-22 al activar y desactivar un elemento la seccion cambia sin reabrir la pantalla`() {
        show()
        compose.onAllNodesWithTag(ACTIVE_SECTION_TAG).assertCountEquals(0)

        state = state.copy(active = ActiveItems.of(ActiveInputs(baseShiftName = "Vértice 4")))
        compose.waitForIdle()
        compose.onNodeWithTag(activeRowTag(ActiveKind.BASE_SHIFT)).performScrollTo().assertIsDisplayed()

        state = state.copy(active = emptyList())
        compose.waitForIdle()
        compose.onAllNodesWithTag(ACTIVE_SECTION_TAG).assertCountEquals(0)
    }
}

internal object ProjectInfoFixtures {
    fun project(): Project {
        val draft = ProjectDefaults().toDraft().copy(name = "Muralla")
        return Project(
            id = "p1",
            name = draft.name,
            author = "",
            description = "",
            crs = draft.crs,
            linearUnit = draft.linearUnit,
            angularUnit = draft.angularUnit,
            correctionsDatum = draft.correctionsDatum,
            folderId = null,
            createdAtMs = 1_000,
            modifiedAtMs = 1_000,
            openedAtMs = null,
            deletedAtMs = null,
        )
    }
}
