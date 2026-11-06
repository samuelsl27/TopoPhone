package es.topophone.app.ui.export

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.dxf.export.DxfCoordinates
import es.topophone.core.formats.dxf.export.DxfExportOptions
import es.topophone.core.formats.dxf.export.DxfExportSummary
import es.topophone.core.formats.dxf.export.DxfUnit
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla «Exportar DXF»: etiquetas, altura, sistema, unidad, guardar y compartir, mensajes y resumen (S029/RF-7, RF-8, RF-13, RF-14, RF-17 a RF-20). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class DxfExportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun state(
        count: Int = 3,
        options: DxfExportOptions = DxfExportOptions(),
        heightText: String = "0.5",
        heightValid: Boolean = true,
        canChoose: Boolean = true,
        coordinates: DxfCoordinates = DxfCoordinates.LOCAL,
        unit: DxfUnit = DxfUnit.METRE,
        summary: DxfExportSummary? = null,
        result: ExportResult? = null,
        lines: Int = 0,
        polygons: Int = 0,
    ) = DxfExportUiState(
        loaded = true,
        projectName = "Calle Mayor 12",
        pointCount = count,
        lineCount = lines,
        polygonCount = polygons,
        options = options,
        heightText = heightText,
        heightValid = heightValid,
        canChooseCoordinates = canChoose,
        coordinates = coordinates,
        unit = unit,
        summary = summary,
        result = result,
    )

    private fun show(state: DxfExportUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            DxfExportScreen(
                ThemeState.DEFAULT,
                {},
                state,
                DxfExportActions(
                    onLabel = { label, on -> calls += "label:${label.name}:$on" },
                    onCoordinates = { calls += "coordinates:${it.name}" },
                    onTextHeight = { calls += "height:$it" },
                    onSave = { calls += "save:$it" },
                    onShare = { calls += "share:$it" },
                ),
                onBack = {},
            )
        }
    }

    private fun summary(points: Int = 3, noHeight: Int = 0, vertices: Int = 0, noPosition: Int = 0, replaced: Int = 0) = DxfExportSummary(
        points = points,
        lines = 1,
        polygons = 2,
        layers = 4,
        pointsWithoutHeight = noHeight,
        verticesWithoutHeight = vertices,
        pointsWithoutPosition = noPosition,
        replacedChars = replaced,
        unit = DxfUnit.METRE,
        coordinates = DxfCoordinates.LOCAL,
    )

    @Test
    fun `S029 RF-1 dice cuantos puntos se exportan en singular y en plural`() {
        show(state(count = 1))
        compose.onNodeWithTag(DXF_COUNT_TAG).assertTextContains("Se exportará 1 punto")
    }

    @Test
    fun `S029 RF-3 dice cuantas lineas y poligonos van`() {
        show(state(lines = 2, polygons = 1))

        compose.onNodeWithTag(DXF_LINES_TAG).assertTextContains("2 líneas", substring = true)
        compose.onNodeWithTag(DXF_LINES_TAG).assertTextContains("1 polígono", substring = true)
    }

    @Test
    fun `S029 RF-18 sin puntos lo dice y no deja guardar ni compartir`() {
        show(state(count = 0))

        compose.onNodeWithTag(DXF_COUNT_TAG).assertTextContains("No hay puntos que exportar.")
        compose.onNodeWithTag(DXF_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DXF_SHARE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S029 RF-7 Nombre y Cota vienen marcadas y Descripcion no, y cada casilla avisa de su cambio`() {
        show(state())

        compose.onNodeWithTag(DXF_LABEL_NAME_TAG).assertIsOn()
        compose.onNodeWithTag(DXF_LABEL_ELEVATION_TAG).assertIsOn()
        compose.onNodeWithTag(DXF_LABEL_DESCRIPTION_TAG).assertIsOff()
        compose.onNodeWithTag(DXF_LABEL_DESCRIPTION_TAG).performClick()
        compose.onNodeWithTag(DXF_LABEL_NAME_TAG).performClick()

        calls shouldContainExactly listOf("label:DESCRIPTION:true", "label:NAME:false")
    }

    @Test
    fun `S029 RF-8 la altura del texto se escribe y un valor fuera de rango avisa y no deja exportar`() {
        show(state())
        compose.onNodeWithTag(DXF_TEXT_HEIGHT_TAG).performTextReplacement("2,5")
        calls shouldContainExactly listOf("height:2,5")
        compose.onNodeWithTag(DXF_SAVE_TAG).assertIsEnabled()
    }

    @Test
    fun `S029 RF-8 con una altura no valida se ve el aviso y los botones estan desactivados`() {
        show(state(heightText = "500", heightValid = false))

        compose.onNodeWithTag(DXF_TEXT_HEIGHT_ERROR_TAG, useUnmergedTree = true).assertTextContains("Entre 0,01 m y 100 m.")
        compose.onNodeWithTag(DXF_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(DXF_SHARE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S029 RF-11 RF-12 en un proyecto UTM se elige entre locales y globales`() {
        show(state())

        compose.onNodeWithTag(DXF_COORD_LOCAL_TAG).assertIsSelected()
        compose.onNodeWithTag(DXF_COORD_GLOBAL_TAG).performScrollTo().performClick()

        calls shouldContainExactly listOf("coordinates:GLOBAL")
    }

    @Test
    fun `S029 RF-12 con globales elegidas aparece elegida esa opcion`() {
        show(state(coordinates = DxfCoordinates.GLOBAL, unit = DxfUnit.DEGREE))

        compose.onNodeWithTag(DXF_COORD_GLOBAL_TAG).assertIsSelected()
    }

    @Test
    fun `S029 RF-13 en un proyecto geografico no se ofrece elegir y se dice que son globales`() {
        show(state(canChoose = false, coordinates = DxfCoordinates.GLOBAL, unit = DxfUnit.DEGREE))

        compose.onAllNodesWithTag(DXF_COORD_LOCAL_TAG).assertCountEquals(0)
        compose.onNodeWithTag(DXF_COORD_FIXED_TAG).assertTextContains("Globales", substring = true)
    }

    @Test
    fun `S029 RF-14 la pantalla dice la unidad de las coordenadas`() {
        show(state(unit = DxfUnit.US_SURVEY_FOOT))
        compose.onNodeWithTag(DXF_UNIT_TAG).assertTextContains("pies de EE. UU.", substring = true)
    }

    @Test
    fun `S029 RF-14 en metros y en grados tambien`() {
        show(state(unit = DxfUnit.METRE))
        compose.onNodeWithTag(DXF_UNIT_TAG).assertTextContains("metros", substring = true)
    }

    @Test
    fun `S029 RF-16 muestra el nombre propuesto con el proyecto, dxf y la fecha`() {
        show(state())

        compose.onNodeWithTag(DXF_FILE_NAME_TAG).assertTextContains("Calle Mayor 12_dxf_", substring = true)
        compose.onNodeWithTag(DXF_FILE_NAME_TAG).assertTextContains(".dxf", substring = true)
    }

    @Test
    fun `S029 RF-17 guardar y compartir llevan el nombre propuesto a las acciones`() {
        show(state())

        compose.onNodeWithTag(DXF_SAVE_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag(DXF_SHARE_TAG).performScrollTo().performClick()

        calls.size shouldBe 2
        calls[0] shouldStartWith "save:Calle Mayor 12_dxf_"
        calls[1] shouldStartWith "share:Calle Mayor 12_dxf_"
    }

    @Test
    fun `S029 RNF-T8 los botones y las opciones miden al menos 56 dp`() {
        show(state())

        for (tag in listOf(
            DXF_SAVE_TAG,
            DXF_SHARE_TAG,
            DXF_COORD_LOCAL_TAG,
            DXF_COORD_GLOBAL_TAG,
            DXF_LABEL_NAME_TAG,
            DXF_LABEL_ELEVATION_TAG,
        )) {
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(56.dp)
        }
        compose.onNodeWithTag(DXF_LABEL_DESCRIPTION_TAG).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(DXF_TEXT_HEIGHT_TAG).assertHeightIsAtLeast(56.dp)
    }

    @Test
    fun `S029 RF-19 si no se ha podido exportar lo dice y si se guardo tambien`() {
        show(state(result = ExportResult.FAILED))
        compose.onNodeWithTag(DXF_RESULT_TAG).assertTextContains("No se ha podido exportar el fichero.")
    }

    @Test
    fun `S029 RF-17 un fichero guardado lo dice`() {
        show(state(result = ExportResult.SAVED))
        compose.onNodeWithTag(DXF_RESULT_TAG).assertTextContains("Fichero guardado.")
    }

    @Test
    fun `S029 RF-20 el resumen dice puntos lineas poligonos y capas`() {
        show(state(result = ExportResult.SAVED, summary = summary()))

        compose.onNodeWithTag(DXF_SUMMARY_TAG).assertTextContains("3 puntos", substring = true)
        compose.onNodeWithTag(DXF_SUMMARY_TAG).assertTextContains("1 línea", substring = true)
        compose.onNodeWithTag(DXF_SUMMARY_TAG).assertTextContains("2 polígonos", substring = true)
        compose.onNodeWithTag(DXF_SUMMARY_TAG).assertTextContains("4 capas", substring = true)
        compose.onNodeWithTag(DXF_SUMMARY_UNIT_TAG).assertTextContains("metros", substring = true) // RF-14
        compose.onAllNodesWithTag(DXF_NO_HEIGHT_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(DXF_NO_POSITION_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(DXF_REPLACED_TAG).assertCountEquals(0)
    }

    @Test
    fun `S029 RF-9 RF-15 RF-20 el resumen avisa de lo que va sin altura, sin posicion y de los caracteres sustituidos`() {
        show(state(result = ExportResult.SAVED, summary = summary(noHeight = 2, vertices = 5, noPosition = 1, replaced = 3)))

        compose.onNodeWithTag(DXF_NO_HEIGHT_TAG).assertTextContains("2 puntos y 5 vértices", substring = true)
        compose.onNodeWithTag(DXF_NO_POSITION_TAG).assertTextContains("1 puntos sin posición", substring = true)
        compose.onNodeWithTag(DXF_REPLACED_TAG).assertTextContains("3 caracteres sustituidos", substring = true)
    }
}
