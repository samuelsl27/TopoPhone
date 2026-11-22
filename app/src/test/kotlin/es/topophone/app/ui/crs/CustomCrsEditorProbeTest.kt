package es.topophone.app.ui.crs

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GridEntry
import es.topophone.core.geodesy.catalog.GridKind
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Las coordenadas de prueba del editor en los dos sentidos y las rejillas sin instalar con su descarga (S035/RF-7, RF-8; RNF). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CustomCrsEditorProbeTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeCustomCrsRepository()
    private lateinit var viewModel: CustomCrsEditorViewModel

    /** Un anfitrión de pega que apunta las rejillas que se ofrecen descargar. */
    private class FakeHost : CrsLibraryHost {
        var offered: List<GridEntry> = emptyList()
        var installed: (() -> Unit)? = null

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
            offered = entries
            installed = onInstalled
            Column { entries.forEach { Text(it.name, modifier = Modifier.testTag("fake_grid_${it.name}")) } }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = CustomCrsEditorViewModel(repository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun show(host: FakeHost? = null) {
        viewModel.open(CustomCrsEditorRequest())
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                val state by viewModel.state.collectAsState()
                CustomCrsEditorScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    CustomCrsEditorActions(onChange = viewModel::update, onProbe = viewModel::setProbe, onSave = viewModel::save),
                    library = host,
                    onGridsInstalled = viewModel::gridsInstalled,
                )
            }
        }
    }

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    /** Un UTM 30N con GRS 1980 y sin cambio de datum, escrito a mano. */
    private fun utm30() {
        type(CUSTOM_NAME_TAG, "UTM propio")
        type(CUSTOM_SEMI_MAJOR_TAG, "6378137")
        type(CUSTOM_INVERSE_FLATTENING_TAG, "298.257222101")
        tap(CUSTOM_METHOD_TAG)
        tap(customMethodTag("tm"))
        type(customParamTag("latitudeOfOriginDeg"), "0")
        type(customParamTag("longitudeOfOriginDeg"), "-3")
        type(customParamTag("scaleFactor"), "0.9996")
        type(customParamTag("falseEasting"), "500000")
        type(customParamTag("falseNorthing"), "0")
    }

    private fun waitForText(tag: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithTagCount(tag) > 0 }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun textOf(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") {
            it.text
        }

    @Test
    fun `S035 RF-7 una posicion de Madrid da el Este y el Norte de pyproj en el SC`() {
        show()
        utm30()

        type(CUSTOM_PROBE_LAT_TAG, "40.4")
        type(CUSTOM_PROBE_LON_TAG, "-3.7")
        waitForText(CUSTOM_PROBE_TO_TAG)

        // pyproj: EPSG:4258 a EPSG:25830 para (40.4, -3.7).
        textOf(CUSTOM_PROBE_TO_TAG) shouldContain "Este 440598.0789"
        textOf(CUSTOM_PROBE_TO_TAG) shouldContain "Norte 4472390.0310"
        textOf(CUSTOM_PROBE_TO_TAG) shouldContain "cierre 0.000 mm"
    }

    @Test
    fun `S035 RF-7 unas coordenadas del SC dan su posicion en el datum de las correcciones`() {
        show()
        utm30()

        type(CUSTOM_PROBE_FIRST_TAG, "440598.0788513809")
        type(CUSTOM_PROBE_SECOND_TAG, "4472390.031014141")
        waitForText(CUSTOM_PROBE_FROM_TAG)

        textOf(CUSTOM_PROBE_FROM_TAG) shouldContain "latitud 40.400000000"
        textOf(CUSTOM_PROBE_FROM_TAG) shouldContain "longitud -3.700000000"
    }

    @Test
    fun `S035 RF-7 con un SC no valido no hay resultado de prueba`() {
        show()
        type(CUSTOM_PROBE_LAT_TAG, "40.4")
        type(CUSTOM_PROBE_LON_TAG, "-3.7")

        compose.onAllNodesWithTagCount(CUSTOM_PROBE_TO_TAG) shouldBe 0
    }

    @Test
    fun `S035 RF-8 con una rejilla sin instalar se ofrece su descarga y la prueba dice cual falta`() {
        val remote = CrsCatalog.bundled.grids.values.filter { it.kind == GridKind.HORIZONTAL && !it.bundled }.minBy { it.name }
        val host = FakeHost()
        show(host)
        utm30()

        tap(CUSTOM_TRANSFORMATION_TAG)
        tap(customTransformationTag(TransformationKind.GRID))
        tap(CUSTOM_GRID_TAG)
        tap(customGridTag(remote.name))
        type(CUSTOM_PROBE_LAT_TAG, "40.4")
        type(CUSTOM_PROBE_LON_TAG, "-3.7")
        waitForText(CUSTOM_PROBE_TO_TAG)

        host.offered.map { it.name } shouldBe listOf(remote.name)
        compose.onNodeWithTag("fake_grid_${remote.name}").performScrollTo()
        textOf(CUSTOM_PROBE_TO_TAG) shouldContain "Falta la rejilla ${remote.name}"
    }

    @Test
    fun `S035 RF-8 sin rejillas que falten no se enseña la descarga`() {
        val host = FakeHost()
        show(host)
        utm30()

        compose.onAllNodesWithTagCount(CUSTOM_MISSING_GRIDS_TAG) shouldBe 0
        host.offered shouldBe emptyList()
    }

    @Test
    fun `S035 RNF cada cambio de campo se valida en menos de 100 ms`() {
        show()
        utm30()
        viewModel.update { it.copy(name = "calentar") }

        val millis = measureTimeMillis { repeat(20) { i -> viewModel.update { it.copy(name = "Obra $i") } } } / 20

        millis shouldBeLessThan 100L
    }
}
