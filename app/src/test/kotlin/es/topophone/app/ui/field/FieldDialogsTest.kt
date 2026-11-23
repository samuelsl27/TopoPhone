package es.topophone.app.ui.field

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.format.DurationText
import es.topophone.core.model.preferences.EffectiveTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Los diálogos de las pastillas: teclado numérico, tiempo en minutos y segundos y lista corta (S060/RF-7, RF-11 a RF-13). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class FieldDialogsTest {

    @get:Rule
    val compose = createComposeRule()

    private val confirmed = mutableListOf<String>()
    private var dismissed = 0

    private fun poleDialog(initial: String = "1,800") {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                NumericEntryDialog(
                    title = "Altura del jalón",
                    label = "Altura",
                    initial = initial,
                    validate = { text ->
                        R.string.pole_height_invalid.takeIf {
                            (text.replace(',', '.').toDoubleOrNull() ?: -1.0) !in
                                0.0..6.0
                        }
                    },
                    onConfirm = { confirmed += it },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    private fun timeDialog(initialSeconds: Int = 5) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                TimeEntryDialog(
                    title = "Tiempo",
                    initialSeconds = initialSeconds,
                    validate = { text -> R.string.collector_seconds_invalid.takeIf { DurationText.parse(text, 1..3600) == null } },
                    onConfirm = { confirmed += it },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    @Test
    fun `S060 RF-11 el dialogo del jalon abre con el valor actual y el teclado decimal`() {
        poleDialog()

        compose.onNodeWithTag(FIELD_ENTRY_TAG).assertTextContains("1,800", substring = true)
    }

    @Test
    fun `S060 RF-11 un valor valido se aplica al confirmar`() {
        poleDialog()

        compose.onNodeWithTag(FIELD_ENTRY_TAG).performTextClearance()
        compose.onNodeWithTag(FIELD_ENTRY_TAG).performTextInput("2,000")
        compose.onNodeWithTag(FIELD_ENTRY_CONFIRM_TAG).performClick()

        confirmed shouldBe listOf("2,000")
        dismissed shouldBe 1
    }

    @Test
    fun `S060 RF-12 un valor fuera de rango dice el motivo junto al campo y no se aplica`() {
        poleDialog()

        compose.onNodeWithTag(FIELD_ENTRY_TAG).performTextClearance()
        compose.onNodeWithTag(FIELD_ENTRY_TAG).performTextInput("9,5")

        compose.onNodeWithTag(FIELD_ENTRY_ERROR_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FIELD_ENTRY_CONFIRM_TAG).assertIsNotEnabled()
        confirmed shouldBe emptyList()
    }

    @Test
    fun `S060 RF-11 un valor valido deja confirmar y no muestra error`() {
        poleDialog()

        compose.onNodeWithTag(FIELD_ENTRY_CONFIRM_TAG).assertIsEnabled()
        compose.onAllNodesWithTag(FIELD_ENTRY_ERROR_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-11 Cancelar cierra sin aplicar`() {
        poleDialog()

        compose.onNodeWithTag(FIELD_ENTRY_CANCEL_TAG).performClick()

        confirmed shouldBe emptyList()
        dismissed shouldBe 1
    }

    @Test
    fun `S060 RF-11 el tiempo abre con minutos y segundos separados`() {
        timeDialog(initialSeconds = 90)

        compose.onNodeWithTag(FIELD_ENTRY_MINUTES_TAG).assertTextContains("1", substring = true)
        compose.onNodeWithTag(FIELD_ENTRY_SECONDS_TAG).assertTextContains("30", substring = true)
    }

    @Test
    fun `S060 RF-11 minutos y segundos se juntan en el texto m dos puntos ss de S017`() {
        timeDialog(initialSeconds = 5)

        compose.onNodeWithTag(FIELD_ENTRY_MINUTES_TAG).performTextClearance()
        compose.onNodeWithTag(FIELD_ENTRY_MINUTES_TAG).performTextInput("2")
        compose.onNodeWithTag(FIELD_ENTRY_SECONDS_TAG).performTextClearance()
        compose.onNodeWithTag(FIELD_ENTRY_SECONDS_TAG).performTextInput("5")
        compose.onNodeWithTag(FIELD_ENTRY_CONFIRM_TAG).performClick()

        confirmed shouldBe listOf("2:05")
    }

    @Test
    fun `S060 RF-12 un tiempo fuera de rango dice el motivo y no se aplica`() {
        timeDialog()

        compose.onNodeWithTag(FIELD_ENTRY_MINUTES_TAG).performTextClearance()
        compose.onNodeWithTag(FIELD_ENTRY_MINUTES_TAG).performTextInput("99")

        compose.onNodeWithTag(FIELD_ENTRY_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FIELD_ENTRY_CONFIRM_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S060 RF-13 la lista corta marca la opcion vigente y elegir otra la aplica y cierra`() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ShortListDialog(
                    title = "Modo",
                    options = listOf("a" to "Instantáneo", "b" to "Promediado", "c" to "Automático por tiempo"),
                    selected = "b",
                    onSelect = { chosen += it },
                    onDismiss = { dismissed++ },
                )
            }
        }

        compose.onNodeWithTag(shortListOptionTag("b")).assertIsSelected()
        compose.onNodeWithTag(shortListOptionTag("a")).performClick()

        chosen shouldBe listOf("a")
        dismissed shouldBe 1
    }

    @Test
    fun `S060 RF-7 el teclado decimal no es el de texto`() {
        (fieldEntryKeyboard(decimal = true) == KeyboardType.Decimal) shouldBe true
        (fieldEntryKeyboard(decimal = false) == KeyboardType.Number) shouldBe true
    }
}
