package es.topophone.app.ui.codes

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.CodePalette
import es.topophone.core.model.codes.SurveyCode
import es.topophone.data.codes.LibraryResult
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.nulls.shouldNotBeNull
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
import org.junit.Test
import org.junit.runner.RunWith

/** El editor de un código: alta, edición, color, atributos, rechazos y borrado (S023/RF-5 a RF-10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CodeEditorViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val general = CodeLibrary("default:general", "General", true, listOf(SurveyCode("g1", "VER", "", CodeGeometry.POINT, 1)))
    private val repository = RoomCodeLibraryRepository(db, { listOf(general) }, clock = { 1L })
    private val viewModels = mutableListOf<CodeEditorViewModel>()
    private val library: String = runBlocking { (repository.create("Mía") as LibraryResult.Saved).id }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        db.close()
        Dispatchers.resetMain()
    }

    private fun editor(codeId: String? = null, libraryId: String = library) = CodeEditorViewModel(repository).also {
        viewModels += it
        it.open(libraryId, codeId)
    }

    private fun CodeEditorViewModel.await(predicate: (CodeEditorUiState) -> Boolean): CodeEditorUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private fun stored(): List<SurveyCode> = runBlocking { repository.library(library).first().shouldNotBeNull().codes }

    @Test
    fun `S023 RF-5 un codigo nuevo con descripcion y geometria se guarda y cierra el editor`() {
        val vm = editor().also { it.await { s -> s.loaded } }

        vm.setCode(" ARQ ")
        vm.setDescription(" Arqueta ")
        vm.setGeometry(CodeGeometry.LINE)
        vm.save()

        vm.await { it.finished }
        stored().single().let {
            it.code shouldBe "ARQ"
            it.description shouldBe "Arqueta"
            it.geometry shouldBe CodeGeometry.LINE
        }
    }

    @Test
    fun `S023 RF-8 sin color elegido toma el de su geometria y con uno elegido, ese`() {
        val a = editor().also { it.await { s -> s.loaded } }
        a.setCode("A")
        a.setGeometry(CodeGeometry.POLYGON)
        a.save()
        a.await { it.finished }
        val b = editor().also { it.await { s -> s.loaded } }
        b.setCode("B")
        b.setColor(CodePalette.COLORS[3])
        b.save()
        b.await { it.finished }

        stored().associate { it.code to it.color } shouldBe mapOf(
            "A" to CodePalette.defaultFor(CodeGeometry.POLYGON),
            "B" to CodePalette.COLORS[3],
        )
    }

    @Test
    fun `S023 RF-7 un codigo vacio o largo no se guarda y se indica el motivo hasta que se edita`() {
        val vm = editor().also { it.await { s -> s.loaded } }

        vm.save()
        vm.await { it.error == CodeError.Empty }
        vm.setCode("A".repeat(33))
        vm.await { it.error == null }
        vm.save()
        vm.await { it.error == CodeError.TooLong }

        stored() shouldBe emptyList()
    }

    @Test
    fun `S023 RF-6 un codigo repetido con otra mayuscula no se guarda y se indica el que choca`() {
        runBlocking { repository.saveCode(library, SurveyCode("", "Árbol", "", CodeGeometry.POINT, 1)) }
        val vm = editor().also { it.await { s -> s.loaded } }

        vm.setCode("ARBOL")
        vm.save()

        vm.await { it.error == CodeError.Duplicate("Árbol") }
        stored().size shouldBe 1
    }

    @Test
    fun `S023 RF-9 los atributos se guardan con su tipo, opciones y valor por defecto, y la coma decimal vale`() {
        val vm = editor().also { it.await { s -> s.loaded } }
        vm.setCode("ARQ")
        vm.addAttribute()
        vm.setAttributeName(0, "Material")
        vm.setAttributeType(0, AttributeType.LIST)
        vm.addOption(0)
        vm.setOption(0, 0, "PVC")
        vm.addOption(0)
        vm.setOption(0, 1, "Hormigón")
        vm.setAttributeDefault(0, "PVC")
        vm.addAttribute()
        vm.setAttributeName(1, "Diámetro")
        vm.setAttributeType(1, AttributeType.NUMBER)
        vm.setAttributeDefault(1, "0,25")
        vm.addAttribute()
        vm.setAttributeName(2, "Plantas")
        vm.setAttributeType(2, AttributeType.INTEGER)
        vm.setAttributeDefault(2, "3")

        vm.save()

        vm.await { it.finished }
        stored().single().attributes shouldBe listOf(
            CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón")),
            CodeAttribute("Diámetro", AttributeType.NUMBER, "0.25"),
            CodeAttribute("Plantas", AttributeType.INTEGER, "3"),
        )
    }

    @Test
    fun `S023 RF-9 un valor por defecto que no vale para el tipo no guarda nada`() {
        val vm = editor().also { it.await { s -> s.loaded } }
        vm.setCode("ARQ")
        vm.addAttribute()
        vm.setAttributeName(0, "Plantas")
        vm.setAttributeType(0, AttributeType.INTEGER)
        vm.setAttributeDefault(0, "1,5")

        vm.save()

        vm.await { it.error == CodeError.DefaultValue(0) }
        stored() shouldBe emptyList()
    }

    @Test
    fun `S023 RF-9 una lista sin opciones no se guarda y cambiar de tipo borra el valor por defecto`() {
        val vm = editor().also { it.await { s -> s.loaded } }
        vm.setCode("ARQ")
        vm.addAttribute()
        vm.setAttributeName(0, "Material")
        vm.setAttributeDefault(0, "algo")
        vm.setAttributeType(0, AttributeType.LIST)
        vm.await { it.attributes[0].default.isEmpty() && it.attributes[0].type == AttributeType.LIST }

        vm.save()

        vm.await { it.error == CodeError.ListOptions(0) }
    }

    @Test
    fun `S023 RF-9 si la opcion elegida por defecto se renombra, el valor por defecto la sigue y si se quita, se borra`() {
        val vm = editor().also { it.await { s -> s.loaded } }
        vm.addAttribute()
        vm.setAttributeType(0, AttributeType.LIST)
        vm.addOption(0)
        vm.setOption(0, 0, "A")
        vm.setAttributeDefault(0, "A")

        vm.setOption(0, 0, "AB")
        vm.await { it.attributes[0].default == "AB" }
        vm.removeOption(0, 0)

        vm.await { it.attributes[0].default.isEmpty() && it.attributes[0].options.isEmpty() }
    }

    @Test
    fun `S023 RF-10 los atributos se reordenan y se quitan`() {
        val vm = editor().also { it.await { s -> s.loaded } }
        repeat(3) { vm.addAttribute() }
        vm.setAttributeName(0, "A")
        vm.setAttributeName(1, "B")
        vm.setAttributeName(2, "C")

        vm.moveAttribute(2, -1)
        vm.await { s -> s.attributes.map { it.name } == listOf("A", "C", "B") }
        vm.moveAttribute(0, -1) // ya es el primero: no hace nada
        vm.moveAttribute(2, 1) // ya es el último: no hace nada
        vm.removeAttribute(1)

        vm.await { s -> s.attributes.map { it.name } == listOf("A", "B") }
    }

    @Test
    fun `S023 RF-5 editar un codigo parte de sus datos y los cambios se guardan con el mismo id`() {
        val id = runBlocking {
            val attribute = CodeAttribute("A", AttributeType.TEXT, "x")
            (
                repository.saveCode(
                    library,
                    SurveyCode("", "FAR", "Farola", CodeGeometry.POINT, CodePalette.COLORS[5], listOf(attribute)),
                ) as es.topophone.data.codes.CodeSaveResult.Saved
                ).id
        }
        val vm = editor(id)

        val loaded = vm.await { it.loaded }
        loaded.isNew shouldBe false
        loaded.code shouldBe "FAR"
        loaded.color shouldBe CodePalette.COLORS[5]
        loaded.attributes shouldBe listOf(AttributeDraft("A", AttributeType.TEXT, "x"))
        vm.setDescription("Farola de calle")
        vm.save()

        vm.await { it.finished }
        stored().single().let {
            it.id shouldBe id
            it.description shouldBe "Farola de calle"
        }
    }

    @Test
    fun `S023 RF-5 borrar un codigo pide confirmacion y lo quita`() {
        val id = runBlocking {
            (
                repository.saveCode(
                    library,
                    SurveyCode("", "FAR", "", CodeGeometry.POINT, 1),
                ) as es.topophone.data.codes.CodeSaveResult.Saved
                ).id
        }
        val vm = editor(id).also { it.await { s -> s.loaded } }

        vm.askDelete()
        vm.await { it.confirmDelete }
        vm.cancelDelete()
        vm.await { !it.confirmDelete }
        stored().size shouldBe 1
        vm.askDelete()
        vm.confirmDelete()

        vm.await { it.finished }
        stored() shouldBe emptyList()
    }

    @Test
    fun `S023 RF-3 una biblioteca por defecto o un codigo que no existe no se editan`() {
        editor(libraryId = "default:general").await { it.loaded }.available shouldBe false
        editor(codeId = "no-existe").await { it.loaded }.available shouldBe false
        editor(libraryId = "no-existe").await { it.loaded }.available shouldBe false
    }
}
