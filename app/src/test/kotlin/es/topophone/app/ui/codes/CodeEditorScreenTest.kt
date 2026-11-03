package es.topophone.app.ui.codes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodePalette
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El editor de un código: paleta con marca y texto, atributos, motivos de rechazo y zonas tactiles (S023/RF-5 a RF-10, RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CodeEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: CodeEditorUiState, actions: CodeEditorActions = CodeEditorActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CodeEditorScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    private val withAttributes = CodeEditorUiState(
        loaded = true,
        code = "ARQ",
        attributes = listOf(
            AttributeDraft("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón")),
            AttributeDraft("Diámetro", AttributeType.NUMBER, "0.25"),
        ),
    )

    @Test
    fun `S023 RF-5 escribir el codigo y la descripcion y guardar llega a quien lo atiende`() {
        val calls = mutableListOf<String>()
        show(
            CodeEditorUiState(loaded = true),
            CodeEditorActions(onCode = { calls += "code:$it" }, onDescription = { calls += "desc:$it" }, onSave = { calls += "save" }),
        )

        compose.onNodeWithTag(CODE_FIELD_TAG).performTextReplacement("ARQ")
        compose.onNodeWithTag(CODE_DESCRIPTION_TAG).performTextReplacement("Arqueta")
        compose.onNodeWithTag(CODE_SAVE_TAG).performScrollTo().performClick()

        ("code:ARQ" in calls) shouldBe true
        ("desc:Arqueta" in calls) shouldBe true
        calls.last() shouldBe "save"
    }

    @Test
    fun `S023 RF-5 la geometria se elige entre tres opciones con texto`() {
        val chosen = mutableListOf<CodeGeometry>()
        show(CodeEditorUiState(loaded = true, geometry = CodeGeometry.LINE), CodeEditorActions(onGeometry = { chosen += it }))

        compose.onNodeWithTag(geometryTag(CodeGeometry.LINE)).assertIsSelected()
        compose.onNodeWithTag(geometryTag(CodeGeometry.POLYGON)).performClick()

        chosen shouldBe listOf(CodeGeometry.POLYGON)
        compose.onNodeWithText("Punto").assertIsDisplayed()
    }

    @Test
    fun `S023 RF-8 la paleta ofrece 12 colores y automatico, y el elegido se marca con texto para TalkBack`() {
        val picked = mutableListOf<Int?>()
        val chosen = CodePalette.COLORS[2]
        show(CodeEditorUiState(loaded = true, color = chosen), CodeEditorActions(onColor = { picked += it }))

        CodePalette.COLORS.forEach { compose.onNodeWithTag(colorTag(it)).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithContentDescription(
            text(R.string.codes_color_option, CodePalette.format(chosen)) + ", " + text(R.string.codes_color_selected),
        )
            .assertIsDisplayed()
        compose.onNodeWithTag(colorTag(CodePalette.COLORS[5])).performScrollTo().performClick()
        compose.onNodeWithTag(CODE_COLOR_AUTO_TAG).performScrollTo().performClick()

        picked shouldBe listOf(CodePalette.COLORS[5], null)
    }

    @Test
    fun `S023 RF-8 sin color elegido se marca automatico`() {
        show(CodeEditorUiState(loaded = true, color = null))

        compose.onNodeWithTag(CODE_COLOR_AUTO_TAG).assertIsSelected()
    }

    @Test
    fun `S023 RF-9 cada atributo muestra su nombre, su tipo y, si es una lista, sus opciones y su valor por defecto`() {
        show(withAttributes)

        compose.onNodeWithTag(attributeNameTag(0)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(attributeTypeTag(0, AttributeType.LIST)).assertIsSelected()
        compose.onNodeWithTag(optionTag(0, 1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(attributeDefaultChoiceTag(0, "PVC")).assertIsSelected()
        compose.onNodeWithTag(attributeTypeTag(1, AttributeType.NUMBER)).assertIsSelected()
        compose.onNodeWithTag(attributeDefaultTag(1)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S023 RF-9 y RF-10 anadir, mover y quitar atributos y opciones llega a quien lo atiende`() {
        val calls = mutableListOf<String>()
        show(
            withAttributes,
            CodeEditorActions(
                onAddAttribute = { calls += "add" },
                onMoveAttribute = { i, d -> calls += "move:$i:$d" },
                onRemoveAttribute = { calls += "remove:$it" },
                onAddOption = { calls += "addOption:$it" },
                onRemoveOption = { i, o -> calls += "removeOption:$i:$o" },
                onAttributeType = { i, t -> calls += "type:$i:${t.name}" },
            ),
        )

        compose.onNodeWithTag(ATTRIBUTE_ADD_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(attributeDownTag(0)).performScrollTo().performClick()
        compose.onNodeWithTag(attributeUpTag(1)).performScrollTo().performClick()
        compose.onNodeWithTag(attributeRemoveTag(1)).performScrollTo().performClick()
        compose.onNodeWithTag(OPTION_ADD_TAG_PREFIX + 0).performScrollTo().performClick()
        compose.onNodeWithTag(optionRemoveTag(0, 1)).performScrollTo().performClick()
        compose.onNodeWithTag(attributeTypeTag(1, AttributeType.INTEGER)).performScrollTo().performClick()

        calls shouldBe listOf("add", "move:0:1", "move:1:-1", "remove:1", "addOption:0", "removeOption:0:1", "type:1:INTEGER")
    }

    @Test
    fun `S023 RF-10 el primer atributo no sube ni el ultimo baja`() {
        show(withAttributes)

        compose.onNodeWithTag(attributeUpTag(0)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(attributeDownTag(1)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S023 RF-6 y RF-7 y RF-9 el motivo del rechazo sale en texto`() {
        var error by mutableStateOf<CodeError?>(null)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CodeEditorScreen(ThemeState.DEFAULT, {}, withAttributes.copy(error = error), CodeEditorActions(), {})
            }
        }

        listOf(
            CodeError.Empty to text(R.string.codes_error_empty),
            CodeError.TooLong to text(R.string.codes_error_too_long),
            CodeError.NewLine to text(R.string.codes_error_newline),
            CodeError.Duplicate("Árbol") to "Ya existe el código «Árbol» en la biblioteca.",
            CodeError.TooManyAttributes to text(R.string.codes_error_too_many_attributes),
            CodeError.AttributeName(1) to text(R.string.codes_error_attribute_name, 2),
            CodeError.ListOptions(0) to text(R.string.codes_error_list_options, 1),
            CodeError.DefaultValue(2) to text(R.string.codes_error_default_value, 3),
        ).forEach { (reason, expected) ->
            error = reason
            compose.waitForIdle()
            compose.onNodeWithTag(CODE_ERROR_TAG).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(expected).assertIsDisplayed()
        }
    }

    @Test
    fun `S023 RF-5 borrar solo existe al editar y pide confirmacion con el codigo`() {
        var confirmed = false
        show(withAttributes.copy(isNew = false, confirmDelete = true), CodeEditorActions(onConfirmDelete = { confirmed = true }))

        compose.onNodeWithText(text(R.string.codes_delete_code_message, "ARQ")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.codes_library_delete)).performClick()

        confirmed shouldBe true
    }

    @Test
    fun `S023 RF-5 un codigo nuevo no tiene boton de borrar`() {
        show(CodeEditorUiState(loaded = true, isNew = true))

        compose.onNodeWithTag(CODE_DELETE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S023 RNF-T8 los controles miden al menos 56 dp`() {
        show(withAttributes.copy(isNew = false))

        listOf(
            CODE_SAVE_TAG,
            CODE_DELETE_TAG,
            ATTRIBUTE_ADD_TAG,
            attributeTypeTag(0, AttributeType.TEXT),
            colorTag(CodePalette.COLORS[0]),
        ).forEach {
            compose.onNodeWithTag(it).performScrollTo().assertHeightIsAtLeast(MinTouchTarget)
        }
        compose.onNodeWithTag(
            attributeRemoveTag(0),
        ).performScrollTo().assertHeightIsAtLeast(MinTouchTarget).assertWidthIsAtLeast(MinTouchTarget)
    }
}
