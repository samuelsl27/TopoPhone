package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.ProjectDraft
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
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

/** Formulario de proyecto nuevo (S006/RF-5 a RF-12). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectFormTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var now = 1_000L
    private val repository = RoomProjectRepository(db, clock = { now })
    private val defaults = ProjectDefaults(author = "S. S.", crs = ProjectCrs.ETRS89_UTM31, linearUnit = LinearUnit.INTERNATIONAL_FOOT)

    private class FakeProjectPreferences(initial: ProjectDefaults) : ProjectPreferences {
        override val defaults = MutableStateFlow(initial)

        override suspend fun saveDefaults(defaults: ProjectDefaults) {
            this.defaults.value = defaults
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    @After
    fun tearDown() {
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ProjectFormViewModel(repository, FakeProjectPreferences(defaults), session)

    private fun ProjectFormViewModel.awaitState(predicate: (ProjectFormUiState) -> Boolean): ProjectFormUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private fun show(state: ProjectFormUiState, actions: ProjectFormActions = ProjectFormActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ProjectFormScreen(ThemeState.DEFAULT, {}, state, actions) }
        }
    }

    private val draft = defaults.toDraft().copy(name = "Calle Mayor 12")

    @Test
    fun `S006 RF-5 el formulario tiene nombre, autor, descripcion, SC, unidades y datum de las correcciones`() {
        show(ProjectFormUiState(draft = draft, loaded = true))

        listOf(FORM_NAME_TAG, FORM_AUTHOR_TAG, FORM_DESCRIPTION_TAG).forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
        }
        // Desde S008 se ven las proyecciones del datum elegido (ProjectFormCrsTest).
        ProjectCrs.entries.filter { it.datum == draft.crs.datum }.forEach {
            compose.onNodeWithTag(crsOptionTag(it)).performScrollTo().assertIsDisplayed()
        }
        LinearUnit.entries.forEach { compose.onNodeWithTag(linearOptionTag(it)).performScrollTo().assertIsDisplayed() }
        AngularUnit.entries.forEach { compose.onNodeWithTag(angularOptionTag(it)).performScrollTo().assertIsDisplayed() }
        CorrectionsDatum.entries.forEach { compose.onNodeWithTag(datumOptionTag(it)).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag(crsOptionTag(ProjectCrs.ETRS89_UTM31)).assertIsSelected()
    }

    @Test
    fun `S006 RF-6 el formulario nuevo parte de los valores por defecto de Ajustes, sin nombre`() {
        val vm = viewModel()
        vm.start(projectId = null, folderId = null)

        vm.awaitState { it.loaded }.draft shouldBe defaults.toDraft()
    }

    @Test
    fun `S006 RF-7 sin nombre o con un nombre repetido no se guarda y se dice por que`() {
        runBlocking { repository.create(draft) }
        val vm = viewModel()
        vm.start(projectId = null, folderId = null)
        vm.awaitState { it.loaded }

        vm.save()
        vm.awaitState { it.error != null }.error shouldBe NameError.EMPTY

        vm.update(draft.copy(name = "calle mayor 12"))
        vm.save()
        vm.awaitState { it.error == NameError.DUPLICATE }.savedId shouldBe null
    }

    @Test
    fun `S006 RF-7 los motivos se muestran en el formulario`() {
        show(ProjectFormUiState(draft = draft, loaded = true, error = NameError.DUPLICATE))
        compose.onNodeWithText(text(R.string.project_name_duplicate)).assertIsDisplayed()
    }

    @Test
    fun `S006 RF-8 S006 RF-9 S006 RF-10 elegir una opcion cambia el formulario`() {
        var changed: ProjectDraft? = null
        show(ProjectFormUiState(draft = draft, loaded = true), ProjectFormActions(onChange = { changed = it }))

        compose.onNodeWithTag(crsOptionTag(ProjectCrs.ETRS89_UTM29)).performScrollTo().performClick()
        changed?.crs shouldBe ProjectCrs.ETRS89_UTM29
        compose.onNodeWithTag(linearOptionTag(LinearUnit.US_SURVEY_FOOT)).performScrollTo().performClick()
        changed?.linearUnit shouldBe LinearUnit.US_SURVEY_FOOT
        compose.onNodeWithTag(angularOptionTag(AngularUnit.GON)).performScrollTo().performClick()
        changed?.angularUnit shouldBe AngularUnit.GON
        compose.onNodeWithTag(datumOptionTag(CorrectionsDatum.REGCAN95)).performScrollTo().performClick()
        changed?.correctionsDatum shouldBe CorrectionsDatum.REGCAN95
        compose.onNodeWithTag(FORM_NAME_TAG).performTextReplacement("Parcela 23")
        changed?.name shouldBe "Parcela 23"
    }

    @Test
    fun `S006 RF-11 con correcciones en ITRF-WGS84 se avisa de la diferencia con ETRS89`() {
        show(ProjectFormUiState(draft = draft.copy(correctionsDatum = CorrectionsDatum.ITRF_WGS84), loaded = true))

        compose.onNodeWithTag(DATUM_WARNING_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.project_datum_warning)).assertIsDisplayed()
    }

    @Test
    fun `S006 RF-11 con correcciones en ETRS89 no hay aviso`() {
        show(ProjectFormUiState(draft = draft, loaded = true))

        compose.onNodeWithTag(DATUM_WARNING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S006 RF-12 al guardar un proyecto valido se guarda y se abre`() {
        val vm = viewModel()
        vm.start(projectId = null, folderId = null)
        vm.awaitState { it.loaded }
        now = 7_000

        vm.update(draft)
        vm.save()

        val id = vm.awaitState { it.savedId != null }.savedId.shouldNotBeNull()
        val project = runBlocking { repository.project(id) }.shouldNotBeNull()
        project.toDraft() shouldBe draft
        project.openedAtMs shouldBe 7_000
    }

    @Test
    fun `S006 RF-12 pulsar Guardar pide guardar`() {
        var saved = 0
        show(ProjectFormUiState(draft = draft, loaded = true), ProjectFormActions(onSave = { saved++ }))

        compose.onNodeWithTag(FORM_SAVE_TAG).performScrollTo().performClick()

        saved shouldBe 1
    }

    private fun saved(name: String): String = runBlocking {
        (repository.create(draft.copy(name = name)) as es.topophone.data.project.SaveResult.Saved).id
    }

    @Test
    fun `S006 RF-14 al editar el formulario parte de los datos del proyecto`() {
        val id = saved("Calle Mayor 12")
        val vm = viewModel()

        vm.start(projectId = id, folderId = null)

        val state = vm.awaitState { it.loaded }
        state.editing shouldBe true
        state.draft shouldBe draft.copy(name = "Calle Mayor 12")
    }

    @Test
    fun `S006 RF-14 se pueden cambiar todos los datos con las mismas comprobaciones`() {
        val id = saved("Calle Mayor 12")
        saved("Parcela 23")
        val vm = viewModel()
        vm.start(projectId = id, folderId = null)
        vm.awaitState { it.loaded }

        vm.update(draft.copy(name = "PARCELA 23"))
        vm.save()
        vm.awaitState { it.error == NameError.DUPLICATE }

        val changed = draft.copy(name = "Calle Mayor 14", author = "Otro", linearUnit = LinearUnit.METRE, angularUnit = AngularUnit.GON)
        vm.update(changed)
        vm.save()

        vm.awaitState { it.savedId == id }
        runBlocking { repository.project(id) }.shouldNotBeNull().toDraft() shouldBe changed
    }

    @Test
    fun `S006 RF-15 cambiar el SC del proyecto se guarda sin cambiar su identidad`() {
        val id = saved("Calle Mayor 12")
        val vm = viewModel()
        vm.start(projectId = id, folderId = null)
        vm.awaitState { it.loaded }

        vm.update(vm.state.value.draft.copy(crs = ProjectCrs.ETRS89_UTM29))
        vm.save()

        vm.awaitState { it.savedId == id }
        runBlocking { repository.project(id) }.shouldNotBeNull().crs shouldBe ProjectCrs.ETRS89_UTM29
    }

    @Test
    fun `S006 RF-14 el titulo dice Editar proyecto`() {
        show(ProjectFormUiState(draft = draft, loaded = true, editing = true))

        compose.onNodeWithText(text(R.string.project_edit)).assertIsDisplayed()
    }

    @Test
    fun `S006 RF-14 la vista de proyecto ofrece editarlo`() {
        val id = saved("Calle Mayor 12")
        val project = runBlocking { repository.project(id) }.shouldNotBeNull()
        var edited: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(project), onBack = {}, onEdit = { edited = it })
            }
        }

        compose.onNodeWithTag(PROJECT_EDIT_TAG).performClick()

        edited shouldBe id
    }
}
