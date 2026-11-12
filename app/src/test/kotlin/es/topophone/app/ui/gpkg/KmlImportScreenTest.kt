package es.topophone.app.ui.gpkg

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsUnit
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgImportSummary
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.GpkgLayerSummary
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.GeodeticDatum
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de importar un KML o KMZ: el título, los problemas, las capas, el aviso de datum, las altitudes y el resumen (S031/RF-1 a RF-19). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class KmlImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val columns = listOf(GpkgColumn("name", "TEXT"), GpkgColumn("description", "TEXT"))
    private val points = GpkgLayerInfo("Obra / Norte (puntos)", "geometry", "POINT", 4, 0, columns)
    private val lines = GpkgLayerInfo("Obra / Norte (líneas)", "geometry", "LINESTRING", 2, 0, columns)
    private val wgs84 = GpkgCrs.Supported(4258, CadSourceCrs.Geographic(GeodeticDatum.ETRS89), GpkgCrsUnit.DEGREE, wgs84 = true)

    private val config = GpkgImportUiState(
        stage = GpkgImportStage.CONFIG,
        format = ImportFormat.KML,
        fileName = "obra.kmz",
        projectName = "Calle Mayor 12",
        layers = listOf(GpkgLayerRow(points, crs = wgs84, nameField = "name"), GpkgLayerRow(lines, crs = wgs84, nameField = "name")),
        useElevations = false,
    )

    private fun show(state: GpkgImportUiState, actions: GpkgImportActions = GpkgImportActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {})
            }
        }
    }

    @Test
    fun `S031 RF-1 mientras se lee el fichero el titulo es el del KML`() {
        show(GpkgImportUiState(format = ImportFormat.KML, fileName = "obra.kmz"))

        compose.onNodeWithTag(GPKG_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithText("Importar KML/KMZ").assertIsDisplayed()
    }

    @Test
    fun `S031 RF-3 y RF-4 cada problema del fichero se explica`() {
        val texts = mapOf(
            GpkgFileProblem.EMPTY to "El fichero está vacío.",
            GpkgFileProblem.NOT_KML to "El fichero no es un KML ni un KMZ.",
            GpkgFileProblem.NO_PLACEMARKS to "El fichero no tiene ninguna marca con geometría que importar.",
            GpkgFileProblem.DAMAGED to "El KML o el KMZ está dañado y no se puede leer.",
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
                    GpkgImportUiState(
                        stage = GpkgImportStage.PROBLEM,
                        format = ImportFormat.KML,
                        fileName = "x.kml",
                        problem = shown.value,
                    ),
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
    }

    @Test
    fun `S031 RF-6 las capas salen con su ruta, su tipo y su numero de objetos, marcadas`() {
        show(config)

        compose.onNodeWithText("Obra / Norte (puntos)").assertIsDisplayed()
        compose.onNodeWithText("Obra / Norte (líneas)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(GPKG_LAYERS_TAG).assertIsDisplayed()
    }

    @Test
    fun `S031 RF-7 un KML avisa del datum una vez y no ofrece elegir el sistema de cada capa`() {
        show(config)

        compose.onNodeWithTag(KML_DATUM_TAG).assertIsDisplayed()
        compose.onNodeWithText(
            "Un KML va en WGS84 y se toma como el datum del proyecto, sin transformación: pueden diferir de 0,5 a 1 m.",
        ).assertIsDisplayed()
        compose.onNodeWithTag(gpkgCrsChangeTag(points.table)).assertDoesNotExist()
        compose.onNodeWithTag(gpkgCrsTag(points.table)).assertDoesNotExist()
    }

    @Test
    fun `S031 RF-13 con altitudes absolutas se pregunta por ellas, desmarcado, con el aviso de EGM96`() {
        show(config.copy(askElevations = true))

        compose.onNodeWithTag(GPKG_ELEVATIONS_TAG).performScrollTo().assertIsOff()
        compose.onNodeWithText("¿Usar las altitudes del fichero?").assertIsDisplayed()
        compose.onNodeWithTag(KML_ELEVATIONS_NOTICE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S031 RF-13 al marcar la pregunta de las altitudes avisa a quien escucha`() {
        var chosen: Boolean? = null
        show(config.copy(askElevations = true), GpkgImportActions(onUseElevations = { chosen = it }))

        compose.onNodeWithTag(GPKG_ELEVATIONS_TAG).performScrollTo().performClick()

        chosen shouldBe true
    }

    @Test
    fun `S031 RF-13 sin altitudes absolutas no se pregunta`() {
        show(config.copy(askElevations = false))

        compose.onNodeWithTag(GPKG_ELEVATIONS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(KML_ELEVATIONS_NOTICE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S031 RF-13 un GeoPackage sigue preguntando por las cotas de la capa con Si por defecto`() {
        show(config.copy(format = ImportFormat.GEOPACKAGE, useElevations = true))

        compose.onNodeWithTag(GPKG_ELEVATIONS_TAG).performScrollTo().assertIsOn()
        compose.onNodeWithText("¿Usar las cotas de la capa?").assertIsDisplayed()
    }

    @Test
    fun `S031 RF-18 el resumen cuenta los elementos ignorados del fichero y avisa del datum`() {
        val summary = GpkgImportSummary(
            layers = listOf(GpkgLayerSummary("Obra / Norte (puntos)", imported = 4, outsideArea = 0)),
            wgs84Layers = listOf("Obra / Norte (puntos)"),
            ignoredElements = mapOf(GpkgIgnoredReason.NETWORK_LINK to 2, GpkgIgnoredReason.MODEL to 1),
        )
        val outcome = GpkgImportOutcome("d", summary)
        show(GpkgImportUiState(stage = GpkgImportStage.RESULT, format = ImportFormat.KML, fileName = "obra.kmz", result = outcome))

        compose.onNodeWithTag(GPKG_RESULT_TAG).assertIsDisplayed()
        compose.onNodeWithText("No se han importado: Vínculo de red: 2, Modelo 3D: 1").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("WGS84 y el datum del proyecto se han tomado como iguales.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S031 RF-18 el resumen de un GeoPackage no habla de elementos ignorados del fichero`() {
        val summary = GpkgImportSummary(layers = listOf(GpkgLayerSummary("vertices", imported = 4, outsideArea = 0)))
        show(GpkgImportUiState(stage = GpkgImportStage.RESULT, format = ImportFormat.GEOPACKAGE, result = GpkgImportOutcome("d", summary)))

        compose.onNodeWithTag(KML_IGNORED_TAG, useUnmergedTree = true).assertDoesNotExist()
    }
}
