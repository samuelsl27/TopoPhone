package es.topophone.app.ui.surface

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.landxml.LandXmlSurfaceInfo
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.data.surface.SurfaceImportFailure
import es.topophone.data.surface.SurfaceImportSummary
import es.topophone.data.surface.SurfaceSummary
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de importar un LandXML: configuración, avisos, progreso y resumen (S042/RF-3 a RF-10, RF-13, RF-15). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfaceImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = SurfaceImportActions(
        onSelect = { calls += "select:$it" },
        onSource = { calls += "source:$it" },
        onUnit = { calls += "unit:${it.name}" },
        onConfirmFar = { calls += "far" },
        onConfirmDuplicate = { calls += "duplicate" },
        onImport = { calls += "import" },
        onCancel = { calls += "cancel" },
    )

    private fun info(index: Int, name: String, points: Int = 42, faces: Int = 60, hidden: Int = 2) =
        LandXmlSurfaceInfo(index, name, true, points, faces, hidden, 440_000.0, 440_150.5, 4_474_000.0, 4_474_260.25, 600.0, 608.7)

    private val ready = SurfaceImportUiState(
        stage = SurfaceImportStage.CONFIG,
        fileName = "obra.xml",
        projectName = "Calle Mayor 12",
        projectCrs = ProjectCrs.ETRS89_UTM30,
        surfaces = listOf(info(0, "terreno")),
        extent = SurfaceExtent(440_000.0, 440_150.5, 4_474_000.0, 4_474_260.25),
        distanceKm = 0.4,
    )

    private fun show(state: SurfaceImportUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SurfaceImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = { calls += "back" })
            }
        }
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun shown(text: String) = compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()

    @Test
    fun `S042 RF-3 y RF-4 cada motivo de rechazo tiene su texto`() {
        val cases = mapOf(
            SurfaceFileProblem.EMPTY to "El fichero está vacío.",
            SurfaceFileProblem.TOO_BIG to "pasa de 100 MB",
            SurfaceFileProblem.NOT_XML to "no es un XML válido",
            SurfaceFileProblem.NOT_LANDXML to "no es un LandXML",
            SurfaceFileProblem.NO_SURFACE to "ninguna superficie de triángulos",
            SurfaceFileProblem.UNREADABLE to "No se puede leer el fichero.",
        )
        val holder = mutableStateOf(SurfaceImportUiState(stage = SurfaceImportStage.PROBLEM))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SurfaceImportScreen(ThemeState.DEFAULT, {}, holder.value, actions, {}) }
        }
        cases.forEach { (problem, text) ->
            compose.runOnIdle { holder.value = SurfaceImportUiState(stage = SurfaceImportStage.PROBLEM, fileProblem = problem) }
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun `S042 RF-2 una version que no se lee se explica con la version`() {
        show(
            SurfaceImportUiState(stage = SurfaceImportStage.PROBLEM, fileProblem = SurfaceFileProblem.UNSUPPORTED_VERSION, version = "2.0"),
        )

        compose.onNodeWithText("versión 2.0", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1.0 a 1.2", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S042 RF-3 con un problema solo se puede cerrar`() {
        show(SurfaceImportUiState(stage = SurfaceImportStage.PROBLEM, fileName = "obra.xml", fileProblem = SurfaceFileProblem.EMPTY))

        compose.onNodeWithText("obra.xml").assertIsDisplayed()
        compose.onNodeWithText("Cerrar").performClick()
        calls shouldBe listOf("back")
    }

    @Test
    fun `S042 RF-5 con una sola superficie no se pide elegir y con varias se elige una`() {
        val holder = mutableStateOf(ready)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SurfaceImportScreen(ThemeState.DEFAULT, {}, holder.value, actions, {}) }
        }
        compose.onNodeWithText("Superficie del fichero").assertDoesNotExist()
        val two = listOf(info(0, "terreno"), info(1, "rasante", points = 4, faces = 2, hidden = 0))
        compose.runOnIdle { holder.value = ready.copy(surfaces = two) }
        shown("Superficie del fichero")
        shown("terreno · 42 puntos · 58 triángulos")
        compose.onNodeWithTag(surfaceChoiceTag(0)).assertIsSelected()
        tap(surfaceChoiceTag(1))

        calls shouldBe listOf("select:1")
        compose.runOnIdle { holder.value = holder.value.copy(selected = 1) }
        compose.onNodeWithTag(surfaceChoiceTag(1)).assertIsSelected()
    }

    @Test
    fun `S042 RF-6 se elige el sistema del proyecto u otro UTM`() {
        show(ready)

        compose.onNodeWithTag(SURFACE_CRS_PROJECT_TAG).assertIsSelected()
        tap(SURFACE_CRS_OTHER_TAG)

        calls.single().startsWith("source:Utm") shouldBe true
    }

    @Test
    fun `S042 RF-6 con otro sistema elegido se ofrecen los husos`() {
        show(ready.copy(source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)))

        compose.onNodeWithTag(SURFACE_CRS_OTHER_TAG).assertIsSelected()
        tap(surfaceSourceTag(ProjectCrs.ETRS89_UTM30))

        calls shouldBe listOf("source:Utm(datum=ETRS89, zone=30)")
    }

    @Test
    fun `S042 RF-7 se elige la unidad de las coordenadas y las cotas`() {
        show(ready.copy(unit = LinearUnit.INTERNATIONAL_FOOT))

        compose.onNodeWithTag(surfaceUnitTag(LinearUnit.INTERNATIONAL_FOOT)).assertIsSelected()
        tap(surfaceUnitTag(LinearUnit.US_SURVEY_FOOT))

        calls shouldBe listOf("unit:US_SURVEY_FOOT")
    }

    @Test
    fun `S042 RF-8 la extension sale con las cotas y la distancia al proyecto`() {
        show(ready)

        compose.onNodeWithTag(SURFACE_EXTENT_TAG).performScrollTo().assertIsDisplayed()
        shown("Este: de 440.000,0 a 440.150,5 m")
        shown("Norte: de 4.474.000,0 a 4.474.260,2 m")
        shown("Cota: de 600,0 a 608,7 m")
        shown("A 0,4 km del centro del proyecto")
    }

    @Test
    fun `S042 RF-8 una superficie que no cabe en el sistema lo dice y no deja importar`() {
        show(ready.copy(extent = null, outsideArea = true, distanceKm = null))

        compose.onNodeWithTag(SURFACE_OUTSIDE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SURFACE_IMPORT_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S042 RF-9 una superficie lejana avisa y hasta confirmar no deja importar`() {
        show(ready.copy(distanceKm = 321.5, farWarning = true))

        compose.onNodeWithTag(SURFACE_FAR_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SURFACE_IMPORT_BUTTON_TAG).assertIsNotEnabled()
        tap(SURFACE_FAR_CONFIRM_TAG)

        calls shouldBe listOf("far")
    }

    @Test
    fun `S042 RF-15 un nombre de fichero repetido avisa y pide confirmar`() {
        show(ready.copy(duplicateName = true))

        compose.onNodeWithTag(SURFACE_DUPLICATE_TAG).performScrollTo().assertIsDisplayed()
        tap(SURFACE_DUPLICATE_CONFIRM_TAG)

        calls shouldBe listOf("duplicate")
    }

    @Test
    fun `S042 RF-10 mientras se explora no se puede importar y se ve el progreso`() {
        show(ready.copy(scanning = true, bytesRead = 50, totalBytes = 100))

        compose.onNodeWithTag(SURFACE_SCANNING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SURFACE_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SURFACE_IMPORT_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S042 RF-10 con todo listo se importa`() {
        show(ready)

        compose.onNodeWithTag(SURFACE_IMPORT_BUTTON_TAG).assertIsEnabled()
        tap(SURFACE_IMPORT_BUTTON_TAG)

        calls shouldBe listOf("import")
    }

    @Test
    fun `S042 RF-10 durante la importacion hay progreso y se puede cancelar`() {
        show(ready.copy(stage = SurfaceImportStage.IMPORTING, importBytes = 30, totalBytes = 100))

        compose.onNodeWithTag(SURFACE_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SURFACE_CANCEL_TAG).performClick()

        calls shouldBe listOf("cancel")
    }

    @Test
    fun `S042 RF-11 una importacion cancelada o fallida lo dice y no se ha guardado nada`() {
        val holder = mutableStateOf(ready.copy(cancelled = true))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { SurfaceImportScreen(ThemeState.DEFAULT, {}, holder.value, actions, {}) }
        }
        shown("Importación cancelada: no se ha guardado nada.")

        val cases = mapOf(
            SurfaceImportFailure.WRITE_ERROR to "No se ha podido guardar la superficie",
            SurfaceImportFailure.READ_ERROR to "No se ha podido leer la superficie",
            SurfaceImportFailure.NO_SURFACE to "ninguna superficie que importar",
            SurfaceImportFailure.OUTSIDE_CRS_AREA to "fuera del ámbito del sistema",
            SurfaceImportFailure.OUTSIDE_ED50_GRID to "fuera de la rejilla de ED50",
        )
        cases.forEach { (failure, text) ->
            compose.runOnIdle { holder.value = ready.copy(failure = failure) }
            compose.onNodeWithTag(SURFACE_FAILURE_TAG).performScrollTo().assertTextContains(text, substring = true)
        }
    }

    @Test
    fun `S042 RF-13 el resumen da los recuentos la extension las cotas y si queda activa`() {
        val summary = SurfaceSummary(SurfaceExtent(440_000.0, 440_150.5, 4_474_000.0, 4_474_260.25), 600.0, 608.7, 42, 58, 2)
        show(
            ready.copy(
                stage = SurfaceImportStage.RESULT,
                result = SurfaceImportOutcome("s1", SurfaceImportSummary("terreno", summary, droppedFaces = 3, activated = true)),
            ),
        )

        shown("Superficie importada")
        shown("terreno")
        shown("Puntos: 42")
        shown("Triángulos: 58")
        shown("Huecos (triángulos invisibles): 2")
        shown("Cota: de 600,0 a 608,7 m")
        compose.onNodeWithText("Se han descartado 3 caras", substring = true).assertIsDisplayed()
        shown("Es la superficie activa del proyecto.")
    }

    @Test
    fun `S042 RF-17 una superficie que no queda activa lo dice`() {
        val summary = SurfaceSummary(SurfaceExtent(0.0, 1.0, 0.0, 1.0), 1.0, 2.0, 3, 1, 0)
        show(
            ready.copy(
                stage = SurfaceImportStage.RESULT,
                result = SurfaceImportOutcome("s2", SurfaceImportSummary("rasante", summary, droppedFaces = 0, activated = false)),
            ),
        )

        shown("No es la superficie activa")
        compose.onNodeWithText("Se han descartado", substring = true).assertDoesNotExist()
    }

    @Test
    fun `S042 RNF-T8 los controles tienen zonas tactiles de 56 dp`() {
        show(ready.copy(surfaces = listOf(info(0, "terreno"), info(1, "rasante"))))

        compose.onNodeWithTag(SURFACE_IMPORT_BUTTON_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(surfaceChoiceTag(1)).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(SURFACE_CRS_OTHER_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(surfaceUnitTag(LinearUnit.METRE)).assertHeightIsAtLeast(56.dp)
    }
}
