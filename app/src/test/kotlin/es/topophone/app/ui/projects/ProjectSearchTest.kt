package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.ProjectFolder
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectSearchResult
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Búsqueda en la lista de proyectos (S006/RF-4). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectSearchTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = ProjectDefaults().toDraft().let {
        es.topophone.core.model.project.Project(
            id = "p1",
            name = "Camino de Ávila",
            author = it.author,
            description = "",
            crs = it.crs,
            linearUnit = it.linearUnit,
            angularUnit = it.angularUnit,
            correctionsDatum = it.correctionsDatum,
            folderId = "f1",
            createdAtMs = 1_000,
            modifiedAtMs = 1_000,
            openedAtMs = null,
            deletedAtMs = null,
        )
    }

    private fun show(state: ProjectListUiState, actions: ProjectListActions = ProjectListActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectListScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    @Test
    fun `S006 RF-4 escribir en la busqueda avisa del texto`() {
        var query = ""
        show(ProjectListUiState(projects = listOf(project)), ProjectListActions(onQueryChange = { query = it }))

        compose.onNodeWithTag(PROJECT_SEARCH_TAG).performTextInput("avila")

        query shouldBe "avila"
    }

    @Test
    fun `S006 RF-4 los resultados muestran la carpeta de cada proyecto y no las carpetas`() {
        val folder = ProjectFolder("f1", "Obras 2026")

        show(ProjectListUiState(folders = listOf(folder), query = "avila", results = listOf(ProjectSearchResult(project, folder))))

        compose.onNodeWithTag(
            projectTag("p1"),
        ).assert(hasText("Camino de Ávila")).assert(hasText(text(R.string.project_in_folder, "Obras 2026")))
        compose.onNodeWithTag(folderTag("f1")).assertDoesNotExist()
    }

    @Test
    fun `S006 RF-4 si ningun proyecto coincide se dice`() {
        show(ProjectListUiState(query = "zzz", results = emptyList()))

        compose.onNodeWithText(text(R.string.projects_search_empty, "zzz")).assertIsDisplayed()
    }

    @Test
    fun `S006 RF-4 el ViewModel busca en todas las carpetas sin mayusculas ni tildes`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            val repository = RoomProjectRepository(db)
            val state = runBlocking {
                val folder = (repository.createFolder("Obras 2026") as SaveResult.Saved).id
                val avila = (repository.create(ProjectDefaults().toDraft().copy(name = "Camino de Ávila")) as SaveResult.Saved).id
                repository.create(ProjectDefaults().toDraft().copy(name = "Parcela 23"))
                repository.moveToFolder(avila, folder)
                val viewModel = ProjectListViewModel(repository)
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { viewModel.state.collect {} }
                try {
                    viewModel.search("AVILA")
                    withTimeout(5_000) { viewModel.state.first { it.results?.size == 1 } }
                } finally {
                    collector.cancel()
                }
            }

            state.query shouldBe "AVILA"
            state.results!!.single().project.name shouldBe "Camino de Ávila"
            state.results!!.single().folder?.name shouldBe "Obras 2026"
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
