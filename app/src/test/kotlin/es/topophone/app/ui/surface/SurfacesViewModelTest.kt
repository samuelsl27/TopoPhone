package es.topophone.app.ui.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTile
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceDraft
import es.topophone.data.surface.SurfaceSummary
import es.topophone.data.surface.SurfaceTileWrite
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** La lista de superficies del proyecto: activar, borrar y el desplazamiento vertical (S042/RF-16 a RF-20). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SurfacesViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val surfaces = RoomSurfaceRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private var projectCount = 0

    private fun newProject(unit: LinearUnit = LinearUnit.METRE): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Proyecto ${projectCount++}", linearUnit = unit))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val projectId = newProject()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private val extent = SurfaceExtent(440_000.0, 440_100.0, 4_474_000.0, 4_474_100.0)
    private val tile = SurfaceTile(
        doubleArrayOf(440_000.0, 440_010.0, 440_000.0),
        doubleArrayOf(4_474_000.0, 4_474_000.0, 4_474_010.0),
        doubleArrayOf(600.0, 601.0, 602.0),
        intArrayOf(0, 1, 2),
    )

    private fun write(id: String, project: String = projectId, at: Long = 1_000L) = runBlocking {
        surfaces.begin(SurfaceDraft(id, project, "terreno $id", "obra.xml", at, CadSourceCrs.Project, LinearUnit.METRE))
        surfaces.addTiles(id, listOf(SurfaceTileWrite(extent, 1, SurfaceTileCodec.encode(tile))))
        surfaces.finish(id, SurfaceSummary(extent, 600.0, 602.0, 3, 1, 0))
    }

    private fun open(project: String = projectId): SurfacesViewModel {
        val vm = SurfacesViewModel(surfaces, projects, dispatcher)
        vm.start(project)
        return vm
    }

    /** Espera a que el estado cumpla [condition]: Room contesta desde otro hilo. */
    private fun SurfacesViewModel.until(condition: (SurfacesUiState) -> Boolean): SurfacesUiState = runBlocking {
        withTimeout(10_000) {
            while (!condition(state.value)) delay(10)
            state.value
        }
    }

    @Test
    fun `S042 RF-16 la lista muestra las superficies del proyecto con la activa`() {
        write("s1")
        write("s2", at = 2_000L)

        val state = open().until { it.surfaces.size == 2 && it.activeId != null }

        state.surfaces.map { it.id } shouldBe listOf("s1", "s2")
        state.activeId shouldBe "s1"
        state.offsetM shouldBe 0.0
        state.unit shouldBe LinearUnit.METRE
    }

    @Test
    fun `S042 RF-16 activar otra superficie o ninguna cambia la activa en un toque`() {
        write("s1")
        write("s2", at = 2_000L)
        val vm = open()
        vm.until { it.activeId == "s1" }

        vm.activate("s2")
        vm.until { it.activeId == "s2" }.activeId shouldBe "s2"
        vm.activate(null)
        vm.until { it.activeId == null }.activeId shouldBe null
        vm.activate("s1")
        vm.until { it.activeId == "s1" }.activeId shouldBe "s1"
    }

    @Test
    fun `S042 RF-16 no se activa una superficie de otro proyecto`() {
        write("s1")
        val other = newProject()
        write("s9", project = other)
        val vm = open()
        vm.until { it.activeId == "s1" }

        vm.activate("s9")
        runBlocking { delay(100) }

        vm.state.value.activeId shouldBe "s1"
    }

    @Test
    fun `S042 RF-18 borrar la superficie activa deja el proyecto sin ninguna y borra solo esa`() {
        write("s1")
        write("s2", at = 2_000L)
        val vm = open()
        vm.until { it.activeId == "s1" && it.surfaces.size == 2 }

        vm.delete("s1")

        val state = vm.until { it.surfaces.size == 1 }
        state.surfaces.single().id shouldBe "s2"
        state.activeId shouldBe null
    }

    @Test
    fun `S042 RF-19 el desplazamiento se escribe en la unidad del proyecto y se guarda en metros`() {
        val inFeet = newProject(LinearUnit.INTERNATIONAL_FOOT)
        write("s1", project = inFeet)
        val vm = open(inFeet)
        vm.until { it.unit == LinearUnit.INTERNATIONAL_FOOT && it.activeId == "s1" }

        vm.setOffset("1") shouldBe true

        vm.until { it.offsetM != 0.0 }.offsetM shouldBe (0.3048 plusOrMinus 1e-12)
        runBlocking { surfaces.verticalOffset(inFeet).first() } shouldBe (0.3048 plusOrMinus 1e-12)
    }

    @Test
    fun `S042 RF-19 el desplazamiento admite signo negativo y coma decimal`() {
        val vm = open()
        vm.until { it.unit == LinearUnit.METRE }

        vm.setOffset("-0,45") shouldBe true

        vm.until { it.offsetM != 0.0 }.offsetM shouldBe (-0.45 plusOrMinus 1e-12)
    }

    @Test
    fun `S042 RF-20 un desplazamiento que no es un numero no se aplica`() {
        val vm = open()
        vm.until { it.unit == LinearUnit.METRE }
        vm.setOffset("0.3") shouldBe true
        vm.until { it.offsetM == 0.3 }

        vm.setOffset("abc") shouldBe false
        vm.setOffset("") shouldBe false
        vm.setOffset("1,2,3x") shouldBe false
        runBlocking { delay(100) }

        vm.state.value.offsetM shouldBe 0.3
    }

    @Test
    fun `S042 RF-19 el desplazamiento se conserva al activar otra superficie o borrarlas todas`() {
        write("s1")
        write("s2", at = 2_000L)
        val vm = open()
        vm.until { it.surfaces.size == 2 }
        vm.setOffset("0,3")
        vm.until { it.offsetM == 0.3 }

        vm.activate("s2")
        vm.until { it.activeId == "s2" }
        vm.delete("s1")
        vm.delete("s2")

        vm.until { it.surfaces.isEmpty() }.offsetM shouldBe 0.3
    }
}
