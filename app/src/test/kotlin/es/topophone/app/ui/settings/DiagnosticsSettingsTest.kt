package es.topophone.app.ui.settings

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.log.RecordingLog
import es.topophone.app.log.SystemReportWriter
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.text
import es.topophone.core.model.log.LogLevel
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Sección "Diagnóstico" de Ajustes: informe de sistema y tamaño del log (S014/RF-9, RF-10, RF-16, RF-17). Interfaz en español. */
@RunWith(AndroidJUnit4::class)
class DiagnosticsSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val log = RecordingLog()

    private fun viewModel(
        writer: SystemReportWriter = SystemReportWriter {
            it.write(1)
            true
        },
    ) = SystemReportViewModel(writer, log).apply {
        clock = { Instant.parse("2026-10-19T09:40:12Z") }
        zone = { ZoneId.of("Europe/Madrid") }
    }

    @Test
    fun `S014 RF-9 el tamano del log se elige entre 5 y 50 MB`() {
        var chosen: Int? = null
        compose.setContent { StatefulTestApp(onSelectLogSize = { chosen = it }) }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()

        compose.onNodeWithTag(LOG_SIZE_SETTING_TAG).performScrollTo()
            .assert(hasText(text(R.string.log_size)))
            .assert(hasText("10 MB"))
            .performClick()
        listOf(5, 10, 20, 50).forEach { compose.onNodeWithTag(logSizeOptionTag(it)).assert(hasText("$it MB")) }
        compose.onNodeWithTag(logSizeOptionTag(50)).performClick()

        chosen shouldBe 50
        compose.onNodeWithTag(LOG_SIZE_SETTING_TAG).performScrollTo().assert(hasText("50 MB"))
    }

    @Test
    fun `S014 RF-10 Crear informe de sistema esta en Diagnostico`() {
        var asked = 0
        compose.setContent { StatefulTestApp(onCreateReport = { asked++ }) }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()

        compose.onNodeWithText(text(R.string.settings_section_diagnostics)).performScrollTo()
        compose.onNodeWithTag(REPORT_SETTING_TAG).performScrollTo().assert(hasText(text(R.string.report_create))).performClick()

        asked shouldBe 1
    }

    @Test
    fun `S014 RF-10 se ofrece guardar o compartir y cancelar no escribe nada`() {
        val vm = viewModel()
        compose.setContent {
            val state by vm.state.collectAsState()
            SystemReportDialogs(
                state,
                onSave = vm::startWriting,
                onShare = vm::startWriting,
                onCancel = vm::cancel,
                onDismissMessage = vm::dismissMessage,
            )
        }
        compose.runOnIdle { vm.start() }

        compose.onNodeWithTag(REPORT_SAVE_TAG).assert(hasText(text(R.string.export_save)))
        compose.onNodeWithTag(REPORT_SHARE_TAG).assert(hasText(text(R.string.export_share)))
        compose.onNodeWithText(text(R.string.dialog_cancel)).performClick()

        compose.runOnIdle { vm.state.value shouldBe ReportUiState() }
        log.entries shouldBe emptyList()
    }

    @Test
    fun `S014 RF-16 el nombre propuesto lleva la fecha y hora locales`() {
        val vm = viewModel()
        vm.start()

        vm.state.value.fileName shouldBe "TopoPhone_informe_20261019-1140.zip"
    }

    @Test
    fun `S014 RF-17 si no se escribe se dice y queda en el log`() {
        val vm = viewModel()
        compose.setContent {
            val state by vm.state.collectAsState()
            SystemReportDialogs(state, onSave = {}, onShare = {}, onCancel = {}, onDismissMessage = vm::dismissMessage)
        }
        compose.runOnIdle {
            vm.start()
            vm.startWriting()
            vm.done(false)
        }

        compose.onNodeWithTag(REPORT_MESSAGE_TAG).assert(hasText(text(R.string.report_not_saved)))
        log.entries.single().first shouldBe LogLevel.ERROR
    }

    @Test
    fun `S014 RF-17 al compartir no queda un informe a medias`() {
        val file = File(folder.root, "TopoPhone_informe_20261019-1140.zip")
        val failing = viewModel(
            SystemReportWriter {
                it.write(1)
                throw IOException("sin espacio")
            },
        )

        runBlocking { runCatching { failing.writeFile(file) } }
        file.exists() shouldBe false

        val broken = viewModel(SystemReportWriter { false })
        runBlocking { broken.writeFile(file) } shouldBe false
        file.exists() shouldBe false

        runBlocking { viewModel().writeFile(file) } shouldBe true
        file.length() shouldBe 1L
    }

    @Test
    fun `S014 RF-17 informe guardado se dice y queda en el log`() {
        val vm = viewModel()
        vm.start()
        vm.done(true)

        vm.state.value.message shouldBe ReportMessage.SAVED
        log.entries.single().first shouldBe LogLevel.INFO
        log.messages.single() shouldBe "Informe de sistema creado: TopoPhone_informe_20261019-1140.zip"
    }
}
