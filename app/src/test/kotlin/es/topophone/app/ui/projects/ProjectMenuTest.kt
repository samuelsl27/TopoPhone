package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.ProjectFolder
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Menú de cada proyecto y menú de la lista (S006/RF-16, RF-19). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = ProjectDefaults().toDraft()
    private val project = Project(
        id = "p1",
        name = "Parcela 23",
        author = "",
        description = "",
        crs = draft.crs,
        linearUnit = draft.linearUnit,
        angularUnit = draft.angularUnit,
        correctionsDatum = draft.correctionsDatum,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )
    private val folders = listOf(ProjectFolder("f1", "Fincas"), ProjectFolder("f2", "Obras 2026"))

    private fun show(actions: ProjectListActions) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(ThemeState.DEFAULT, {}, ProjectListUiState(folders = folders, projects = listOf(project)), actions)
            }
        }
    }

    @Test
    fun `S006 RF-16 el menu del proyecto ofrece duplicarlo`() {
        var duplicated: String? = null
        show(ProjectListActions(onDuplicate = { duplicated = it }))

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText(text(R.string.project_duplicate)).performClick()

        duplicated shouldBe "p1"
    }

    @Test
    fun `S006 RF-14 el menu del proyecto ofrece editarlo`() {
        var edited: String? = null
        show(ProjectListActions(onEdit = { edited = it }))

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText(text(R.string.project_edit)).performClick()

        edited shouldBe "p1"
    }

    @Test
    fun `S006 RF-19 mover a carpeta ofrece las carpetas y sacarlo de ellas`() {
        var moved: Pair<String, String?>? = null
        show(ProjectListActions(onMove = { id, folder -> moved = id to folder }))

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText(text(R.string.project_move)).performClick()
        compose.onNodeWithText(text(R.string.project_no_folder)).assertIsDisplayed()
        compose.onNodeWithTag(moveTargetTag("f2")).performClick()

        moved shouldBe ("p1" to "f2")
    }

    @Test
    fun `S006 RF-19 el menu de la lista tiene Nueva carpeta y Papelera`() {
        var newFolder = 0
        var trash = 0
        show(ProjectListActions(onNewFolder = { newFolder++ }, onOpenTrash = { trash++ }))

        compose.onNodeWithTag(LIST_MENU_TAG).performClick()
        compose.onNodeWithText(text(R.string.folder_new)).performClick()
        compose.onNodeWithTag(LIST_MENU_TAG).performClick()
        compose.onNodeWithText(text(R.string.trash_title)).performClick()

        newFolder shouldBe 1
        trash shouldBe 1
    }

    @Test
    fun `S006 RF-16 S006 RF-19 el ViewModel duplica con la palabra de la interfaz y mueve el proyecto`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            val repository = RoomProjectRepository(db)
            runBlocking {
                val id = (repository.create(draft.copy(name = "Parcela 23")) as SaveResult.Saved).id
                val folder = (repository.createFolder("Obras") as SaveResult.Saved).id
                val viewModel = ProjectListViewModel(repository)

                viewModel.duplicate(id, copyLabel = text(R.string.project_copy_label))
                viewModel.moveToFolder(id, folder)

                withTimeout(5_000) { repository.projects(folder).first { it.isNotEmpty() } }.map { it.name } shouldBe listOf("Parcela 23")
                withTimeout(5_000) { repository.projects(folderId = null).first { it.isNotEmpty() } }.map { it.name } shouldBe
                    listOf("Parcela 23 (copia)")
            }
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
