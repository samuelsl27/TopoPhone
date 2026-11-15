package es.topophone.app.ui.equipment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.survey.AntennaCatalog
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «Datos del receptor»: el formulario, el catálogo y el perfil de antena propuesto (S043/RF-18, RF-21, RF-22). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class EquipmentScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = EquipmentActions(
        onSave = { model, serial, firmware -> calls += "save:$model|$serial|$firmware" },
        onPick = { ref ->
            calls += "pick:$ref"
            AntennaCatalog.byRef(ref)?.name
        },
        onUseProfile = { calls += "use" },
    )

    private val real = EquipmentUiState(applies = true, receiverKey = "AA:BB", currentProfile = AntennaCatalog.RS3)

    private fun show(state: EquipmentUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) { EquipmentCard(state, actions) }
            }
        }
    }

    @Test
    fun `S043 RF-22 con una fuente que no es un receptor se dice que no aplica y no hay formulario`() {
        show(EquipmentUiState(applies = false))

        compose.onNodeWithTag(EQUIPMENT_NOT_APPLICABLE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(EQUIPMENT_MODEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EQUIPMENT_SAVE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S043 RF-18 el formulario muestra lo guardado y Guardar espera a que algo cambie`() {
        show(real.copy(saved = EquipmentProfile("Reach RS3", "RS3-0042", "2026.2")))

        compose.onNodeWithTag(EQUIPMENT_MODEL_TAG).assertTextContains("Reach RS3")
        compose.onNodeWithTag(EQUIPMENT_SERIAL_TAG).assertTextContains("RS3-0042")
        compose.onNodeWithTag(EQUIPMENT_FIRMWARE_TAG).assertTextContains("2026.2")
        compose.onNodeWithTag(EQUIPMENT_SAVE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S043 RF-18 se escriben los tres datos y se guardan`() {
        show(real)

        compose.onNodeWithTag(EQUIPMENT_MODEL_TAG).performTextInput("Reach RS3")
        compose.onNodeWithTag(EQUIPMENT_SERIAL_TAG).performTextInput("RS3-0042")
        compose.onNodeWithTag(EQUIPMENT_FIRMWARE_TAG).performTextInput("2026.2")
        compose.onNodeWithTag(EQUIPMENT_SAVE_TAG).performScrollTo().assertIsEnabled().performClick()

        calls shouldBe listOf("save:Reach RS3|RS3-0042|2026.2")
    }

    @Test
    fun `S043 RF-18 se edita lo guardado`() {
        show(real.copy(saved = EquipmentProfile("Reach RS3", "RS3-0042", "2026.2")))

        compose.onNodeWithTag(EQUIPMENT_FIRMWARE_TAG).performTextClearance()
        compose.onNodeWithTag(EQUIPMENT_FIRMWARE_TAG).performTextInput("2026.3")
        compose.onNodeWithTag(EQUIPMENT_SAVE_TAG).performScrollTo().performClick()

        calls shouldBe listOf("save:Reach RS3|RS3-0042|2026.3")
    }

    @Test
    fun `S043 RF-21 elegir un modelo del catalogo lo pone en el campo y no guarda ni cambia nada solo`() {
        show(real)

        compose.onNodeWithTag(equipmentCatalogTag("catalog:RS4")).performScrollTo().performClick()

        calls shouldBe listOf("pick:catalog:RS4")
        compose.onNodeWithTag(EQUIPMENT_MODEL_TAG).assertTextContains("Reach RS4/RS4 Pro")
    }

    @Test
    fun `S043 RF-21 con un modelo elegido se propone su perfil de antena y solo se cambia al pulsar`() {
        val rs4 = AntennaCatalog.byRef("catalog:RS4")!!
        show(real.copy(picked = rs4))

        compose.onNodeWithTag(EQUIPMENT_CURRENT_PROFILE_TAG).performScrollTo().assertTextContains("Reach RS3", substring = true)
        calls shouldBe emptyList()
        val use = compose.onNodeWithTag(EQUIPMENT_USE_PROFILE_TAG).performScrollTo()
        use.assertTextContains("Reach RS4/RS4 Pro", substring = true).performClick()

        calls shouldBe listOf("use")
    }

    @Test
    fun `S043 RF-21 si el receptor ya usa ese perfil no se propone`() {
        show(real.copy(picked = AntennaCatalog.RS3))

        compose.onNodeWithTag(EQUIPMENT_USE_PROFILE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S043 RF-18 el aviso del ViewModel se muestra`() {
        show(real.copy(message = EquipmentMessage.Saved))

        compose.onNodeWithTag(EQUIPMENT_MESSAGE_TAG).performScrollTo().assertTextContains("Datos guardados")
    }
}
