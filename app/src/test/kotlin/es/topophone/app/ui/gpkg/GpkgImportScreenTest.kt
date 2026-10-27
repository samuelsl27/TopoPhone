package es.topophone.app.ui.gpkg

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de importar un GeoPackage: el fichero, sus errores y la lista de capas marcables (S048/RF-2 a RF-7). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GpkgImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val columns = listOf(GpkgColumn("nombre", "TEXT"))
    private val points = GpkgLayerInfo("vertices", "geom", "POINT", 1_234_567, 25830, columns, hasSpatialIndex = true)
    private val polygons = GpkgLayerInfo("parcelas", "geom", "MULTIPOLYGON", 1, 25830, columns)
    private val mixed = GpkgLayerInfo("mixta", "geom", "GEOMETRY", 0, 4258, columns)

    private val config = GpkgImportUiState(
        stage = GpkgImportStage.CONFIG,
        fileName = "capas.gpkg",
        projectName = "Calle Mayor 12",
        layers = listOf(GpkgLayerRow(points), GpkgLayerRow(polygons, marked = false), GpkgLayerRow(mixed)),
    )

    private fun show(state: GpkgImportUiState, actions: GpkgImportActions = GpkgImportActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {})
            }
        }
    }

    @Test
    fun `S048 RF-1 mientras se lee el fichero lo dice`() {
        show(GpkgImportUiState(fileName = "capas.gpkg"))

        compose.onNodeWithTag(GPKG_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithText("Importar GeoPackage").assertIsDisplayed()
        compose.onNodeWithText("Leyendo el fichero…").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-3 y RF-4 cada problema del fichero se explica y se puede cerrar`() {
        val texts = mapOf(
            GpkgFileProblem.EMPTY to "El fichero está vacío.",
            GpkgFileProblem.NOT_GEOPACKAGE to "El fichero no es un GeoPackage.",
            GpkgFileProblem.DAMAGED to "El GeoPackage está dañado y no se puede leer.",
            GpkgFileProblem.NO_LAYERS to "El GeoPackage no tiene ninguna capa con geometría.",
            GpkgFileProblem.TOO_BIG to "El fichero supera el límite de 100 MB.",
            GpkgFileProblem.NO_SPACE to "No hay espacio libre en el móvil para leer el fichero.",
            GpkgFileProblem.UNREADABLE to "No se ha podido leer el fichero.",
        )

        val shown = mutableStateOf(GpkgFileProblem.EMPTY)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgImportScreen(
                    ThemeState.DEFAULT,
                    {},
                    GpkgImportUiState(stage = GpkgImportStage.PROBLEM, fileName = "x.gpkg", problem = shown.value),
                    GpkgImportActions(),
                    onBack = {},
                )
            }
        }

        for ((problem, text) in texts) {
            shown.value = problem
            compose.waitForIdle()
            compose.onNodeWithTag(GPKG_PROBLEM_TAG).assertIsDisplayed()
            compose.onNode(hasText(text)).assertIsDisplayed()
        }
        compose.onNodeWithText("Cerrar").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-5 la lista da el nombre, el tipo y el numero de objetos de cada capa`() {
        show(config)

        compose.onNodeWithText("vertices").assertIsDisplayed()
        compose.onNodeWithText("Puntos · 1.234.567 objetos").assertIsDisplayed()
        compose.onNodeWithText("Polígonos · 1 objeto").assertIsDisplayed()
        compose.onNodeWithText("Varios tipos · 0 objetos").assertIsDisplayed()
        compose.onNodeWithText("3 capas con geometría").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-5 las capas marcadas se ven marcadas y las demas no`() {
        show(config)

        compose.onNodeWithTag(gpkgLayerTag("vertices")).assertIsOn()
        compose.onNodeWithTag(gpkgLayerTag("parcelas")).assertIsOff()
        compose.onNodeWithTag(gpkgLayerTag("mixta")).assertIsOn()
    }

    @Test
    fun `S048 RF-5 tocar una capa la marca o la desmarca`() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        show(config, GpkgImportActions(onMarked = { table, marked -> calls += table to marked }))

        compose.onNodeWithTag(gpkgLayerTag("vertices")).performClick()
        compose.onNodeWithTag(gpkgLayerTag("parcelas")).performClick()

        calls shouldBe listOf("vertices" to false, "parcelas" to true)
    }

    @Test
    fun `S048 RF-5 las filas de las capas miden al menos 56 dp`() {
        show(config)

        compose.onNodeWithTag(gpkgLayerTag("vertices")).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S048 RF-6 avisa de las tablas que deja fuera`() {
        show(config.copy(leftOutTables = listOf("censo", "teselas")))

        compose.onNodeWithTag(GPKG_LEFT_OUT_TAG).assertIsDisplayed()
        compose.onNodeWithText("Se dejan fuera 2 tablas sin geometría o de teselas: censo, teselas.").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-6 con una sola tabla fuera lo dice en singular y sin ninguna no avisa`() {
        show(config.copy(leftOutTables = listOf("censo")))
        compose.onNodeWithText("Se deja fuera 1 tabla sin geometría o de teselas: censo.").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-6 sin tablas ajenas no hay aviso`() {
        show(config)

        compose.onAllNodes(hasText("fuera", substring = true)).assertCountEquals(0)
    }

    @Test
    fun `S048 RF-5 sin ninguna capa marcada lo dice y no deja importar`() {
        show(config.copy(layers = config.layers.map { it.copy(marked = false) }))

        compose.onNodeWithTag(GPKG_NO_LAYER_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(GPKG_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S048 RF-5 con alguna capa marcada el boton Importar se puede pulsar`() {
        var imported = 0
        show(config, GpkgImportActions(onImport = { imported++ }))

        compose.onNodeWithTag(GPKG_IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled().performClick()

        imported shouldBe 1
    }
}
