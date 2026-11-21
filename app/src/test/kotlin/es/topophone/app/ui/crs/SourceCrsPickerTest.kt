package es.topophone.app.ui.crs

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GridEntry
import es.topophone.core.geodesy.catalog.build
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
 * La parte «otro de la biblioteca» del SC de origen de una importación: abrir el selector, el SC elegido, el cambio de datum con su precisión, el
 * aviso que se confirma y las rejillas que faltan (S057/RF-1, RF-8, RF-9, RF-10). Interfaz en es-ES.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SourceCrsPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val catalog = CrsCatalog.bundled
    private val centre = GeoPoint(44.43, 26.1)

    private fun library(epsg: Int, at: GeoPoint = centre): LibraryCrs {
        val result = catalog.build(epsg, CorrectionsDatum.ETRS89, at)
        val definition = when (result) {
            is CrsBuildResult.Ready -> result.definition
            is CrsBuildResult.NeedsGrids -> result.definition
            is CrsBuildResult.Unavailable -> error("EPSG:$epsg")
        }
        return LibraryCrs(definition, CorrectionsDatum.ETRS89)
    }

    /** Un anfitrión de pega: el flujo es un texto con el SC que «elige» al pulsar y las descargas, un texto por rejilla. */
    private class FakeHost(private val chosen: LibraryCrs) : CrsLibraryHost {
        val requests = mutableListOf<CrsLibraryRequest>()
        var chooser: (() -> Unit)? = null
        var downloads: List<GridEntry> = emptyList()
        var installed: (() -> Unit)? = null

        @Composable
        override fun Flow(
            themeState: ThemeState,
            onToggleSun: () -> Unit,
            request: CrsLibraryRequest,
            onChosen: (LibraryCrs) -> Unit,
            onDismiss: () -> Unit,
        ) {
            requests += request
            chooser = { onChosen(chosen) }
            Text("flujo abierto", modifier = Modifier.testTag("fake_flow_open"))
        }

        @Composable
        override fun MissingGrids(crs: ProjectCrs) = Unit

        @Composable
        override fun GridDownloads(entries: List<GridEntry>, onInstalled: () -> Unit) {
            downloads = entries
            installed = onInstalled
            Column { entries.forEach { Text(it.name, modifier = Modifier.testTag("fake_grid_${it.name}")) } }
        }
    }

    private class State(var chosen: LibrarySourceUi?, var selected: Boolean = chosen != null)

    private fun show(host: FakeHost, start: State = State(null)): State {
        var current by mutableStateOf(start.chosen)
        var selected by mutableStateOf(start.selected)
        val live = State(current, selected)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                Column {
                    LibrarySourceSection(
                        chosen = current,
                        selected = selected,
                        request = LibrarySourceRequest(CorrectionsDatum.ETRS89, centre),
                        themeState = ThemeState.DEFAULT,
                        onToggleSun = {},
                        host = host,
                        onChosen = {
                            current = LibrarySourceUi.of(it, centre)
                            selected = true
                            live.chosen = current
                        },
                        onSelect = { selected = true },
                        onConfirm = {
                            current = current?.confirm(it)
                            live.chosen = current
                        },
                        onRefresh = { live.chosen = current?.refreshed(centre) },
                    )
                }
            }
        }
        return live
    }

    @Test
    fun `S057 RF-1 sin SC elegido solo se ofrece abrir el selector de la biblioteca`() {
        show(FakeHost(library(3844)))

        compose.onNodeWithTag(SOURCE_LIBRARY_CHOICE_TAG).assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag(SOURCE_LIBRARY_CHOSEN_TAG).assertDoesNotExist()
        compose.onNodeWithTag("fake_flow_open").assertDoesNotExist()
    }

    @Test
    fun `S057 RF-8 al pulsar se abre el selector con el datum de las correcciones y el centro del proyecto`() {
        val host = FakeHost(library(3844))
        show(host)

        compose.onNodeWithTag(SOURCE_LIBRARY_CHOICE_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("fake_flow_open").assertIsDisplayed()
        host.requests.first() shouldBe CrsLibraryRequest(CorrectionsDatum.ETRS89, centre, null)
    }

    @Test
    fun `S057 RF-2 el SC elegido queda seleccionado con su nombre, su cambio de datum y su precision`() {
        val host = FakeHost(library(3844))
        show(host)
        compose.onNodeWithTag(SOURCE_LIBRARY_CHOICE_TAG).performClick()
        compose.waitForIdle()

        compose.runOnIdle { host.chooser!!.invoke() }
        compose.waitForIdle()

        compose.onNodeWithTag(SOURCE_LIBRARY_CHOICE_TAG).assertIsSelected()
        compose.onNodeWithTag(SOURCE_LIBRARY_CHOSEN_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SOURCE_LIBRARY_TRANSFORMATION_TAG).assertIsDisplayed()
        compose.onNodeWithTag("fake_flow_open").assertDoesNotExist() // el selector se cierra al elegir
    }

    @Test
    fun `S057 RF-9 con un cambio de datum de mas de 1 m se avisa y se pide confirmar`() {
        val ui = LibrarySourceUi.of(library(3844), centre)
        val live = show(FakeHost(library(3844)), State(ui))

        compose.onNodeWithTag(SOURCE_LIBRARY_WARNING_TAG).assertIsDisplayed()
        ui.ready shouldBe false
        compose.onNodeWithTag(SOURCE_LIBRARY_CONFIRM_TAG).performClick()
        compose.waitForIdle()

        live.chosen!!.confirmed shouldBe true
        live.chosen!!.ready shouldBe true
    }

    @Test
    fun `S057 RF-9 con el mismo datum no hay aviso ni confirmacion`() {
        val ui = LibrarySourceUi.of(library(2154, GeoPoint(48.0, 2.0)), GeoPoint(48.0, 2.0))
        show(FakeHost(library(3844)), State(ui))

        compose.onNodeWithTag(SOURCE_LIBRARY_NO_SHIFT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SOURCE_LIBRARY_WARNING_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SOURCE_LIBRARY_CONFIRM_TAG).assertDoesNotExist()
        ui.ready shouldBe true
    }

    @Test
    fun `S057 RF-10 una rejilla que falta se dice y se ofrece descargarla, y sin ella no esta listo`() {
        val germany = GeoPoint(50.0, 8.5)
        val ui = LibrarySourceUi.of(library(31467, germany), germany).confirm(true)
        val host = FakeHost(library(3844))
        show(host, State(ui))

        compose.onNodeWithTag(SOURCE_LIBRARY_GRIDS_TAG).assertIsDisplayed()
        host.downloads.isNotEmpty() shouldBe true
        compose.onNodeWithTag("fake_grid_${host.downloads.first().name}").assertIsDisplayed()
        ui.ready shouldBe false
    }
}
