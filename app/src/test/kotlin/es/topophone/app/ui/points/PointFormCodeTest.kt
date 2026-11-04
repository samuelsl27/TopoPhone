package es.topophone.app.ui.points

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.model.codes.AttributeError
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Corregir los valores de los atributos de un punto con rastro en el historial (S024/RF-12). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PointFormCodeTest {

    private val es = Locale.forLanguageTag("es-ES")
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val code = PointCode(
        "ARQ",
        "Arqueta",
        0x1E88E5,
        CodeSymbol.SQUARE,
        listOf(
            PointAttribute("Material", AttributeType.LIST, "PVC"),
            PointAttribute("Unidades", AttributeType.INTEGER, "3"),
            PointAttribute("Diametro", AttributeType.NUMBER, "0.25"),
        ),
    )
    private val repository = FakePointRepository(listOf(measured("12", projectId = projectId).copy(code = code)), clock = { 9_000 })
    private lateinit var vm: PointFormViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = PointFormViewModel(repository, projects) { ProjectPositionCalculator() }
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun await(predicate: (PointFormUiState) -> Boolean): PointFormUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    @Test
    fun `S024 RF-12 el formulario de un punto con codigo trae sus atributos con la coma de la interfaz`() {
        vm.start(projectId, "id-12", es)

        val state = await { it.loaded }

        state.codeName shouldBe "ARQ"
        state.attributes.map { it.text } shouldBe listOf("PVC", "3", "0,25")
    }

    @Test
    fun `S024 RF-12 corregir un atributo guarda el punto y deja los valores anteriores en el historial`() {
        vm.start(projectId, "id-12", es)
        await { it.loaded }

        vm.setAttribute(2, "0,3")
        await { it.canSave && it.attributes[2].text == "0,3" }
        vm.save()

        runBlocking { withTimeout(5_000) { vm.events.first() } }
        val point = repository.points.value.single { it.id == "id-12" }
        point.code?.attributes?.map { it.value } shouldBe listOf("PVC", "3", "0.3")
        point.code?.code shouldBe "ARQ"
        val entry = repository.historyEntries.value.single()
        entry.change shouldBe PointChange.EDIT
        entry.previous.code shouldBe code
    }

    @Test
    fun `S024 RF-12 un valor no valido se marca y no deja guardar`() {
        vm.start(projectId, "id-12", es)
        await { it.loaded }

        vm.setAttribute(1, "tres")

        val state = await { it.attributes.getOrNull(1)?.text == "tres" }
        state.attributes[1].error shouldBe AttributeError.NOT_INTEGER
        state.canSave shouldBe false
    }

    @Test
    fun `S024 RF-12 sin tocar nada no se guarda ningun cambio`() {
        vm.start(projectId, "id-12", es)
        await { it.loaded }

        vm.save()

        runBlocking { withTimeout(5_000) { vm.events.first() } }
        repository.historyEntries.value.size shouldBe 0
    }
}
