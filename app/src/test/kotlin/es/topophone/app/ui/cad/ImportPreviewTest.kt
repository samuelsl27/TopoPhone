package es.topophone.app.ui.cad

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.crs.CrsLibraryHost
import es.topophone.app.ui.crs.CrsLibraryRequest
import es.topophone.app.ui.crs.LibrarySourceUi
import es.topophone.app.ui.crs.SOURCE_LIBRARY_CONFIRM_TAG
import es.topophone.app.ui.crs.SOURCE_LIBRARY_GRIDS_TAG
import es.topophone.app.ui.crs.SOURCE_LIBRARY_NO_SHIFT_TAG
import es.topophone.app.ui.crs.SOURCE_LIBRARY_TRANSFORMATION_TAG
import es.topophone.app.ui.crs.SOURCE_LIBRARY_WARNING_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GridEntry
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * La vista previa de una importación con un SC de origen de la biblioteca, en la pantalla de importar un DXF: la transformación con su precisión,
 * el aviso que se confirma y las rejillas que faltan con «Descargar»; la importación no se puede lanzar hasta que está todo listo (S057/RF-8, RF-9,
 * RF-10). Interfaz en es-ES.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ImportPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = CadImportActions(
        onConfirmLibrary = { calls += "confirm:$it" },
        onRefreshLibrary = { calls += "refresh" },
        onImport = { calls += "import" },
    )

    private class FakeHost : CrsLibraryHost {
        var downloads: List<GridEntry> = emptyList()

        @Composable
        override fun Flow(
            themeState: ThemeState,
            onToggleSun: () -> Unit,
            request: CrsLibraryRequest,
            onChosen: (LibraryCrs) -> Unit,
            onDismiss: () -> Unit,
        ) = Unit

        @Composable
        override fun MissingGrids(crs: ProjectCrs) = Unit

        @Composable
        override fun GridDownloads(entries: List<GridEntry>, onInstalled: () -> Unit) {
            downloads = entries
            Column { entries.forEach { Text(it.name, modifier = Modifier.testTag("fake_grid_${it.name}")) } }
        }
    }

    private val host = FakeHost()

    private fun library(epsg: Int, at: GeoPoint): LibraryCrs {
        val result = CrsCatalog.bundled.build(epsg, CorrectionsDatum.ETRS89, at)
        val definition = when (result) {
            is CrsBuildResult.Ready -> result.definition
            is CrsBuildResult.NeedsGrids -> result.definition
            is CrsBuildResult.Unavailable -> error("EPSG:$epsg")
        }
        return LibraryCrs(definition, CorrectionsDatum.ETRS89)
    }

    private fun stateWith(ui: LibrarySourceUi) = CadImportUiState(
        stage = CadImportStage.CONFIG,
        fileName = "plano.dxf",
        projectName = "Obra",
        elementCount = 10,
        extent = CadExtent(440_000.0, 440_150.0, 4_474_000.0, 4_474_260.0),
        distanceKm = 0.4,
        options = CadImportOptions(source = ui.source),
        library = ui,
    )

    private fun show(state: CadImportUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CadImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}, libraryHost = host)
            }
        }
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun `S057 RF-8 la vista previa dice la transformacion con su precision`() {
        val at = GeoPoint(44.43, 26.1)
        show(stateWith(LibrarySourceUi.of(library(3844, at), at)))

        compose.onNodeWithTag(SOURCE_LIBRARY_TRANSFORMATION_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Precisión declarada: 3,0 m", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S057 RF-9 con mas de 1 m el aviso es destacado y la importacion espera a la confirmacion`() {
        val at = GeoPoint(44.43, 26.1)
        show(stateWith(LibrarySourceUi.of(library(3844, at), at)))

        compose.onNodeWithTag(SOURCE_LIBRARY_WARNING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CAD_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
        tap(SOURCE_LIBRARY_CONFIRM_TAG)

        calls shouldBe listOf("confirm:true")
    }

    @Test
    fun `S057 RF-9 confirmado el aviso se puede importar`() {
        val at = GeoPoint(44.43, 26.1)
        show(stateWith(LibrarySourceUi.of(library(3844, at), at).confirm(true)))

        compose.onNodeWithTag(CAD_IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled()
        tap(CAD_IMPORT_BUTTON_TAG)

        calls shouldBe listOf("import")
    }

    @Test
    fun `S057 RF-8 con el mismo datum no hay aviso ni confirmacion y se puede importar`() {
        val at = GeoPoint(48.0, 2.0)
        show(stateWith(LibrarySourceUi.of(library(2154, at), at)))

        compose.onNodeWithTag(SOURCE_LIBRARY_NO_SHIFT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SOURCE_LIBRARY_WARNING_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CAD_IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled()
    }

    @Test
    fun `S057 RF-10 con una rejilla que falta se dice y se ofrece descargarla, y no se importa aunque se confirme`() {
        val at = GeoPoint(50.0, 8.5)
        show(stateWith(LibrarySourceUi.of(library(31467, at), at).confirm(true)))

        compose.onNodeWithTag(SOURCE_LIBRARY_GRIDS_TAG).performScrollTo().assertIsDisplayed()
        host.downloads.isNotEmpty() shouldBe true
        compose.onNodeWithTag("fake_grid_${host.downloads.first().name}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CAD_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }
}
