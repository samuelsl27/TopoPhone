package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
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
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectFolder
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import io.kotest.matchers.shouldBe
import java.util.Locale
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Lista de proyectos en Levantamiento (S006/RF-1, RF-2, RF-3). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectListTest {

    @get:Rule
    val compose = createComposeRule()

    private fun project(id: String, name: String, modifiedAt: Long = 1_792_080_000_000, crs: ProjectCrs = ProjectCrs.ETRS89_UTM30) =
        Project(
            id = id,
            name = name,
            author = "",
            description = "",
            crs = crs,
            linearUnit = LinearUnit.METRE,
            angularUnit = AngularUnit.DMS,
            correctionsDatum = CorrectionsDatum.ETRS89,
            folderId = null,
            createdAtMs = modifiedAt,
            modifiedAtMs = modifiedAt,
            openedAtMs = null,
            deletedAtMs = null,
        )

    private fun show(state: ProjectListUiState, actions: ProjectListActions = ProjectListActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectListScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot().top

    @Test
    fun `S006 RF-1 primero las carpetas y despues los proyectos, en el orden de la lista`() {
        val folders = listOf(ProjectFolder("f1", "Fincas"), ProjectFolder("f2", "Obras 2026"))
        val projects = listOf(project("p2", "Reciente"), project("p1", "Antiguo"))

        show(ProjectListUiState(folders = folders, projects = projects))

        val tops = listOf(folderTag("f1"), folderTag("f2"), projectTag("p2"), projectTag("p1")).map(::top)
        tops shouldBe tops.sorted()
        compose.onNodeWithTag(folderTag("f1")).assert(hasText("Fincas"))
    }

    @Test
    fun `S006 RF-2 cada proyecto muestra su nombre, su SC y su ultima modificacion`() {
        val modified = 1_792_080_000_000L
        show(ProjectListUiState(projects = listOf(project("p1", "Calle Mayor 12", modified, ProjectCrs.ETRS89_UTM31))))

        compose.onNodeWithTag(projectTag("p1"))
            .assert(hasText("Calle Mayor 12"))
            .assert(hasText(text(R.string.project_crs_utm, "ETRS89", 31)))
            .assert(hasText(text(R.string.project_modified, formatDateTime(modified, Locale.forLanguageTag("es-ES")))))
    }

    @Test
    fun `S006 RF-3 sin proyectos ni carpetas se dice y se ofrece crear uno`() {
        var created = 0
        show(ProjectListUiState(), ProjectListActions(onNewProject = { created++ }))

        compose.onNodeWithText(text(R.string.projects_empty_title)).assertIsDisplayed()
        compose.onNodeWithTag(EMPTY_NEW_PROJECT_TAG).performClick()
        compose.onNodeWithTag(NEW_PROJECT_TAG).performClick()

        created shouldBe 2
    }

    @Test
    fun `S006 RF-1 pulsar un proyecto lo abre y pulsar una carpeta la abre`() {
        var opened: String? = null
        var folder: String? = null
        show(
            ProjectListUiState(folders = listOf(ProjectFolder("f1", "Fincas")), projects = listOf(project("p1", "Calle Mayor 12"))),
            ProjectListActions(onOpenProject = { opened = it }, onOpenFolder = { folder = it }),
        )

        compose.onNodeWithTag(projectTag("p1")).performClick()
        compose.onNodeWithTag(folderTag("f1")).performClick()

        opened shouldBe "p1"
        folder shouldBe "f1"
    }

    @Test
    fun `S006 RF-1 el ViewModel da las carpetas y los proyectos que no estan en ninguna, del abierto mas recientemente al mas antiguo`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            var now = 1_000L
            val repository = RoomProjectRepository(db, clock = { now })
            val draft = es.topophone.core.model.project.ProjectDefaults().toDraft()
            val state = runBlocking {
                val folder = (repository.createFolder("Obras") as es.topophone.data.project.SaveResult.Saved).id
                repository.create(draft.copy(name = "Antiguo"))
                now = 2_000
                repository.create(draft.copy(name = "Reciente"))
                val inFolder = (repository.create(draft.copy(name = "Dentro")) as es.topophone.data.project.SaveResult.Saved).id
                repository.moveToFolder(inFolder, folder)
                val viewModel = ProjectListViewModel(repository)
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { viewModel.state.collect {} }
                try {
                    withTimeout(5_000) { viewModel.state.first { it.projects.size == 2 && it.folders.size == 1 } }
                } finally {
                    collector.cancel()
                }
            }

            state.folders.map { it.name } shouldBe listOf("Obras")
            state.projects.map { it.name } shouldBe listOf("Reciente", "Antiguo")
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
