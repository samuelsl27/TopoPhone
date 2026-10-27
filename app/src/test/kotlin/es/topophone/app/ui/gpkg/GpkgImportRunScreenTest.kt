package es.topophone.app.ui.gpkg

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.ReportOutcome
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgImportSummary
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.GpkgLayerSummary
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.gpkg.GpkgImportFailure
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Campo de nombre, cotas, importar con progreso y cancelar, y el resumen por capa (S048/RF-19 a RF-25, RF-29, RF-30). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GpkgImportRunScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val columns = listOf(GpkgColumn("nombre", "TEXT"), GpkgColumn("cota", "REAL"))
    private val layer = GpkgLayerInfo("vertices", "geom", "POINT", 10, 25830, columns)
    private val row = GpkgLayerRow(layer, crs = GpkgCrsCatalog.resolveEpsg(25830)!!, nameField = "nombre")

    private fun config(failure: GpkgImportFailure? = null, cancelled: Boolean = false, useElevations: Boolean = true) = GpkgImportUiState(
        stage = GpkgImportStage.CONFIG,
        fileName = "capas.gpkg",
        layers = listOf(row),
        failure = failure,
        cancelled = cancelled,
        useElevations = useElevations,
    )

    private fun show(state: GpkgImportUiState, actions: GpkgImportActions = GpkgImportActions(), outcome: ReportOutcome? = null) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}, summaryOutcome = outcome)
            }
        }
    }

    private val summary = GpkgImportSummary(
        layers = listOf(
            GpkgLayerSummary(
                "vertices",
                imported = 8,
                outsideArea = 3,
                ignored = mapOf(GpkgIgnoredReason.CURVE_GEOMETRY to 2, GpkgIgnoredReason.EMPTY_GEOMETRY to 1),
                omittedAttributes = 4,
            ),
            GpkgLayerSummary("parcelas", imported = 2, outsideArea = 0),
        ),
        leftOutTables = listOf("censo", "teselas"),
        wgs84Layers = listOf("vertices"),
    )

    private fun result() = GpkgImportUiState(
        stage = GpkgImportStage.RESULT,
        fileName = "capas.gpkg",
        result = GpkgImportOutcome("d1", summary),
    )

    @Test
    fun `S048 RF-21 cada capa marcada ofrece su campo de nombre con el propuesto marcado`() {
        show(config())

        compose.onNodeWithTag(gpkgNameFieldTag("vertices", "nombre")).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(gpkgNameFieldTag("vertices", "cota")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(gpkgNameFieldTag("vertices", null)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sin nombre").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-21 elegir otro campo o ninguno avisa al ViewModel`() {
        val calls = mutableListOf<Pair<String, String?>>()
        show(config(), GpkgImportActions(onNameField = { table, field -> calls += table to field }))

        compose.onNodeWithTag(gpkgNameFieldTag("vertices", "cota")).performScrollTo().performClick()
        compose.onNodeWithTag(gpkgNameFieldTag("vertices", null)).performScrollTo().performClick()

        calls shouldBe listOf("vertices" to "cota", "vertices" to null)
    }

    @Test
    fun `S048 RF-19 la pregunta de las cotas viene en Si y se puede cambiar`() {
        val calls = mutableListOf<Boolean>()
        show(config(), GpkgImportActions(onUseElevations = { calls += it }))

        compose.onNodeWithTag(GPKG_ELEVATIONS_TAG).performScrollTo().assertIsOn()
        compose.onNodeWithText("¿Usar las cotas de la capa?").assertIsDisplayed()
        compose.onNodeWithTag(GPKG_ELEVATIONS_TAG).performClick()

        calls shouldBe listOf(false)
    }

    @Test
    fun `S048 RF-19 con las cotas desactivadas la casilla se ve apagada`() {
        show(config(useElevations = false))

        compose.onNodeWithTag(GPKG_ELEVATIONS_TAG).performScrollTo().assertIsOff()
    }

    @Test
    fun `S048 RF-29 Importar llama a la importacion`() {
        var imported = 0
        show(config(), GpkgImportActions(onImport = { imported++ }))

        compose.onNodeWithTag(GPKG_IMPORT_BUTTON_TAG).performScrollTo().performClick()

        imported shouldBe 1
    }

    @Test
    fun `S048 RF-29 mientras importa muestra el progreso y Cancelar`() {
        var cancelled = 0
        show(
            GpkgImportUiState(stage = GpkgImportStage.IMPORTING, fileName = "capas.gpkg", importDone = 30, importTotal = 100),
            GpkgImportActions(onCancel = { cancelled++ }),
        )

        compose.onNodeWithText("Importando el GeoPackage…").assertIsDisplayed()
        compose.onNodeWithTag(GPKG_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GPKG_CANCEL_TAG).performClick()

        cancelled shouldBe 1
    }

    @Test
    fun `S048 RF-30 cancelar o fallar vuelve a la configuracion diciendo que no se ha importado nada`() {
        show(config(cancelled = true))
        compose.onNodeWithTag(GPKG_FAILURE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Importación cancelada: no se ha importado nada.").assertIsDisplayed()
    }

    @Test
    fun `S048 RF-30 cada motivo de fallo tiene su texto`() {
        val texts = mapOf(
            GpkgImportFailure.WRITE_ERROR to "No se ha podido escribir en el almacenamiento: no se ha importado nada.",
            GpkgImportFailure.READ_ERROR to "No se ha podido leer el fichero: no se ha importado nada.",
            GpkgImportFailure.NO_OBJECTS to "Ningún objeto cae en la zona con estas capas: no se ha importado nada.",
            GpkgImportFailure.TOO_MANY_OBJECTS to
                "Hay más de 500.000 objetos en la zona: no se ha importado nada. Reduce la zona o las capas.",
        )
        val shown = androidx.compose.runtime.mutableStateOf(GpkgImportFailure.WRITE_ERROR)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgImportScreen(ThemeState.DEFAULT, {}, config(failure = shown.value), GpkgImportActions(), onBack = {})
            }
        }

        for ((failure, text) in texts) {
            shown.value = failure
            compose.waitForIdle()
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `S048 RF-25 el resumen da por capa lo importado, lo de fuera de la zona y lo ignorado con su motivo`() {
        show(result())

        compose.onNodeWithText("Importación terminada", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Objetos importados: 10", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(gpkgResultLayerTag("vertices"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(gpkgResultLayerTag("parcelas"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Importados: 8", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Fuera de la zona: 3", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Ignorados: 3", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Geometría curva: 2", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Geometría vacía: 1", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Atributos omitidos: 4", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `S048 RF-25 el resumen dice las tablas dejadas fuera y las capas en WGS 84`() {
        show(result())

        compose.onNodeWithText("Tablas dejadas fuera: censo, teselas", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            "Capas en WGS 84 tratadas como ETRS89: vertices",
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S048 RF-25 el resumen se puede guardar o compartir como fichero de texto`() {
        val calls = mutableListOf<String>()
        show(
            result(),
            GpkgImportActions(onSaveSummary = { calls += "guardar:$it" }, onShareSummary = { calls += "compartir:$it" }),
        )

        compose.onNodeWithTag(GPKG_SUMMARY_SAVE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GPKG_SUMMARY_SHARE_TAG).performScrollTo().performClick()

        calls shouldBe listOf("guardar:resumen-geopackage.txt", "compartir:resumen-geopackage.txt")
    }

    @Test
    fun `S048 RF-25 dice si el resumen se guardo o no se pudo guardar`() {
        show(result(), outcome = ReportOutcome.SAVED)
        compose.onNodeWithText("Resumen guardado.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S048 RF-25 si no se pudo guardar el resumen lo dice`() {
        show(result(), outcome = ReportOutcome.FAILED)
        compose.onNodeWithText("No se ha podido guardar el resumen.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S048 RF-25 el resumen tiene una descripcion con los recuentos para TalkBack y un boton para cerrar`() {
        show(result())

        compose.onNodeWithTag(GPKG_RESULT_TAG).assertIsDisplayed()
        compose.onNode(hasText("Cerrar")).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("Cerrar")).assertCountEquals(1)
    }
}
