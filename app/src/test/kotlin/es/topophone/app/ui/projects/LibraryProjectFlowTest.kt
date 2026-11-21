package es.topophone.app.ui.projects

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.crs.CrsLibraryHost
import es.topophone.app.ui.crs.CrsLibraryRequest
import es.topophone.app.ui.points.OBJECTS_MENU_TAG
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectFolder
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPosition
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Un proyecto con un SC de la biblioteca en su vista, en la lista y en el menú de objetos (S034/RF-16, RF-18, RF-27, RF-29). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LibraryProjectFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val library: LibraryCrs = when (val result = CrsCatalog.bundled.build(2154, CorrectionsDatum.ETRS89, GeoPoint(48.85, 2.35))) {
        is CrsBuildResult.Ready -> LibraryCrs(result.definition, CorrectionsDatum.ETRS89, "fr_ign_RAF20.tif")
        is CrsBuildResult.NeedsGrids -> LibraryCrs(result.definition, CorrectionsDatum.ETRS89, "fr_ign_RAF20.tif")
        else -> error("$result")
    }

    private fun project(crs: ProjectCrs = library, height: HeightSystem = HeightSystem.ORTHOMETRIC) = Project(
        id = "p1", name = "Paris", author = "", description = "", crs = crs, linearUnit = LinearUnit.METRE, angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89, folderId = null, createdAtMs = 1_000, modifiedAtMs = 1_000, openedAtMs = null,
        deletedAtMs = null, heightSystem = height,
    )

    private class FakeHost : CrsLibraryHost {
        @Composable
        override fun Flow(
            themeState: ThemeState,
            onToggleSun: () -> Unit,
            request: CrsLibraryRequest,
            onChosen: (LibraryCrs) -> Unit,
            onDismiss: () -> Unit,
        ) = Unit

        @Composable
        override fun MissingGrids(crs: ProjectCrs) {
            Text("ofrece descargar", modifier = Modifier.testTag("fake_missing"))
        }
    }

    private fun showView(position: ProjectPosition?, host: CrsLibraryHost? = FakeHost()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(project(), position), onBack = {}, library = host)
            }
        }
    }

    @Test
    fun `S034 RF-27 la vista muestra Este y Norte y, al desplegar, los factores de la proyeccion de la biblioteca`() {
        showView(
            ProjectPosition(
                easting = 652_000.123,
                northing = 6_862_000.456,
                height = 35.0,
                scaleFactor = 0.999_912_345,
                convergenceRad = 0.001_234,
                combinedFactor = 0.999_90,
            ),
        )

        compose.onNodeWithTag(POSITION_EASTING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(POSITION_NORTHING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SCALE_FACTOR_TAG).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.map_panel_more)).performScrollTo().performClick()

        compose.onNodeWithTag(SCALE_FACTOR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CONVERGENCE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COMBINED_FACTOR_TAG).assertIsDisplayed()
    }

    @Test
    fun `S034 RF-18 sin una rejilla del SC no hay coordenadas, se dice cual falta y se ofrece descargarla`() {
        showView(ProjectPosition(unavailable = setOf(PositionUnavailable.MISSING_GRID), missingGrid = "uk_os_OSTN15_NTv2_OSGBtoETRS.tif"))

        compose.onNodeWithTag(POSITION_MISSING_GRID_TAG).assertIsDisplayed()
        compose.onNode(hasText("uk_os_OSTN15_NTv2_OSGBtoETRS.tif", substring = true)).assertIsDisplayed()
        compose.onNodeWithTag("fake_missing").assertIsDisplayed()
    }

    @Test
    fun `S034 RF-16 sin el geoide las coordenadas salen y la altura dice que falta el geoide`() {
        showView(
            ProjectPosition(
                easting = 652_000.123,
                northing = 6_862_000.456,
                unavailable = setOf(PositionUnavailable.MISSING_GRID),
                missingGrid = "fr_ign_RAF20.tif",
            ),
        )

        compose.onNodeWithTag(POSITION_EASTING_TAG).assertIsDisplayed()
        compose.onNode(hasText("fr_ign_RAF20.tif", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `S034 RF-26 un punto fuera de la rejilla de transformacion del SC no tiene coordenadas y lo dice`() {
        showView(ProjectPosition(unavailable = setOf(PositionUnavailable.OUTSIDE_DATUM_GRID)))

        compose.onNodeWithText(text(R.string.project_outside_datum_grid)).assertIsDisplayed()
    }

    @Test
    fun `S034 RF-29 el menu de un proyecto con un SC de la biblioteca no ofrece los formatos y dice por que`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(folders = listOf(ProjectFolder("f1", "Fincas")), projects = listOf(project())),
                    ProjectListActions(),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()

        compose.onNodeWithText(text(R.string.export_csv)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.import_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.formats_unavailable_library)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dxf_export_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.cad_import_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.gpkg_import_title)).assertDoesNotExist()
    }

    @Test
    fun `S034 RF-29 el menu de un proyecto de Espana sigue ofreciendo todos los formatos`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(
                        folders = listOf(ProjectFolder("f1", "Fincas")),
                        projects = listOf(project(ProjectCrs.ETRS89_UTM30)),
                    ),
                    ProjectListActions(),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()

        compose.onNodeWithText(text(R.string.dxf_export_title)).assertExists()
        compose.onNodeWithText(text(R.string.cad_import_title)).assertExists()
        compose.onNodeWithText(text(R.string.formats_unavailable_library)).assertDoesNotExist()
    }

    @Test
    fun `S034 RF-29 el menu de objetos esconde los formatos cuando el SC es de la biblioteca`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true, formatsAvailable = false),
                    ObjectListActions(),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()

        compose.onNodeWithText(text(R.string.export_csv)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.formats_unavailable_library)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.kml_import_title)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.shp_export_title)).assertDoesNotExist()
    }
}
