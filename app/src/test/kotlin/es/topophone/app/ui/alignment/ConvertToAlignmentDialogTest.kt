package es.topophone.app.ui.alignment

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.cad.DESIGN_CONVERT_TAG
import es.topophone.app.ui.cad.DesignElementSheet
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «Convertir en eje» desde la ficha de una polilínea: el botón, el nombre, el PK inicial y el resultado (S049/RF-6, RF-7). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ConvertToAlignmentDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private fun selection(kind: CadEntityKind, vertices: Int = 2) = DesignSelection(
        ref = CadEntityRef("l1", 1L, 0),
        entity = CadEntity(
            kind,
            DoubleArray(vertices) { 440000.0 + it },
            DoubleArray(vertices) { 4474000.0 },
            null,
            null,
        ),
        layerName = "Ejes",
        drawingName = "plano.dxf",
        hasElevation = false,
    )

    private fun showSheet(selection: DesignSelection, onConvert: (() -> Unit)?) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                DesignElementSheet(selection, LinearUnit.METRE, "m", geographic = false, onDismiss = {}, onConvertToAxis = onConvert)
            }
        }
    }

    @Test
    fun `S049 RF-6 la ficha de una polilinea o una linea ofrece Convertir en eje`() {
        var converted = 0
        showSheet(selection(CadEntityKind.POLYLINE, 3)) { converted++ }

        compose.onNodeWithTag(DESIGN_CONVERT_TAG).assertIsDisplayed().performClick()

        converted shouldBe 1
    }

    @Test
    fun `S049 RF-6 un punto un circulo o una spline no ofrecen Convertir en eje`() {
        showSheet(selection(CadEntityKind.CIRCLE, 8)) {}

        compose.onNodeWithTag(DESIGN_CONVERT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S049 RF-6 sin la accion la ficha no ofrece Convertir en eje`() {
        showSheet(selection(CadEntityKind.POLYLINE), onConvert = null)

        compose.onNodeWithTag(DESIGN_CONVERT_TAG).assertDoesNotExist()
    }

    private fun showDialog(
        state: AlignmentFromDrawingUiState = AlignmentFromDrawingUiState(),
        unit: LinearUnit = LinearUnit.METRE,
        onConvert: (String, Double) -> Unit = { _, _ -> },
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ConvertToAlignmentDialog("Ejes", unit, if (unit == LinearUnit.METRE) "m" else "ft", state, onConvert, onDismiss)
            }
        }
    }

    @Test
    fun `S049 RF-6 el dialogo propone el nombre de la capa y el PK 0 y crea con ellos`() {
        var created: Pair<String, Double>? = null
        showDialog(onConvert = { name, start -> created = name to start })

        compose.onNodeWithTag(ALIGNMENT_CONVERT_CREATE_TAG).assertIsEnabled().performClick()

        created shouldBe ("Ejes" to 0.0)
    }

    @Test
    fun `S049 RF-6 se puede escribir el nombre y el PK inicial con coma decimal`() {
        var created: Pair<String, Double>? = null
        showDialog(onConvert = { name, start -> created = name to start })

        compose.onNodeWithTag(ALIGNMENT_CONVERT_NAME_TAG).performTextClearance()
        compose.onNodeWithTag(ALIGNMENT_CONVERT_NAME_TAG).performTextInput("Carretera")
        compose.onNodeWithTag(ALIGNMENT_CONVERT_START_TAG).performTextClearance()
        compose.onNodeWithTag(ALIGNMENT_CONVERT_START_TAG).performTextInput("1240,5")
        compose.onNodeWithTag(ALIGNMENT_CONVERT_CREATE_TAG).performClick()

        created!!.first shouldBe "Carretera"
        created!!.second shouldBe (1240.5 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-6 el PK inicial escrito en pies se pasa a metros`() {
        var created: Pair<String, Double>? = null
        showDialog(unit = LinearUnit.INTERNATIONAL_FOOT, onConvert = { name, start -> created = name to start })

        compose.onNodeWithTag(ALIGNMENT_CONVERT_START_TAG).performTextClearance()
        compose.onNodeWithTag(ALIGNMENT_CONVERT_START_TAG).performTextInput("1000")
        compose.onNodeWithTag(ALIGNMENT_CONVERT_CREATE_TAG).performClick()

        created!!.second shouldBe (304.8 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-6 un PK que no es un numero o un nombre en blanco impiden crear`() {
        showDialog()

        compose.onNodeWithTag(ALIGNMENT_CONVERT_START_TAG).performTextClearance()
        compose.onNodeWithTag(ALIGNMENT_CONVERT_START_TAG).performTextInput("abc")
        compose.onNodeWithText("Escribe un número.").assertIsDisplayed()
        compose.onNodeWithTag(ALIGNMENT_CONVERT_CREATE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(ALIGNMENT_CONVERT_START_TAG).performTextClearance()
        compose.onNodeWithTag(ALIGNMENT_CONVERT_NAME_TAG).performTextClearance()
        compose.onNodeWithTag(ALIGNMENT_CONVERT_CREATE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S049 RF-6 mientras se crea el boton no se puede pulsar otra vez`() {
        showDialog(AlignmentFromDrawingUiState(working = true))

        compose.onNodeWithTag(ALIGNMENT_CONVERT_CREATE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S049 RF-7 el resultado dice el eje creado y si lleva rasante`() {
        showDialog(AlignmentFromDrawingUiState(result = DrawingAlignmentResult.Created("Zanja", 150.0, 1200.0, hasProfile = true)))

        compose.onNodeWithTag(ALIGNMENT_CONVERT_RESULT_TAG).assertIsDisplayed()
        compose.onNodeWithText("Eje «Zanja» creado: 150,0 m, del PK 1+200,00 al 1+350,00.").assertIsDisplayed()
        compose.onNodeWithText("rasante con las cotas", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S049 RF-7 sin cotas el resultado dice que no hay rasante`() {
        showDialog(AlignmentFromDrawingUiState(result = DrawingAlignmentResult.Created("Zanja", 150.0, 0.0, hasProfile = false)))

        compose.onNodeWithText("Sin rasante: la línea no trae cotas.").assertIsDisplayed()
    }

    @Test
    fun `S049 RF-6 un rechazo dice el motivo y cerrar llama a descartar`() {
        var dismissed = 0
        showDialog(AlignmentFromDrawingUiState(result = DrawingAlignmentResult.Rejected(AlignmentIssue.NonCircularCurve(2))), onDismiss = {
            dismissed++
        })

        compose.onNodeWithTag(ALIGNMENT_CONVERT_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithText("el trozo que empieza en el vértice 2 no es un arco", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(ALIGNMENT_CONVERT_CLOSE_TAG).performClick()

        dismissed shouldBe 1
    }
}
