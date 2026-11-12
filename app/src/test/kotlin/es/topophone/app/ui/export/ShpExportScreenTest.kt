package es.topophone.app.ui.export

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.shp.export.ShpCoordinates
import es.topophone.core.formats.shp.export.ShpExportSummary
import es.topophone.core.formats.shp.export.ShpRenamedColumn
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.HeightSystem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla «Exportar Shapefile»: los objetos, el sistema, el nombre, guardar y compartir, los mensajes y el resumen (S055/RF-12 a RF-22; RNF-T7, RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ShpExportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun state(
        count: Int = 3,
        lines: Int = 0,
        polygons: Int = 0,
        coordinates: ShpCoordinates = ShpCoordinates.PROJECTED,
        canChoose: Boolean = true,
        epsg: Int = 25830,
        summary: ShpExportSummary? = null,
        result: ExportResult? = null,
    ) = ShpExportUiState(
        loaded = true,
        projectName = "Calle Mayor 12",
        pointCount = count,
        lineCount = lines,
        polygonCount = polygons,
        coordinates = coordinates,
        canChooseCoordinates = canChoose,
        epsg = epsg,
        projectEpsg = if (canChoose) 25830 else epsg,
        geographicEpsg = if (canChoose) 4258 else epsg,
        summary = summary,
        result = result,
    )

    private fun show(state: ShpExportUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ShpExportScreen(
                ThemeState.DEFAULT,
                {},
                state,
                ShpExportActions(
                    onCoordinates = { calls += "crs:${it.name}" },
                    onSave = { calls += "save:$it" },
                    onShare = { calls += "share:$it" },
                ),
                onBack = {},
            )
        }
    }

    private fun summary(
        points: Int = 3,
        lines: Int = 0,
        polygons: Int = 0,
        withoutPosition: Int = 0,
        withoutHeight: Int = 0,
        truncated: Int = 0,
        renamed: List<ShpRenamedColumn> = emptyList(),
        prjMissing: Boolean = false,
        heightSystem: HeightSystem = HeightSystem.ORTHOMETRIC,
    ) = ShpExportSummary(
        points = points,
        lines = lines,
        polygons = polygons,
        pointsWithoutPosition = withoutPosition,
        verticesWithoutHeight = withoutHeight,
        truncatedTexts = truncated,
        renamedAttributes = renamed,
        prjMissing = prjMissing,
        epsg = 25830,
        heightSystem = heightSystem,
    )

    @Test
    fun `S055 RF-1 dice cuantos puntos se exportan en singular y en plural`() {
        show(state(count = 1))
        compose.onNodeWithTag(SHP_COUNT_TAG).assertTextContains("Se exportará 1 punto")
    }

    @Test
    fun `S055 RF-1 con varios puntos lo dice en plural`() {
        show(state(count = 3))
        compose.onNodeWithTag(SHP_COUNT_TAG).assertTextContains("Se exportarán 3 puntos")
    }

    @Test
    fun `S055 RF-3 dice cuantas lineas y poligonos van y no dice nada si no hay`() {
        show(state(lines = 1, polygons = 2))
        compose.onNodeWithTag(SHP_LINES_TAG).assertTextContains("1 línea · 2 polígonos")
    }

    @Test
    fun `S055 RF-3 sin lineas ni poligonos no aparece la linea de recuento`() {
        show(state())
        compose.onAllNodesWithTag(SHP_LINES_TAG).assertCountEquals(0)
    }

    @Test
    fun `S055 RF-19 sin puntos lo dice y no deja guardar ni compartir`() {
        show(state(count = 0))

        compose.onNodeWithTag(SHP_COUNT_TAG).assertTextContains("No hay puntos que exportar.")
        compose.onNodeWithTag(SHP_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(SHP_SHARE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S055 RF-12 y RF-13 en un proyecto UTM se elige entre el sistema del proyecto y las geograficas y se ve el codigo`() {
        show(state())

        compose.onNodeWithTag(SHP_CRS_PROJECT_TAG).assertTextContains("EPSG:25830", substring = true)
        compose.onNodeWithTag(SHP_CRS_PROJECT_TAG).assertIsSelected()
        compose.onNodeWithTag(SHP_CRS_GEOGRAPHIC_TAG).performClick()

        calls shouldContainExactly listOf("crs:GEOGRAPHIC")
    }

    @Test
    fun `S055 RF-13 con las geograficas elegidas aparece elegida la opcion y su codigo`() {
        show(state(coordinates = ShpCoordinates.GEOGRAPHIC, epsg = 4258))

        compose.onNodeWithTag(SHP_CRS_GEOGRAPHIC_TAG).assertIsSelected()
        compose.onNodeWithTag(SHP_CRS_GEOGRAPHIC_TAG).assertTextContains("EPSG:4258", substring = true)
    }

    @Test
    fun `S055 RF-12 un proyecto en geograficas no ofrece elegir sistema y dice cual es`() {
        show(state(coordinates = ShpCoordinates.GEOGRAPHIC, canChoose = false, epsg = 4258))

        compose.onAllNodesWithTag(SHP_CRS_PROJECT_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(SHP_CRS_GEOGRAPHIC_TAG).assertCountEquals(0)
        compose.onNodeWithTag(SHP_CRS_FIXED_TAG).assertTextContains("EPSG:4258", substring = true)
    }

    @Test
    fun `S055 RF-17 muestra el nombre propuesto con el proyecto, shapefile y la fecha`() {
        show(state())

        compose.onNodeWithTag(SHP_FILE_NAME_TAG).assertTextContains("Calle Mayor 12_shapefile_", substring = true)
        compose.onNodeWithTag(SHP_FILE_NAME_TAG).assertTextContains(".zip", substring = true)
    }

    @Test
    fun `S055 RF-18 guardar y compartir llevan el nombre propuesto a las acciones`() {
        show(state())

        compose.onNodeWithTag(SHP_SAVE_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(SHP_SHARE_TAG).performClick()

        calls.size shouldBe 2
        calls[0] shouldStartWith "save:Calle Mayor 12_shapefile_"
        calls[1] shouldStartWith "share:Calle Mayor 12_shapefile_"
    }

    @Test
    fun `S055 RNF-T8 los botones y las opciones miden al menos 56 dp`() {
        show(state())

        compose.onNodeWithTag(SHP_SAVE_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(SHP_SHARE_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(SHP_CRS_PROJECT_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(SHP_CRS_GEOGRAPHIC_TAG).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S055 RF-20 si no se ha podido exportar lo dice y si se guardo tambien`() {
        show(state(result = ExportResult.FAILED))
        compose.onNodeWithTag(SHP_RESULT_TAG).assertTextContains("No se ha podido exportar el fichero.")
    }

    @Test
    fun `S055 RF-18 un fichero guardado lo dice`() {
        show(state(result = ExportResult.SAVED))
        compose.onNodeWithTag(SHP_RESULT_TAG).assertTextContains("Fichero guardado.")
    }

    @Test
    fun `S055 RF-22 el resumen dice los puntos, las lineas, los poligonos y el sistema`() {
        show(state(result = ExportResult.SAVED, summary = summary(points = 3, lines = 1, polygons = 2)))

        compose.onNodeWithTag(SHP_SUMMARY_TAG).assertTextContains("3 puntos", substring = true)
        compose.onNodeWithTag(SHP_SUMMARY_TAG).assertTextContains("EPSG:25830", substring = true)
        compose.onNodeWithTag(SHP_SUMMARY_LINES_TAG).assertTextContains("1 línea · 2 polígonos")
    }

    @Test
    fun `S055 RF-22 un resumen sin avisos solo lleva el sistema de alturas`() {
        show(state(result = ExportResult.SAVED, summary = summary()))

        compose.onAllNodesWithTag(SHP_NO_POSITION_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(SHP_NO_HEIGHT_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(SHP_TRUNCATED_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(SHP_RENAMED_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(SHP_NO_PRJ_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(SHP_SUMMARY_LINES_TAG).assertCountEquals(0)
        compose.onNodeWithTag(SHP_HEIGHT_SYSTEM_TAG).assertTextContains("La Z es", substring = true)
    }

    @Test
    fun `S055 RF-14b el resumen avisa de los puntos sin posicion`() {
        show(state(result = ExportResult.SAVED, summary = summary(withoutPosition = 1)))
        compose.onNodeWithTag(SHP_NO_POSITION_TAG).assertTextContains("1 punto se ha escrito como objeto sin geometría", substring = true)

        compose.onAllNodesWithTag(SHP_NO_HEIGHT_TAG).assertCountEquals(0)
    }

    @Test
    fun `S055 RF-14 el resumen avisa de los vertices sin altura con la Z igual a 0`() {
        show(state(result = ExportResult.SAVED, summary = summary(withoutHeight = 2)))

        compose.onNodeWithTag(SHP_NO_HEIGHT_TAG).assertTextContains("2 vértices sin altura", substring = true)
        compose.onNodeWithTag(SHP_NO_HEIGHT_TAG).assertTextContains("Z = 0", substring = true)
    }

    @Test
    fun `S055 RF-10 el resumen avisa de los textos recortados`() {
        show(state(result = ExportResult.SAVED, summary = summary(truncated = 1)))

        compose.onNodeWithTag(SHP_TRUNCATED_TAG).assertTextContains("1 texto se ha recortado a 254 bytes", substring = true)
    }

    @Test
    fun `S055 RF-9 el resumen lista los atributos renombrados con su nombre nuevo`() {
        val renamed = listOf(ShpRenamedColumn("Ancho vía", "ANCHO_VIA"), ShpRenamedColumn("cota", "COTA1"))
        show(state(result = ExportResult.SAVED, summary = summary(renamed = renamed)))

        compose.onNodeWithTag(SHP_RENAMED_TAG).assertTextContains("2 atributos de código han cambiado de nombre", substring = true)
        compose.onNodeWithTag(SHP_RENAMED_TAG).assertTextContains("Ancho vía → ANCHO_VIA", substring = true)
        compose.onNodeWithTag(SHP_RENAMED_TAG).assertTextContains("cota → COTA1", substring = true)
    }

    @Test
    fun `S055 RF-16 el resumen avisa de que los Shapefiles van sin prj`() {
        show(state(result = ExportResult.SAVED, summary = summary(prjMissing = true)))

        compose.onNodeWithTag(SHP_NO_PRJ_TAG).assertTextContains("sin él", substring = true)
    }

    @Test
    fun `S055 RF-15 el resumen dice el sistema de alturas de la Z porque el prj no puede declararlo`() {
        show(state(result = ExportResult.SAVED, summary = summary(heightSystem = HeightSystem.ORTHOMETRIC)))
        compose.onNodeWithTag(SHP_HEIGHT_SYSTEM_TAG).assertTextContains("altitud ortométrica", substring = true)
    }

    @Test
    fun `S055 RF-15 con alturas elipsoidales el resumen lo dice`() {
        show(state(result = ExportResult.SAVED, summary = summary(heightSystem = HeightSystem.ELLIPSOIDAL)))
        compose.onNodeWithTag(SHP_HEIGHT_SYSTEM_TAG).assertTextContains("altura elipsoidal", substring = true)
    }

    @Test
    fun `S055 RF-12 la pantalla avisa de que las coordenadas van en metros o grados y los nombres se abrevian`() {
        show(state())

        compose.onNodeWithTag(SHP_UNITS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SHP_UNITS_TAG).assertTextContains("metros", substring = true)
        compose.onNodeWithTag(SHP_UNITS_TAG).assertTextContains("10 letras", substring = true)
    }
}
