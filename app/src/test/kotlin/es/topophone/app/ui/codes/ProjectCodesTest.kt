package es.topophone.app.ui.codes

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.projects.ProjectListActions
import es.topophone.app.ui.projects.ProjectListScreen
import es.topophone.app.ui.projects.ProjectListUiState
import es.topophone.app.ui.projects.projectMenuTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.codes.ProjectLibraryLinker
import es.topophone.data.codes.RoomCodeLibraryRepository
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
import org.robolectric.annotation.Config

/** Consultar los códigos de la biblioteca que lleva el proyecto, desde el menú del proyecto (S023/RF-20). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectCodesTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val general = CodeLibrary(
        "default:general",
        "Topografía general",
        true,
        listOf(
            SurveyCode("g1", "VER", "Vértice", CodeGeometry.POINT, 0x1E88E5),
            SurveyCode("g2", "ARB", "Árbol", CodeGeometry.POINT, 0xE64A19),
        ),
    )
    private val repository = RoomCodeLibraryRepository(db, { listOf(general) })
    private val projects = RoomProjectRepository(db, libraries = ProjectLibraryLinker(db, { listOf(general) }))
    private val viewModels = mutableListOf<CodeLibraryViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun project(choice: LibraryChoice): String = runBlocking {
        (projects.create(ProjectDefaults().toDraft().copy(name = "Camino", libraryChoice = choice)) as SaveResult.Saved).id
    }

    private fun CodeLibraryViewModel.await(predicate: (CodeLibraryUiState) -> Boolean): CodeLibraryUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private fun viewModel() = CodeLibraryViewModel(repository).also { viewModels += it }

    private fun showScreen(state: CodeLibraryUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { CodeLibraryScreen(ThemeState.DEFAULT, {}, state, CodeLibraryActions(), {}) }
        }
    }

    @Test
    fun `S023 RF-20 el menu de un proyecto de la lista ofrece Codigos del proyecto`() {
        var opened: String? = null
        val listed = Project(
            "p1", "Camino", "", "", ProjectCrs.ETRS89_UTM30, LinearUnit.METRE, AngularUnit.DMS, CorrectionsDatum.ETRS89,
            null, 0, 0, null, null,
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {
                    },
                    ProjectListUiState(projects = listOf(listed)),
                    ProjectListActions(onProjectCodes = {
                        opened =
                            it
                    }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Códigos del proyecto").performScrollTo().assertIsDisplayed().performClick()

        opened shouldBe "p1"
    }

    @Test
    fun `S023 RF-20 se consultan los codigos de la copia del proyecto y no se pueden editar`() {
        val vm = viewModel()
        vm.openProject(project(LibraryChoice.Library("default:general")))

        val state = vm.await { it.library != null }

        state.projectCopy shouldBe true
        state.editable shouldBe false
        state.library?.name shouldBe "Topografía general"
        state.codes.map { it.code } shouldBe listOf("ARB", "VER")
    }

    @Test
    fun `S023 RF-20 la copia tambien se busca`() {
        val vm = viewModel()
        vm.openProject(project(LibraryChoice.Library("default:general")))
        vm.await { it.library != null }

        vm.search("vert")

        vm.await { it.query == "vert" && it.codes.size == 1 }.codes.single().code shouldBe "VER"
    }

    @Test
    fun `S023 RF-19 un proyecto sin biblioteca vinculada se abre vacio y lo dice`() {
        val vm = viewModel()
        vm.openProject(project(LibraryChoice.Keep))

        val state = vm.await { it.loaded }

        state.library shouldBe null
        state.projectCopy shouldBe true
    }

    @Test
    fun `S023 RF-20 la pantalla de la copia avisa de que es de consulta y no ofrece anadir ni importar`() {
        showScreen(
            CodeLibraryUiState(
                loaded = true,
                library = CodeLibrary("c1", "Mi biblioteca", false, general.codes),
                codes = general.codes,
                projectCopy = true,
            ),
        )

        compose.onNodeWithTag(CODE_READONLY_TAG).assertIsDisplayed()
        compose.onNodeWithText("Copia de la biblioteca que lleva el proyecto: solo se puede consultar.").assertIsDisplayed()
        compose.onNodeWithTag(CODE_EXPORT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CODE_ADD_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CODE_IMPORT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S023 RF-19 la pantalla de un proyecto sin biblioteca lo dice`() {
        showScreen(CodeLibraryUiState(loaded = true, library = null, projectCopy = true))

        compose.onNodeWithText("Este proyecto no tiene ninguna biblioteca de códigos vinculada.").assertIsDisplayed()
    }
}
