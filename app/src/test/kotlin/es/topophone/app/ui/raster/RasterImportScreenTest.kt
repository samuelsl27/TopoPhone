package es.topophone.app.ui.raster

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.raster.RasterCrsDeclaration
import es.topophone.core.formats.raster.RasterFormat
import es.topophone.core.formats.raster.RasterHeader
import es.topophone.core.formats.raster.RasterReadStats
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.RasterValueType
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.data.surface.RasterImportFailure
import es.topophone.data.surface.RasterImportSummary
import es.topophone.data.surface.RasterSummary
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de importar un ráster: configuración, avisos, progreso y resumen (S045/RF-3 a RF-16). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class RasterImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = RasterImportActions(
        onSource = { calls += "source:$it" },
        onUnit = { calls += "unit:${it.name}" },
        onHeightSystem = { calls += "height:${it.name}" },
        onConfirmFar = { calls += "far" },
        onConfirmDuplicate = { calls += "duplicate" },
        onImport = { calls += "import" },
        onCancel = { calls += "cancel" },
    )

    private fun header(bands: Int = 1, geographic: Boolean = false) = RasterHeader(
        format = RasterFormat.GEOTIFF, columns = 40, rows = 36, originX = 440_005.0, originY = 4_474_005.0, stepX = 10.0, stepY = 10.0,
        epsg = 25830, geographic = geographic, verticalUnit = null, bandCount = bands, noDataValue = -9999.0,
        valueType = RasterValueType.FLOAT32,
    )

    private val ready = RasterImportUiState(
        stage = RasterImportStage.CONFIG,
        fileName = "mdt.tif",
        projectName = "Calle Mayor 12",
        projectCrs = ProjectCrs.ETRS89_UTM30,
        header = header(),
        declaration = RasterCrsDeclaration.Supported(25830, CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30), wgs84 = false),
        scanning = false,
        stats = RasterReadStats(1436, 4, 600.0, 647.0),
        extent = SurfaceExtent(440_005.0, 440_395.0, 4_473_655.0, 4_474_005.0),
        distanceKm = 0.4,
    )

    private fun show(state: RasterImportUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                RasterImportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = { calls += "back" })
            }
        }
    }

    private fun tap(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun shown(text: String) = compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()

    @Test
    fun `S045 RF-3 a RF-5 cada motivo de rechazo tiene su texto`() {
        val cases = mapOf(
            RasterImportFailure.EMPTY to "El fichero está vacío.",
            RasterImportFailure.NOT_A_RASTER to "un GeoTIFF ni un ASCII Grid",
            RasterImportFailure.NOT_GEOREFERENCED to "no está georreferenciado",
            RasterImportFailure.BIGTIFF to "BigTIFF",
            RasterImportFailure.UNSUPPORTED_COMPRESSION to "compresión que no se lee",
            RasterImportFailure.ROTATED to "girado, inclinado",
            RasterImportFailure.COLOUR to "de color",
            RasterImportFailure.UNSUPPORTED_DATA_TYPE to "enteros de 16 o 32 bits",
            RasterImportFailure.TOO_LARGE to "250 MB",
            RasterImportFailure.TOO_WIDE to "65.536 celdas",
            RasterImportFailure.NO_DATA_CELLS to "ninguna celda con dato",
            RasterImportFailure.READ_ERROR to "No se puede leer el fichero.",
        )
        val holder = mutableStateOf(RasterImportUiState(stage = RasterImportStage.PROBLEM, problem = RasterImportFailure.EMPTY))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                RasterImportScreen(ThemeState.DEFAULT, {}, holder.value, actions, onBack = {})
            }
        }
        for ((reason, text) in cases) {
            holder.value = RasterImportUiState(stage = RasterImportStage.PROBLEM, fileName = "x.tif", problem = reason)
            compose.waitForIdle()
            compose.onNodeWithTag(RASTER_PROBLEM_TAG).assertIsDisplayed()
            compose.onNodeWithText(text, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun `S045 RF-13 mientras se copia el fichero se ve el progreso de la copia`() {
        show(
            RasterImportUiState(
                stage = RasterImportStage.CONFIG,
                fileName = "mdt.tif",
                scanning = true,
                bytesCopied = 500,
                totalBytes = 1000,
            ),
        )

        compose.onNodeWithTag(RASTER_SCANNING_TAG).assertIsDisplayed()
        shown("Copiando el fichero")
        compose.onNodeWithTag(RASTER_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S045 RF-11 mientras se mide el raster se ve el progreso de la medicion`() {
        show(ready.copy(scanning = true, stats = null, measuredRows = 10))

        shown("Midiendo el ráster")
        compose.onNodeWithTag(RASTER_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S045 RF-6 un GeoTIFF con varias bandas dice cuantas tiene`() {
        show(ready.copy(header = header(bands = 3)))

        compose.onNodeWithTag(RASTER_BANDS_TAG).performScrollTo().assertIsDisplayed()
        shown("3 bandas: se usa la primera")
    }

    @Test
    fun `S045 RF-8 la pantalla dice lo que declara el fichero y deja elegir el sistema`() {
        show(ready)

        compose.onNodeWithTag(RASTER_CRS_NOTE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RASTER_CRS_PROJECT_TAG).performScrollTo().assertIsSelected()

        tap(RASTER_CRS_OTHER_TAG)

        calls.last().startsWith("source:") shouldBe true
    }

    @Test
    fun `S045 RF-8 sin sistema declarado o con un codigo que la app no ofrece lo avisa`() {
        show(ready.copy(declaration = RasterCrsDeclaration.Undeclared))
        shown("no declara sistema")
    }

    @Test
    fun `S045 RF-8 un codigo EPSG que la app no ofrece pide elegir otro`() {
        show(ready.copy(declaration = RasterCrsDeclaration.Unavailable(3857), source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)))

        shown("código EPSG 3857")
        compose.onNodeWithTag(rasterSourceTag(ProjectCrs.ETRS89_UTM29)).performScrollTo().assertIsSelected()
        tap(rasterSourceTag(ProjectCrs.ETRS89_UTM31))
        calls.last() shouldBe "source:Utm(datum=ETRS89, zone=31)"
    }

    @Test
    fun `S045 RF-9 y RF-10 se elige la unidad y el sistema de alturas`() {
        show(ready.copy(unit = LinearUnit.METRE, heightSystem = HeightSystem.ORTHOMETRIC))

        compose.onNodeWithTag(rasterUnitTag(LinearUnit.METRE)).performScrollTo().assertIsSelected()
        tap(rasterUnitTag(LinearUnit.INTERNATIONAL_FOOT))
        tap(rasterHeightTag(HeightSystem.ELLIPSOIDAL))

        calls shouldBe listOf("unit:INTERNATIONAL_FOOT", "height:ELLIPSOIDAL")
    }

    @Test
    fun `S045 RF-10 si el sistema de alturas difiere del proyecto lo dice y sin geoide avisa`() {
        show(ready.copy(heightSystem = HeightSystem.ELLIPSOIDAL, projectHeightSystem = HeightSystem.ORTHOMETRIC, geoidShiftM = 50.0))
        shown("se convierten al sistema de alturas del proyecto")
    }

    @Test
    fun `S045 RF-10 sin geoide en la zona el aviso es de error`() {
        show(ready.copy(heightSystem = HeightSystem.ELLIPSOIDAL, projectHeightSystem = HeightSystem.ORTHOMETRIC, geoidShiftM = null))

        shown("No hay geoide en la zona")
        shown("no se puede dar sin geoide")
    }

    @Test
    fun `S045 RF-11 la pantalla da el tamano de celda las celdas con su porcentaje sin dato y las cotas`() {
        show(ready)

        compose.onNodeWithTag(RASTER_DATA_TAG).performScrollTo().assertIsDisplayed()
        shown("40 × 36 celdas de 10,0 m")
        shown("1.440 celdas · 0,3 % sin dato")
        shown("Cota: de 600,0 a 647,0 m")
        compose.onNodeWithTag(RASTER_EXTENT_TAG).performScrollTo().assertIsDisplayed()
        shown("Este: de 440.005,0 a 440.395,0 m")
        shown("A 0,4 km del centro del proyecto")
    }

    @Test
    fun `S045 RF-11 un raster en geograficas da la celda en grados`() {
        show(ready.copy(header = header(geographic = true).copy(stepX = 0.0001, stepY = 0.0001)))

        shown("celdas de 0,0001°")
    }

    @Test
    fun `S045 RF-3 un raster que no cabe en el sistema lo dice y no deja importar`() {
        show(ready.copy(extent = null, outsideArea = true))

        compose.onNodeWithTag(RASTER_OUTSIDE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RASTER_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S045 RF-12 un raster lejos pide confirmar antes de importar`() {
        show(ready.copy(distanceKm = 320.0, farWarning = true))

        compose.onNodeWithTag(RASTER_FAR_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RASTER_IMPORT_BUTTON_TAG).performScrollTo().assertIsNotEnabled()

        tap(RASTER_FAR_CONFIRM_TAG)

        calls shouldBe listOf("far")
    }

    @Test
    fun `S045 RF-15 un nombre repetido pide confirmar y Importar actua cuando se puede`() {
        show(ready.copy(duplicateName = true, duplicateConfirmed = true))

        compose.onNodeWithTag(RASTER_DUPLICATE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(RASTER_IMPORT_BUTTON_TAG).performScrollTo().assertIsEnabled()

        tap(RASTER_IMPORT_BUTTON_TAG)

        calls shouldBe listOf("import")
    }

    @Test
    fun `S045 RF-13 durante la importacion se ve el progreso por filas y Cancelar`() {
        show(ready.copy(stage = RasterImportStage.IMPORTING, importRows = 128, importTotalRows = 1_000))

        compose.onNodeWithTag(RASTER_PROGRESS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Fila 128 de 1.000").assertIsDisplayed()
        compose.onNodeWithTag(RASTER_CANCEL_TAG).assertHeightIsAtLeast(56.dp).performClick()

        calls shouldBe listOf("cancel")
    }

    @Test
    fun `S045 RF-13 si falla o se cancela dice que no se ha guardado nada`() {
        show(ready.copy(failure = RasterImportFailure.WRITE_ERROR))
        compose.onNodeWithTag(RASTER_FAILURE_TAG).assertIsDisplayed()
        shown("No se ha podido guardar el ráster")
    }

    @Test
    fun `S045 RF-13 una importacion cancelada dice que no se ha guardado nada`() {
        show(ready.copy(cancelled = true))

        shown("Importación cancelada: no se ha guardado nada")
    }

    @Test
    fun `S045 RF-16 el resumen da las celdas con y sin dato el tamano de celda la extension y las cotas`() {
        val summary = RasterSummary(SurfaceExtent(440_005.0, 440_395.0, 4_473_655.0, 4_474_005.0), 600.0, 647.0, 1_436, 4)
        show(
            ready.copy(
                stage = RasterImportStage.RESULT,
                result = RasterImportOutcome("r1", RasterImportSummary("mdt", summary, 40, 36, 10.0, 10.0, activated = true)),
            ),
        )

        compose.onNodeWithTag(RASTER_RESULT_TAG).assertIsDisplayed()
        shown("Celdas con dato: 1.436")
        shown("Celdas sin dato: 4")
        shown("Tamaño de celda: 10,0 m")
        shown("Cota: de 600,0 a 647,0 m")
        shown("Es la superficie activa del proyecto")
    }

    @Test
    fun `S045 RF-17 el resumen de un raster que no es el activo lo dice`() {
        val summary = RasterSummary(SurfaceExtent(0.0, 1.0, 0.0, 1.0), 600.0, 647.0, 1_436, 4)
        show(
            ready.copy(
                stage = RasterImportStage.RESULT,
                result = RasterImportOutcome("r1", RasterImportSummary("mdt", summary, 40, 36, 10.0, 10.0, activated = false)),
            ),
        )

        shown("No es la superficie activa")
    }

    @Test
    fun `RNF-T8 los botones y las opciones tienen zonas tactiles de 56 dp`() {
        show(ready)

        for (tag in listOf(
            RASTER_IMPORT_BUTTON_TAG,
            RASTER_CRS_PROJECT_TAG,
            RASTER_CRS_OTHER_TAG,
            rasterUnitTag(LinearUnit.METRE),
            rasterHeightTag(HeightSystem.ORTHOMETRIC),
        )) {
            compose.onNodeWithTag(tag).performScrollTo().assertHeightIsAtLeast(56.dp)
        }
    }
}
