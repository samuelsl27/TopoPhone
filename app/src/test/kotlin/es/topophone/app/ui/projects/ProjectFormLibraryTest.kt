package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.codes.PROJECT_LIBRARY_CURRENT_TAG
import es.topophone.app.ui.codes.PROJECT_LIBRARY_NONE_TAG
import es.topophone.app.ui.codes.projectLibraryTag
import es.topophone.app.ui.map.FakeMapPreferences
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.data.codes.LibrarySummary
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

/** La biblioteca de códigos en el formulario de proyecto: elegir, mantener, cambiar y quitar con confirmación (S023/RF-19 a RF-21). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectFormLibraryTest {

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
        listOf(SurveyCode("g1", "VER", "", CodeGeometry.POINT, 1)),
    )
    private val libraries = RoomCodeLibraryRepository(db, { listOf(general) })
    private val projects = RoomProjectRepository(db, libraries = es.topophone.data.codes.ProjectLibraryLinker(db, { listOf(general) }))
    private val viewModels = mutableListOf<ProjectFormViewModel>()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    private class FakePreferences(initial: ProjectDefaults) : ProjectPreferences {
        override val defaults = MutableStateFlow(initial)

        override suspend fun saveDefaults(defaults: ProjectDefaults) {
            this.defaults.value = defaults
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(defaults: ProjectDefaults = ProjectDefaults()) =
        ProjectFormViewModel(projects, FakePreferences(defaults), session, FakeMapPreferences(), libraries).also { viewModels += it }

    private fun ProjectFormViewModel.await(predicate: (ProjectFormUiState) -> Boolean): ProjectFormUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private val summaries = listOf(
        LibrarySummary("default:general", "Topografía general", true, 15),
        LibrarySummary("u1", "Mía", false, 2),
    )

    private fun show(state: ProjectFormUiState, onChange: (es.topophone.core.model.project.ProjectDraft) -> Unit = {}) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectFormScreen(ThemeState.DEFAULT, {}, state, ProjectFormActions(onChange = onChange))
            }
        }
    }

    // --- ViewModel ---

    @Test
    fun `S023 RF-19 un proyecto nuevo parte de la biblioteca de Ajustes y al guardarlo lleva su copia`() {
        val vm = viewModel(ProjectDefaults(codeLibraryId = "default:general"))
        vm.start(null, null)

        val state = vm.await { it.loaded }
        state.draft.libraryChoice shouldBe LibraryChoice.Library("default:general")
        state.libraries.map { it.id } shouldBe listOf("default:general")
        vm.update(state.draft.copy(name = "Camino"))
        vm.save()

        val saved = vm.await { it.savedId != null }.savedId.shouldNotBeNull()
        runBlocking { libraries.projectCopy(saved).first().shouldNotBeNull().codes.map { it.code } } shouldBe listOf("VER")
    }

    @Test
    fun `S023 RF-19 si la biblioteca de Ajustes ya no existe el proyecto nuevo no lleva ninguna`() {
        val vm = viewModel(ProjectDefaults(codeLibraryId = "borrada"))
        vm.start(null, null)

        vm.await { it.loaded }.draft.libraryChoice shouldBe LibraryChoice.Keep
    }

    @Test
    fun `S023 RF-19 sin biblioteca en Ajustes el proyecto nuevo no lleva ninguna`() {
        val vm = viewModel()
        vm.start(null, null)
        vm.await { it.loaded }
        vm.update(vm.state.value.draft.copy(name = "Camino"))
        vm.save()

        val saved = vm.await { it.savedId != null }.savedId.shouldNotBeNull()
        runBlocking { libraries.projectCopy(saved).first() } shouldBe null
    }

    @Test
    fun `S023 RF-21 al editar se ve la biblioteca que lleva el proyecto, y cambiarla o quitarla se aplica al guardar`() {
        val created = runBlocking {
            val draft = ProjectDefaults().toDraft().copy(name = "Camino", libraryChoice = LibraryChoice.Library("default:general"))
            (projects.create(draft) as es.topophone.data.project.SaveResult.Saved).id
        }
        val vm = viewModel()
        vm.start(created, null)

        val loaded = vm.await { it.loaded }
        loaded.linkedLibraryName shouldBe "Topografía general"
        loaded.draft.libraryChoice shouldBe LibraryChoice.Keep
        vm.update(loaded.draft.copy(libraryChoice = LibraryChoice.None))
        vm.save()

        vm.await { it.savedId != null }
        runBlocking { libraries.projectCopy(created).first() } shouldBe null
    }

    // --- Pantalla ---

    @Test
    fun `S023 RF-19 el formulario ofrece ninguna y cada biblioteca, con la elegida marcada`() {
        show(
            ProjectFormUiState(
                draft = ProjectDefaults().toDraft().copy(libraryChoice = LibraryChoice.Library("u1")),
                loaded = true,
                libraries = summaries,
            ),
        )

        compose.onNodeWithTag(PROJECT_LIBRARY_NONE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(projectLibraryTag("default:general")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Topografía general · Por defecto").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(projectLibraryTag("u1")).performScrollTo().assertIsSelected()
    }

    @Test
    fun `S023 RF-19 en un proyecto nuevo elegir una biblioteca no pide confirmacion`() {
        var chosen: LibraryChoice? = null
        show(ProjectFormUiState(loaded = true, libraries = summaries)) { chosen = it.libraryChoice }

        compose.onNodeWithTag(PROJECT_LIBRARY_NONE_TAG).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(projectLibraryTag("u1")).performScrollTo().performClick()

        chosen shouldBe LibraryChoice.Library("u1")
    }

    @Test
    fun `S023 RF-21 cambiar la biblioteca vinculada pide confirmacion y cancelar no cambia nada`() {
        var chosen: LibraryChoice? = null
        show(ProjectFormUiState(loaded = true, editing = true, libraries = summaries, linkedLibraryName = "Mía")) {
            chosen =
                it.libraryChoice
        }

        compose.onNodeWithTag(PROJECT_LIBRARY_CURRENT_TAG).performScrollTo().assertIsSelected()
        compose.onNodeWithText("Mantener la actual: Mía").assertIsDisplayed()
        compose.onNodeWithTag(projectLibraryTag("default:general")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.project_library_change_message, "Topografía general")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.dialog_cancel)).performClick()

        chosen shouldBe null
        compose.onNodeWithTag(PROJECT_LIBRARY_CURRENT_TAG).assertIsSelected()
    }

    @Test
    fun `S023 RF-21 confirmar el cambio elige la nueva biblioteca`() {
        var chosen: LibraryChoice? = null
        show(ProjectFormUiState(loaded = true, editing = true, libraries = summaries, linkedLibraryName = "Mía")) {
            chosen =
                it.libraryChoice
        }

        compose.onNodeWithTag(projectLibraryTag("default:general")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.project_library_change_confirm)).performClick()

        chosen shouldBe LibraryChoice.Library("default:general")
    }

    @Test
    fun `S023 RF-21 quitar la biblioteca vinculada pide confirmacion y dice que no se toca ningun punto`() {
        var chosen: LibraryChoice? = null
        show(ProjectFormUiState(loaded = true, editing = true, libraries = summaries, linkedLibraryName = "Mía")) {
            chosen =
                it.libraryChoice
        }

        compose.onNodeWithTag(PROJECT_LIBRARY_NONE_TAG).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.project_library_remove_message)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.project_library_remove_confirm)).performClick()

        chosen shouldBe LibraryChoice.None
    }

    @Test
    fun `S023 RF-21 volver a mantener la actual no pide confirmacion`() {
        var chosen: LibraryChoice? = null
        show(
            ProjectFormUiState(
                draft = ProjectDefaults().toDraft().copy(libraryChoice = LibraryChoice.None),
                loaded = true,
                editing = true,
                libraries = summaries,
                linkedLibraryName = "Mía",
            ),
        ) { chosen = it.libraryChoice }

        compose.onNodeWithTag(PROJECT_LIBRARY_CURRENT_TAG).performScrollTo().performClick()

        chosen shouldBe LibraryChoice.Keep
    }
}
