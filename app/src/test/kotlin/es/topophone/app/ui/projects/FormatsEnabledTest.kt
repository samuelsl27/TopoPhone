package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.options.OptionGroup
import es.topophone.app.ui.options.ProjectOptions
import es.topophone.app.ui.options.groupButtonTag
import es.topophone.app.ui.options.optionTag
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Con un proyecto en un SC de la biblioteca los menús vuelven a ofrecer todos los formatos de intercambio y desaparece el aviso de S034/RF-29
 * (S057/RF-28). Interfaz en es-ES.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class FormatsEnabledTest {

    @get:Rule
    val compose = createComposeRule()

    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun text(id: Int): String = resources.getString(id)

    private fun project(crs: ProjectCrs) = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = crs,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
        heightSystem = HeightSystem.ELLIPSOIDAL,
    )

    private val lambert93 = LibraryCrs(
        (CrsCatalog.bundled.build(2154, CorrectionsDatum.ETRS89, GeoPoint(48.0, 2.0)) as CrsBuildResult.Ready).definition,
    )

    private val exports =
        listOf(R.string.gpkg_export_title, R.string.dxf_export_title, R.string.shp_export_title, R.string.kml_export_title)
    private val imports = listOf(
        R.string.cad_import_title,
        R.string.surface_import_title,
        R.string.raster_import_title,
        R.string.gpkg_import_title,
        R.string.shp_import_title,
        R.string.kml_import_title,
        R.string.alignment_import_title,
    )

    @Test
    fun `S057 RF-28 el menu de un proyecto de la biblioteca ofrece todos los formatos`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(folders = listOf(ProjectFolder("f1", "Fincas")), projects = listOf(project(lambert93))),
                    ProjectListActions(),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()

        compose.onNodeWithText(text(R.string.export_csv)).assertIsDisplayed()
        (exports + imports).forEach { compose.onNodeWithText(text(it)).assertExists() }
    }

    @Test
    fun `S057 RF-28 el menu de un proyecto de Espana sigue igual`() {
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

        (exports + imports).forEach { compose.onNodeWithText(text(it)).assertExists() }
    }

    private fun showObjects() = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ObjectListScreen(ThemeState.DEFAULT, {}, ObjectListUiState(loaded = true), ObjectListActions(), onBack = {})
        }
    }

    @Test
    fun `S057 RF-28 el menu de Exportar de objetos ofrece los formatos de intercambio`() {
        showObjects()

        compose.onNodeWithTag(groupButtonTag(OptionGroup.EXPORT)).performClick()

        ProjectOptions.of(OptionGroup.EXPORT).forEach { compose.onNodeWithTag(optionTag(it)).assertExists() }
    }

    @Test
    fun `S057 RF-28 el menu de Importar de objetos ofrece los formatos de intercambio`() {
        showObjects()

        compose.onNodeWithTag(groupButtonTag(OptionGroup.IMPORT)).performClick()

        ProjectOptions.of(OptionGroup.IMPORT).forEach { compose.onNodeWithTag(optionTag(it)).assertExists() }
    }
}
