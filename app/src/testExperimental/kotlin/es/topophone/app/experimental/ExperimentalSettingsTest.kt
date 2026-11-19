package es.topophone.app.experimental

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.data.settings.SettingsDataStore
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** S044/RF-3, RF-4, RF-5: el interruptor «Módulo experimental» de Ajustes y su aviso legal. Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class ExperimentalSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun close() = scopes.forEach { it.cancel() }

    private fun hoisted(accepted: MutableList<Unit>, deactivated: MutableList<Unit>, startActive: Boolean = false) {
        compose.setContent {
            var active by remember { mutableStateOf(startActive) }
            ExperimentalSettingsRow(
                active = active,
                onAccept = {
                    accepted += Unit
                    active = true
                },
                onDeactivate = {
                    deactivated += Unit
                    active = false
                },
            )
        }
    }

    @Test
    fun `S044 RF-3 de fabrica el interruptor esta apagado y no hay aviso`() {
        hoisted(mutableListOf(), mutableListOf())

        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).assertIsOff()
        compose.onNodeWithTag(EXPERIMENTAL_NOTICE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).assertTextContains(text(R.string.experimental_settings_title))
    }

    @Test
    fun `S044 RF-4 activar abre el aviso con su texto y todavia no activa nada`() {
        val accepted = mutableListOf<Unit>()
        hoisted(accepted, mutableListOf())

        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).performClick()

        compose.onNodeWithTag(EXPERIMENTAL_NOTICE_TAG).assertExists()
        compose.onNodeWithText(text(R.string.experimental_notice_body)).assertExists()
        accepted.size shouldBe 0
        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).assertIsOff()
    }

    @Test
    fun `S044 RF-4 rechazar el aviso lo cierra y el modulo sigue apagado`() {
        val accepted = mutableListOf<Unit>()
        hoisted(accepted, mutableListOf())
        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).performClick()

        compose.onNodeWithTag(EXPERIMENTAL_NOTICE_CANCEL_TAG).performClick()

        compose.onNodeWithTag(EXPERIMENTAL_NOTICE_TAG).assertDoesNotExist()
        accepted.size shouldBe 0
        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).assertIsOff()
    }

    @Test
    fun `S044 RF-4 aceptar el aviso activa el modulo una vez`() {
        val accepted = mutableListOf<Unit>()
        hoisted(accepted, mutableListOf())
        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).performClick()

        compose.onNodeWithTag(EXPERIMENTAL_NOTICE_ACCEPT_TAG).performClick()

        accepted.size shouldBe 1
        compose.onNodeWithTag(EXPERIMENTAL_NOTICE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).assertIsOn()
    }

    @Test
    fun `S044 RF-5 desactivar apaga el interruptor sin aviso y al volver a activar el aviso sale otra vez`() {
        val accepted = mutableListOf<Unit>()
        val deactivated = mutableListOf<Unit>()
        hoisted(accepted, deactivated, startActive = true)
        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).assertIsOn()

        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).performClick()

        deactivated.size shouldBe 1
        compose.onNodeWithTag(EXPERIMENTAL_NOTICE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).assertIsOff()

        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).performClick()
        compose.onNodeWithTag(EXPERIMENTAL_NOTICE_TAG).assertExists()
    }

    @Test
    fun `S044 RF-4 el aviso no nombra al fabricante`() {
        val texts = listOf(
            R.string.experimental_notice_title,
            R.string.experimental_notice_body,
            R.string.experimental_settings_title,
            R.string.experimental_settings_summary_off,
        ).map { text(it).lowercase() }

        texts.none { "emlid" in it || "reach" in it } shouldBe true
    }

    @Test
    fun `S044 RF-4 aceptar con las preferencias reales guarda la fecha y desactivar la borra`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val prefs = ExperimentalPreferences(SettingsDataStore.create(scope) { File(folder.root, "e.preferences_pb") })
        val feature = ExperimentalFeatureImpl(prefs) { 1_795_082_400_000L }
        compose.setContent { feature.SettingsRow() }

        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).assertIsOff()
        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).performClick()
        compose.onNodeWithTag(EXPERIMENTAL_NOTICE_ACCEPT_TAG).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).assertIsOn() }.isSuccess }
        prefs.acceptedAtMs.first() shouldBe 1_795_082_400_000L

        compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(EXPERIMENTAL_SWITCH_TAG).assertIsOff() }.isSuccess }

        prefs.acceptedAtMs.first() shouldBe null
    }
}
