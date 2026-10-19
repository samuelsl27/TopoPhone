package es.topophone.app.ui.backup

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.backup.FakePackageBackups
import es.topophone.app.ui.collector.MeasurementInProgress
import es.topophone.app.ui.projects.LIST_MENU_TAG
import es.topophone.app.ui.projects.ProjectListActions
import es.topophone.app.ui.projects.ProjectListScreen
import es.topophone.app.ui.projects.ProjectListUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupManifest
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.backup.BackupRead
import es.topophone.data.backup.ImportChoice
import es.topophone.data.backup.ImportResult
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Importar paquete" desde la lista de proyectos (S013/RF-12, RF-13, RF-16, RF-25 a RF-28). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PackageImportTest {

    @get:Rule
    val compose = createComposeRule()

    private val backups = FakePackageBackups()
    private val measuring = MeasurementInProgress()
    private lateinit var vm: PackageViewModel
    private val valid = BackupRead.Valid(
        BackupManifest(BackupKind.PACKAGE, 6, "0.13.0", Instant.EPOCH, 1, 4, projectId = "p1", projectName = "Calle Mayor 12"),
        File("data.db"),
        settings = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = PackageViewModel(backups, measuring)
        backups.readResult = valid
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun open() = vm.openPackage({ "zip".byteInputStream() }, label = "importado")

    @Test
    fun `S013 RF-25 el menu de la lista ofrece importar un paquete`() {
        var opened = false
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(ThemeState.DEFAULT, {}, ProjectListUiState(), ProjectListActions(onImportPackage = { opened = true }))
            }
        }

        compose.onNodeWithTag(LIST_MENU_TAG).performClick()
        compose.onNodeWithText("Importar paquete").performClick()

        opened shouldBe true
    }

    @Test
    fun `S013 RF-25 un paquete nuevo se importa y se dice`() {
        backups.importResults += ImportResult.Imported("p1", "Calle Mayor 12")

        open()

        backups.readKind shouldBe BackupKind.PACKAGE
        backups.imports shouldBe listOf(null)
        vm.state.value.message shouldBe PackageMessage.IMPORTED
        vm.state.value.messageArg shouldBe "Calle Mayor 12"
    }

    @Test
    fun `S013 RF-12 y RF-13 un fichero que no vale se dice por que`() {
        val cases = mapOf(
            BackupRead.NotTopoPhone to PackageMessage.NOT_TOPOPHONE,
            BackupRead.WrongKind(BackupKind.BACKUP) to PackageMessage.IS_BACKUP,
            BackupRead.Damaged to PackageMessage.DAMAGED,
            BackupRead.Newer to PackageMessage.NEWER,
        )
        for ((read, message) in cases) {
            backups.readResult = read
            open()
            vm.state.value.message shouldBe message
        }
        backups.imports shouldBe emptyList()
    }

    @Test
    fun `S013 RF-26 y RF-28 si ya esta se pregunta, e importado como nuevo cambia de nombre`() {
        backups.importResults += ImportResult.NeedsChoice("Calle Mayor 12")
        backups.importResults += ImportResult.Imported("p2", "Calle Mayor 12 (importado)")

        open()
        vm.state.value.question shouldBe "Calle Mayor 12"
        vm.answer(ImportChoice.AS_NEW)

        backups.imports shouldBe listOf(null, ImportChoice.AS_NEW)
        vm.state.value.question shouldBe null
        vm.state.value.messageArg shouldBe "Calle Mayor 12 (importado)"
    }

    @Test
    fun `S013 RF-27 sustituir importa el del paquete`() {
        backups.importResults += ImportResult.NeedsChoice("Calle Mayor 12")
        backups.importResults += ImportResult.Imported("p1", "Calle Mayor 12")

        open()
        vm.answer(ImportChoice.REPLACE)

        backups.imports shouldBe listOf(null, ImportChoice.REPLACE)
        vm.state.value.message shouldBe PackageMessage.IMPORTED
    }

    @Test
    fun `S013 RF-16 con una medida en curso no se sustituye`() {
        backups.importResults += ImportResult.NeedsChoice("Calle Mayor 12")
        open()
        measuring.set(true)

        vm.answer(ImportChoice.REPLACE)

        backups.imports shouldBe listOf(null)
        vm.state.value.message shouldBe PackageMessage.MEASURING
    }

    @Test
    fun `S013 RF-25 si no se puede anadir se dice`() {
        backups.importResults += ImportResult.Failed

        open()

        vm.state.value.message shouldBe PackageMessage.NOT_IMPORTED
    }

    @Test
    fun `S013 RF-26 la pregunta ofrece sustituir o importar como nuevo`() {
        backups.importResults += ImportResult.NeedsChoice("Calle Mayor 12")
        val answers = mutableListOf<ImportChoice>()
        open()
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PackageDialogs(state, PackageDialogActions(onAnswer = { answers += it })) }
        }

        compose.onNodeWithText("Calle Mayor 12 ya está en el móvil", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(PACKAGE_REPLACE_TAG).performClick()
        compose.onNodeWithTag(PACKAGE_AS_NEW_TAG).performClick()

        answers shouldBe listOf(ImportChoice.REPLACE, ImportChoice.AS_NEW)
    }

    @Test
    fun `S013 RF-25 se ve el nombre del proyecto importado`() {
        backups.importResults += ImportResult.Imported("p2", "Calle Mayor 12 (importado)")
        open()
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            TopoPhoneTheme(EffectiveTheme.LIGHT) { PackageDialogs(state, PackageDialogActions()) }
        }

        compose.onNodeWithText("Proyecto importado: Calle Mayor 12 (importado).").assertIsDisplayed()
    }
}
