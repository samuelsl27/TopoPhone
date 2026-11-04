package es.topophone.app.ui.codes

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.CollectorCodeChoice
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.codes.DefaultLibrarySource
import es.topophone.data.codes.ProjectLibraryLinker
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import org.junit.Test
import org.junit.runner.RunWith

/** El editor de los codigos creados en el proyecto, desde el recolector (S024/RF-5, RF-6, RF-7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectCodeEditorTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val general = CodeLibrary("default:general", "General", true, listOf(SurveyCode("g1", "ARB", "Arbol", CodeGeometry.POINT, 1)))
    private val defaults = DefaultLibrarySource { listOf(general) }
    private val repository = RoomCodeLibraryRepository(db, defaults, clock = { 1L })
    private val projects = RoomProjectRepository(db, libraries = ProjectLibraryLinker(db, defaults))
    private val choice = CollectorCodeChoice()
    private val viewModels = mutableListOf<CodeEditorViewModel>()
    private val project: String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle", libraryChoice = LibraryChoice.Library("default:general")))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun editor(codeId: String? = null) = CodeEditorViewModel(repository, choice).also {
        viewModels += it
        it.openProject(project, codeId)
    }

    private fun CodeEditorViewModel.await(predicate: (CodeEditorUiState) -> Boolean): CodeEditorUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private fun projectCodes() = runBlocking { repository.projectCodes(project).first() }

    @Test
    fun `S024 RF-5 el editor del proyecto es de geometria Punto, con simbolo y atributos, y guarda solo en el proyecto`() {
        val vm = editor().also { it.await { s -> s.loaded } }
        vm.state.value.forProject shouldBe true
        vm.state.value.symbol shouldBe CodeSymbol.CIRCLE

        vm.setCode("POS")
        vm.setDescription("Poste")
        vm.setSymbol(CodeSymbol.DIAMOND)
        vm.addAttribute()
        vm.setAttributeName(0, "Altura")
        vm.setAttributeType(0, AttributeType.NUMBER)
        vm.setAttributeDefault(0, "7,5")
        vm.save()

        vm.await { it.finished }
        val saved = projectCodes().single()
        saved.code shouldBe "POS"
        saved.geometry shouldBe CodeGeometry.POINT
        saved.symbol shouldBe CodeSymbol.DIAMOND
        saved.attributes.single().defaultValue shouldBe "7.5"
        runBlocking { repository.libraries().first() }.map { it.id } shouldBe listOf("default:general")
    }

    @Test
    fun `S024 RF-5 al crear un codigo el recolector lo elige al volver`() {
        val vm = editor().also { it.await { s -> s.loaded } }
        vm.setCode("pos")

        vm.save()

        vm.await { it.finished }
        choice.key.value shouldBe "pos"
    }

    @Test
    fun `S024 RF-6 un codigo que ya existe no se guarda, se indica el conflicto y se puede usar el existente`() {
        val vm = editor().also { it.await { s -> s.loaded } }
        vm.setCode("arb")

        vm.save()

        vm.await { it.error != null }.error shouldBe CodeError.Duplicate("ARB")
        projectCodes().shouldBeEmpty()
        vm.useExisting()
        vm.await { it.finished }
        choice.key.value shouldBe "arb"
    }

    @Test
    fun `S024 RF-7 editar un codigo del proyecto lo cambia y no elige otro`() {
        val first = editor().also { it.await { s -> s.loaded } }
        first.setCode("POS")
        first.save()
        first.await { it.finished }
        choice.key.value = null
        val id = projectCodes().single().id

        val vm = editor(id).also { it.await { s -> s.loaded && !s.isNew } }
        vm.setDescription("Poste de luz")
        vm.setSymbol(CodeSymbol.STAR)
        vm.save()

        vm.await { it.finished }
        projectCodes().single().description shouldBe "Poste de luz"
        projectCodes().single().symbol shouldBe CodeSymbol.STAR
        choice.key.value shouldBe null
    }

    @Test
    fun `S024 RF-7 borrar un codigo del proyecto pide confirmacion y lo quita`() {
        val first = editor().also { it.await { s -> s.loaded } }
        first.setCode("POS")
        first.save()
        first.await { it.finished }
        val vm = editor(projectCodes().single().id).also { it.await { s -> s.loaded && !s.isNew } }

        vm.askDelete()
        vm.state.value.confirmDelete shouldBe true
        projectCodes().size shouldBe 1
        vm.confirmDelete()

        vm.await { it.finished }
        projectCodes().shouldBeEmpty()
    }

    @Test
    fun `S024 RF-7 un codigo que no es del proyecto no se puede abrir desde aqui`() {
        val vm = editor("g1")

        vm.await { it.loaded }.available shouldBe false
    }
}
