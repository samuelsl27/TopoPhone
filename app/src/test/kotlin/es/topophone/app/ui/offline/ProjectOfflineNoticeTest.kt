package es.topophone.app.ui.offline

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.OFFLINE_BANNER_TAG
import es.topophone.app.ui.projects.OFFLINE_MAX_DETAIL_TAG
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
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

/** El aviso de sin conexión del mapa y «Detalle máximo guardado» según lo que cubren las zonas sin conexión (S037/RF-20, RF-21; S009/RF-15). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectOfflineNoticeTest {

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

    private fun show(offline: Boolean, coverage: OfflineCoverageUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project, mapOffline = offline),
                    onBack = {},
                    map = fakeMap(FakeMapController()),
                    offlineCoverage = coverage,
                )
            }
        }
    }

    @Test
    fun `S009 RF-15 sin conexion y sin zona que cubra sigue el aviso de mapa incompleto`() {
        show(offline = true, coverage = OfflineCoverageUiState())

        compose.onNodeWithTag(OFFLINE_BANNER_TAG).assertIsDisplayed().assertTextContains("Sin conexión: el mapa puede estar incompleto.")
        compose.onNodeWithTag(OFFLINE_MAX_DETAIL_TAG).assertDoesNotExist()
    }

    @Test
    fun `S037 RF-20 sin conexion dentro de una fuente completa no sale el aviso`() {
        show(offline = true, coverage = OfflineCoverageUiState(covered = true))

        compose.onNodeWithTag(OFFLINE_BANNER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(OFFLINE_MAX_DETAIL_TAG).assertDoesNotExist()
    }

    @Test
    fun `S037 RF-21 sin conexion dentro de la zona y mas cerca de lo guardado sale Detalle maximo guardado`() {
        show(offline = true, coverage = OfflineCoverageUiState(covered = true, beyondDetail = true))

        compose.onNodeWithTag(OFFLINE_MAX_DETAIL_TAG).assertIsDisplayed().assertTextContains("Detalle máximo guardado")
        compose.onNodeWithTag(OFFLINE_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun `S037 RF-21 con conexion no sale ni el aviso ni el detalle maximo aunque se acerque mas de lo guardado`() {
        show(offline = false, coverage = OfflineCoverageUiState(covered = true, beyondDetail = true))

        compose.onNodeWithTag(OFFLINE_BANNER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(OFFLINE_MAX_DETAIL_TAG).assertDoesNotExist()
    }

    @Test
    fun `S037 RF-20 fuera de la zona el aviso vuelve aunque haya zonas`() {
        show(offline = true, coverage = OfflineCoverageUiState(covered = false, beyondDetail = false))

        compose.onNodeWithTag(OFFLINE_BANNER_TAG).assertIsDisplayed()
    }
}

/** El mismo aviso en inglés (S037/RNF-T7). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class ProjectOfflineNoticeEnglishTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S037 RNF-T7 the maximum stored detail text is in English`() {
        val project = Project(
            id = "p1",
            name = "Site",
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
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project, mapOffline = true),
                    onBack = {},
                    map = fakeMap(FakeMapController()),
                    offlineCoverage = OfflineCoverageUiState(covered = true, beyondDetail = true),
                )
            }
        }

        compose.onNodeWithTag(OFFLINE_MAX_DETAIL_TAG).assertTextContains("Maximum stored detail")
    }
}
