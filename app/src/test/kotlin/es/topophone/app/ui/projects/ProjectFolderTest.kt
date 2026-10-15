package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.ProjectFolder
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Carpetas de proyectos (S006/RF-17, RF-18, RF-20, RF-21). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectFolderTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var now = 1_000L
    private val repository = RoomProjectRepository(db, clock = { now })
    private val draft = ProjectDefaults().toDraft()
    private val obras = ProjectFolder("f1", "Obras 2026")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun <T> ProjectListViewModel.awaitState(predicate: (ProjectListUiState) -> Boolean, read: (ProjectListUiState) -> T): T =
        runBlocking {
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
            try {
                read(withTimeout(5_000) { state.first(predicate) })
            } finally {
                collector.cancel()
            }
        }

    private fun showList(state: ProjectListUiState, actions: ProjectListActions = ProjectListActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectListScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    @Test
    fun `S006 RF-17 el dialogo de nueva carpeta pide un nombre y lo guarda`() {
        var submitted: String? = null
        showList(
            ProjectListUiState(folders = listOf(obras), folderDialog = FolderDialogState(FolderDialog.Create)),
            ProjectListActions(onSubmitFolderName = { submitted = it }),
        )

        compose.onNodeWithTag(FOLDER_NAME_TAG).performTextReplacement("Fincas")
        compose.onNodeWithTag(FOLDER_SAVE_TAG).performClick()

        submitted shouldBe "Fincas"
    }

    @Test
    fun `S006 RF-17 una carpeta sin nombre o con un nombre repetido no se crea y se dice por que`() {
        val vm = ProjectListViewModel(repository)
        runBlocking { repository.createFolder("Obras 2026") }

        vm.openNewFolder()
        vm.submitFolderName(" ")
        vm.awaitState({ it.folderDialog?.error == NameError.EMPTY }) { it.folderDialog }
        vm.submitFolderName("obras 2026")
        vm.awaitState({ it.folderDialog?.error == NameError.DUPLICATE }) { it.folderDialog }

        vm.submitFolderName("Fincas")

        vm.awaitState({ it.folderDialog == null && it.folders.size == 2 }) { s -> s.folders.map { it.name } } shouldBe
            listOf("Fincas", "Obras 2026")
    }

    @Test
    fun `S006 RF-17 los motivos se muestran en el dialogo`() {
        showList(ProjectListUiState(folders = listOf(obras), folderDialog = FolderDialogState(FolderDialog.Create, NameError.DUPLICATE)))

        compose.onNodeWithText(text(R.string.folder_name_duplicate)).assertIsDisplayed()
    }

    @Test
    fun `S006 RF-18 renombrar una carpeta parte de su nombre y tiene las mismas comprobaciones`() {
        val vm = ProjectListViewModel(repository)
        val id = runBlocking { (repository.createFolder("Obras 2026") as SaveResult.Saved).id }
        runBlocking { repository.createFolder("Fincas") }

        vm.openRenameFolder(ProjectFolder(id, "Obras 2026"))
        vm.awaitState({ it.folderDialog != null }) { it.folderDialog?.dialog } shouldBe FolderDialog.Rename(id, "Obras 2026")
        vm.submitFolderName("FINCAS")
        vm.awaitState({ it.folderDialog?.error == NameError.DUPLICATE }) { it }
        vm.submitFolderName("Obras 2027")

        vm.awaitState({ it.folderDialog == null && it.folders.any { f -> f.name == "Obras 2027" } }) { true } shouldBe true
    }

    @Test
    fun `S006 RF-18 el menu de la carpeta ofrece renombrarla`() {
        var renamed: ProjectFolder? = null
        showList(ProjectListUiState(folders = listOf(obras)), ProjectListActions(onRenameFolder = { renamed = it }))

        compose.onNodeWithTag(folderMenuTag("f1")).performClick()
        compose.onNodeWithText(text(R.string.folder_rename)).performClick()

        renamed shouldBe obras
    }

    @Test
    fun `S006 RF-21 borrar una carpeta pide confirmacion`() {
        var deleted: String? = null
        showList(ProjectListUiState(folders = listOf(obras)), ProjectListActions(onDeleteFolder = { deleted = it }))

        compose.onNodeWithTag(folderMenuTag("f1")).performClick()
        compose.onNodeWithText(text(R.string.folder_delete)).performClick()
        compose.onNodeWithText(text(R.string.folder_delete_confirm, "Obras 2026")).assertIsDisplayed()
        deleted shouldBe null
        compose.onNodeWithTag(CONFIRM_TAG).performClick()

        deleted shouldBe "f1"
    }

    @Test
    fun `S006 RF-21 al borrar una carpeta sus proyectos quedan fuera de las carpetas`() {
        val vm = ProjectListViewModel(repository)
        val folder = runBlocking { (repository.createFolder("Obras 2026") as SaveResult.Saved).id }
        val project = runBlocking { (repository.create(draft.copy(name = "Parcela 23"), folder) as SaveResult.Saved).id }

        vm.deleteFolder(folder)

        vm.awaitState({ it.folders.isEmpty() && it.projects.size == 1 }) { s -> s.projects.single().id } shouldBe project
    }

    private fun project(id: String, name: String) = Project(
        id = id,
        name = name,
        author = "",
        description = "",
        crs = draft.crs,
        linearUnit = draft.linearUnit,
        angularUnit = draft.angularUnit,
        correctionsDatum = draft.correctionsDatum,
        folderId = "f1",
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    @Test
    fun `S006 RF-20 al abrir una carpeta se ven sus proyectos en orden y Nuevo proyecto crea dentro de ella`() {
        var newIn: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectFolderScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectFolderUiState(obras, listOf(project("p2", "Reciente"), project("p1", "Antiguo"))),
                    ProjectFolderActions(list = ProjectListActions(), onNewProject = { newIn = it }),
                )
            }
        }

        compose.onNodeWithText("Obras 2026").assertIsDisplayed()
        (
            compose.onNodeWithTag(
                projectTag("p2"),
            ).getBoundsInRoot().top < compose.onNodeWithTag(projectTag("p1")).getBoundsInRoot().top
            ) shouldBe
            true
        compose.onNodeWithTag(NEW_PROJECT_TAG).performClick()

        newIn shouldBe "f1"
    }

    @Test
    fun `S006 RF-20 el ViewModel de la carpeta da su nombre y sus proyectos del abierto mas recientemente al mas antiguo`() {
        val folder = runBlocking { (repository.createFolder("Obras 2026") as SaveResult.Saved).id }
        runBlocking {
            repository.create(draft.copy(name = "Antiguo"), folder)
            now = 2_000
            repository.create(draft.copy(name = "Reciente"), folder)
        }
        val vm = ProjectFolderViewModel(repository)
        vm.start(folder)

        val state = runBlocking {
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            try {
                withTimeout(5_000) { vm.state.first { it.projects.size == 2 } }
            } finally {
                collector.cancel()
            }
        }

        state.folder?.name shouldBe "Obras 2026"
        state.projects.map { it.name } shouldBe listOf("Reciente", "Antiguo")
    }
}
