package es.topophone.app.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.backup.FakePackageBackups
import es.topophone.app.ui.export.shareExportIntent
import es.topophone.app.ui.projects.ProjectListActions
import es.topophone.app.ui.projects.ProjectListScreen
import es.topophone.app.ui.projects.ProjectListUiState
import es.topophone.app.ui.projects.projectMenuTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Exportar paquete" de un proyecto: guardar o compartir (S013/RF-4, RF-5, RF-24). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PackageExportTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1", name = "Calle Mayor 12", author = "", description = "", crs = ProjectCrs.ETRS89_UTM30, linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS, correctionsDatum = CorrectionsDatum.ETRS89, folderId = null, createdAtMs = 0, modifiedAtMs = 0,
        openedAtMs = null, deletedAtMs = null,
    )
    private val backups = FakePackageBackups()
    private val vm = PackageViewModel(backups).apply {
        clock = { Instant.parse("2026-10-19T06:02:31Z") }
        zone = { ZoneId.of("Europe/Madrid") }
    }

    @Test
    fun `S013 RF-24 el menu de un proyecto ofrece exportar su paquete`() {
        var chosen: Project? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {
                    },
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onExportPackage = {
                        chosen =
                            it
                    }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Exportar paquete").performClick()

        chosen shouldBe project
    }

    @Test
    fun `S013 RF-4 y RF-24 se propone el nombre y se pregunta guardar o compartir`() {
        vm.startExport("p1", "Calle Mayor 12")
        val names = mutableListOf<String>()
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PackageExportDialogs(state, onSave = {
                    names += "save:$it"
                }, onShare = { names += "share:$it" }, onCancel = {}, onDismissMessage = {})
            }
        }

        compose.onNodeWithText("Paquete de Calle Mayor 12", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(PACKAGE_SAVE_TAG).performClick()
        compose.onNodeWithTag(PACKAGE_SHARE_TAG).performClick()

        names shouldBe listOf("save:Calle Mayor 12_20261019-0802.topoproj", "share:Calle Mayor 12_20261019-0802.topoproj")
    }

    @Test
    fun `S013 RF-24 se escribe el paquete del proyecto elegido y se dice`() {
        vm.startExport("p1", "Calle Mayor 12")
        vm.startWriting()
        val out = ByteArrayOutputStream()

        runBlocking { vm.writePackage(out) } shouldBe true
        vm.exportDone(true)

        backups.written shouldBe listOf("p1")
        out.toString() shouldBe "paquete"
        vm.state.value shouldBe PackageUiState(message = PackageMessage.SAVED)
    }

    @Test
    fun `S013 RF-5 si no se ha podido se dice`() {
        backups.writeOk = false
        vm.startExport("p1", "Calle Mayor 12")
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PackageExportDialogs(state, {}, {}, {}, vm::dismissMessage) }
        }

        runBlocking { vm.writePackage(ByteArrayOutputStream()) } shouldBe false
        vm.exportDone(false)

        compose.onNodeWithText("No se ha podido exportar el paquete.").assertIsDisplayed()
    }

    @Test
    fun `S013 RF-24 cancelar el selector no dice nada`() {
        vm.startExport("p1", "Calle Mayor 12")
        vm.startWriting()

        vm.exportDone(null)

        vm.state.value shouldBe PackageUiState()
    }

    @Test
    fun `S013 RF-24 compartir ofrece el paquete con su tipo`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "Calle Mayor 12_20261019-0802.topoproj").apply { writeText("paquete") }
        val given = Uri.parse("content://${context.packageName}.fileprovider/exports/${Uri.encode(file.name)}")

        val chooser = shareExportIntent(context, file, given, mimeType = PACKAGE_MIME, chooserTitle = R.string.package_share_chooser)

        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java).shouldNotBeNull()
        send.type shouldBe "application/octet-stream"
        chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString() shouldBe "Compartir paquete"
    }
}
