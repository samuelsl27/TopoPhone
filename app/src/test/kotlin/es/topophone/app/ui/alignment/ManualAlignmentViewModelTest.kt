package es.topophone.app.ui.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.PiIssue
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Definir un eje a mano: vértices con radio y clotoides, rasante con Kv, problemas en vivo y creación (S049/RF-8, RF-10, RF-12). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ManualAlignmentViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private var counter = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun newProject(crs: ProjectCrs = ProjectCrs.ETRS89_UTM30, unit: LinearUnit = LinearUnit.METRE): String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra ${counter++}", crs = crs, linearUnit = unit))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun open(projectId: String = newProject()): Pair<ManualAlignmentViewModel, String> {
        val vm = ManualAlignmentViewModel(AlignmentImporter(alignments), projects, dispatcher)
        vm.start(projectId)
        runBlocking { withTimeout(10_000) { vm.state.first { it.loaded } } }
        return vm to projectId
    }

    private fun ManualAlignmentViewModel.line(
        e0: String = "440000",
        n0: String = "4474000",
        e1: String = "440100",
        n1: String = "4474000",
    ) {
        setName("Zanja")
        setVertex(0, VertexRow(e0, n0))
        setVertex(1, VertexRow(e1, n1))
    }

    private fun ManualAlignmentViewModel.createAndWait(): ManualAlignmentUiState = runBlocking {
        create()
        withTimeout(10_000) { state.first { it.result != null } }
    }

    @Test
    fun `S049 RF-8 al empezar hay dos vertices vacios y falta el nombre`() {
        val (vm, _) = open()

        vm.state.value.vertices shouldBe listOf(VertexRow(), VertexRow())
        vm.state.value.problem shouldBe ManualProblem.NameBlank
        vm.state.value.canCreate shouldBe false
    }

    @Test
    fun `S049 RF-8 un campo que no es un numero dice la fila y el campo`() {
        val (vm, _) = open()
        vm.setName("Eje")
        vm.setVertex(0, VertexRow("440000", "abc"))

        vm.state.value.problem shouldBe ManualProblem.InvalidNumber(1, ManualField.NORTHING, profile = false)
        vm.setVertex(0, VertexRow("440000", "4474000"))
        vm.state.value.problem shouldBe ManualProblem.InvalidNumber(2, ManualField.EASTING, profile = false)
    }

    @Test
    fun `S049 RF-8 dos vertices bien escritos dan la longitud del eje y dejan crearlo`() {
        val (vm, _) = open()

        vm.line()

        vm.state.value.problem.shouldBeNull()
        vm.state.value.lengthM.shouldNotBeNull() shouldBe (100.0 plusOrMinus 1e-9)
        vm.state.value.canCreate shouldBe true
    }

    @Test
    fun `S049 RF-8 se aceptan la coma decimal y el PK inicial escrito`() {
        val (vm, _) = open()
        vm.line(e1 = "440100,5")
        vm.setStartChainage("1240,5")

        vm.state.value.lengthM.shouldNotBeNull() shouldBe (100.5 plusOrMinus 1e-9)
        vm.state.value.startChainageM shouldBe (1240.5 plusOrMinus 1e-12)
        vm.setStartChainage("x")
        vm.state.value.problem shouldBe ManualProblem.InvalidStart
    }

    @Test
    fun `S049 RF-8 crear guarda el eje con el nombre el origen manual y el PK inicial`() {
        val (vm, projectId) = open()
        vm.line()
        vm.setStartChainage("250")

        val done = vm.createAndWait()

        done.result.shouldBeInstanceOf<ManualAlignmentResult.Created>().lengthM shouldBe (100.0 plusOrMinus 1e-9)
        val saved = runBlocking { alignments.summaries(projectId).first() }.single()
        saved.name shouldBe "Zanja"
        saved.source shouldBe AlignmentSource.MANUAL
        saved.fileName.shouldBeNull()
        saved.startChainage shouldBe 250.0
    }

    @Test
    fun `S049 RF-8 el eje por vertices con radio y clotoides coincide con el de tools`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        val vertices = File(root, "fixtures/ejes/pi-vertices.csv").readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }
        val expected = File(root, "fixtures/ejes/esperado-pi.csv").readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }
        val (vm, projectId) = open()
        vm.setName("Por vértices")
        vm.setStartChainage("250")
        vertices.forEachIndexed { i, v ->
            if (i >= 2) vm.addVertex()
            vm.setVertex(i, VertexRow(v[1], v[2], v[3], v[4]))
        }

        vm.state.value.problem.shouldBeNull()
        vm.createAndWait().result.shouldBeInstanceOf<ManualAlignmentResult.Created>()

        val axis = runBlocking { alignments.alignment(alignments.summaries(projectId).first().single().id) }.shouldNotBeNull()
        axis.elements.size shouldBe expected.size
        for ((e, row) in axis.elements.zip(expected)) {
            e.kind shouldBe when (row[1]) {
                "line" -> ElementKind.LINE
                "arc" -> ElementKind.ARC
                else -> ElementKind.CLOTHOID
            }
            e.length shouldBe (row[3].toDouble() plusOrMinus 1e-6)
            e.startEasting shouldBe (row[6].toDouble() plusOrMinus 1e-6)
            e.startNorthing shouldBe (row[7].toDouble() plusOrMinus 1e-6)
        }
    }

    @Test
    fun `S049 RF-8 los vertices de la rasante con Kv crean la rasante con sus acuerdos`() {
        val (vm, projectId) = open()
        vm.line(e1 = "440200")
        vm.addProfileVertex()
        vm.addProfileVertex()
        vm.addProfileVertex()
        vm.setProfileVertex(0, ProfileRow("0", "100"))
        vm.setProfileVertex(1, ProfileRow("100", "103", "3000"))
        vm.setProfileVertex(2, ProfileRow("200", "101"))

        vm.state.value.problem.shouldBeNull()
        val done = vm.createAndWait()

        done.result.shouldBeInstanceOf<ManualAlignmentResult.Created>().hasProfile shouldBe true
        val profile = runBlocking { alignments.alignment(alignments.summaries(projectId).first().single().id) }!!.profile.shouldNotBeNull()
        profile.vertices.map { it.chainage } shouldBe listOf(0.0, 100.0, 200.0)
        // g1 = 0,03 y g2 = −0,02: Δi = 0,05 y la parábola mide 3000 · 0,05 = 150 m.
        (profile.vertices[1].curve as VerticalCurve.Parabola).length shouldBe (150.0 plusOrMinus 1e-9)
        profile.vertices[0].curve shouldBe VerticalCurve.None
    }

    @Test
    fun `S049 RF-10 una rasante con un solo vertice o fuera del eje o repetida impide crear`() {
        val (vm, _) = open()
        vm.line()
        vm.addProfileVertex()
        vm.setProfileVertex(0, ProfileRow("0", "100"))
        vm.state.value.problem shouldBe ManualProblem.ProfileTooFew

        vm.addProfileVertex()
        vm.setProfileVertex(1, ProfileRow("250", "101"))
        vm.state.value.problem shouldBe ManualProblem.Profile(ProfileIssue.OutsideAxis(2, 250.0))

        vm.setProfileVertex(1, ProfileRow("0", "101"))
        vm.state.value.problem shouldBe ManualProblem.Profile(ProfileIssue.RepeatedChainage(2, 0.0))

        vm.setProfileVertex(1, ProfileRow("abc", "101"))
        vm.state.value.problem shouldBe ManualProblem.InvalidNumber(2, ManualField.CHAINAGE, profile = true)

        vm.removeProfileVertex(1)
        vm.removeProfileVertex(0)
        vm.state.value.problem.shouldBeNull() // sin vértices de rasante no hay rasante
    }

    @Test
    fun `S049 RF-10 un acuerdo vertical que no cabe impide crear y dice el vertice`() {
        val (vm, _) = open()
        vm.line()
        listOf(ProfileRow("0", "100"), ProfileRow("50", "103", "50000"), ProfileRow("100", "101")).forEachIndexed { i, row ->
            vm.addProfileVertex()
            vm.setProfileVertex(i, row)
        }

        vm.state.value.problem shouldBe ManualProblem.Profile(ProfileIssue.CurveDoesNotFit(2, 50.0))
        vm.state.value.canCreate shouldBe false
    }

    @Test
    fun `S049 RF-8 vertices que no forman un eje dicen el motivo y el vertice`() {
        val (vm, _) = open()
        vm.setName("Eje")

        vm.setVertex(0, VertexRow("440000", "4474000"))
        vm.setVertex(1, VertexRow("440000", "4474000"))
        vm.state.value.problem shouldBe ManualProblem.Geometry(PiIssue.CoincidentVertices(2))

        vm.setVertex(1, VertexRow("440100", "4474000"))
        vm.addVertex()
        vm.setVertex(1, VertexRow("440100", "4474000", "-5"))
        vm.setVertex(2, VertexRow("440100", "4474100"))
        vm.state.value.problem shouldBe ManualProblem.Geometry(PiIssue.InvalidParameter(2))

        vm.setVertex(1, VertexRow("440100", "4474000", "60", "400"))
        vm.state.value.problem shouldBe ManualProblem.Geometry(PiIssue.SpiralTooLong(2))

        vm.setVertex(1, VertexRow("440100", "4474000", "500"))
        vm.state.value.problem shouldBe ManualProblem.Geometry(PiIssue.TangentsOverlap(2))
    }

    @Test
    fun `S049 RF-8 quitar vertices deja el eje con los que quedan y con uno solo falta otro`() {
        val (vm, _) = open()
        vm.line()
        vm.addVertex()
        vm.setVertex(2, VertexRow("440100", "4474100"))
        vm.removeVertex(1)
        vm.state.value.vertices.size shouldBe 2
        vm.state.value.problem.shouldBeNull()

        vm.removeVertex(0)
        vm.state.value.problem shouldBe ManualProblem.TooFewVertices
        vm.removeVertex(5) // una fila que no existe no cambia nada
        vm.state.value.vertices.size shouldBe 1
    }

    @Test
    fun `S049 RF-8 lo escrito en pies se guarda en metros`() {
        val feet = LinearUnit.INTERNATIONAL_FOOT
        val (vm, projectId) = open(newProject(unit = feet))
        vm.setName("En pies")
        vm.setVertex(0, VertexRow("0", "0"))
        vm.setVertex(1, VertexRow("1000", "0"))

        vm.state.value.projectUnit shouldBe feet
        vm.state.value.lengthM.shouldNotBeNull() shouldBe (304.8 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-12 un eje fuera del ambito del sistema no se crea y dice el motivo`() {
        val (vm, projectId) = open()
        vm.line("0", "0", "100", "0")

        val done = vm.createAndWait()

        done.result.shouldBeInstanceOf<ManualAlignmentResult.Rejected>()
        runBlocking { alignments.summaries(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S049 RF-8 un proyecto en geograficas no admite ejes a mano`() {
        val (vm, _) = open(newProject(crs = ProjectCrs.ETRS89_GEOGRAPHIC))
        vm.line()

        vm.state.value.problem shouldBe ManualProblem.GeographicProject
        vm.state.value.canCreate shouldBe false
    }
}
