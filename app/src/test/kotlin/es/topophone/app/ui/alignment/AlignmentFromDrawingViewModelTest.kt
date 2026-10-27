package es.topophone.app.ui.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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

/** Convertir una polilínea de un dibujo en un eje: extremo más cercano, arcos, cotas y motivos (S049/RF-6, RF-7). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlignmentFromDrawingViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val e0 = 440000.0
    private val n0 = 4474000.0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = AlignmentFromDrawingViewModel(AlignmentImporter(alignments), projects, dispatcher)

    private fun selection(entity: CadEntity, tapE: Double = entity.easting[0], tapN: Double = entity.northing[0]) = DesignSelection(
        ref = CadEntityRef("l1", 1L, 0),
        entity = entity,
        layerName = "Ejes",
        drawingName = "plano.dxf",
        hasElevation = entity.hasZ,
        tapEasting = tapE,
        tapNorthing = tapN,
    )

    private fun polyline(e: DoubleArray, n: DoubleArray, z: DoubleArray? = null, original: BooleanArray? = null) =
        CadEntity(CadEntityKind.POLYLINE, e, n, z, null, original)

    private fun AlignmentFromDrawingViewModel.convertAndWait(selection: DesignSelection, name: String = "Zanja", start: Double = 0.0) =
        runBlocking {
            convert(projectId, selection, name, start)
            withTimeout(10_000) { state.first { it.result != null } }.result.shouldNotBeNull()
        }

    @Test
    fun `S049 RF-6 una polilinea de rectas crea un eje con el PK inicial escrito`() {
        val line = polyline(doubleArrayOf(e0, e0 + 100, e0 + 100), doubleArrayOf(n0, n0, n0 + 50))

        val result = viewModel().convertAndWait(selection(line), start = 1200.0)

        val created = result.shouldBeInstanceOf<DrawingAlignmentResult.Created>()
        created.lengthM shouldBe (150.0 plusOrMinus 1e-9)
        created.startChainage shouldBe 1200.0
        val saved = runBlocking { alignments.summaries(projectId).first() }.single()
        saved.name shouldBe "Zanja"
        saved.source shouldBe AlignmentSource.DRAWING
        saved.fileName shouldBe "plano.dxf"
        saved.startChainage shouldBe 1200.0
    }

    @Test
    fun `S049 RF-6 el eje empieza por el extremo mas cercano al punto tocado`() {
        val line = polyline(doubleArrayOf(e0, e0 + 100), doubleArrayOf(n0, n0))

        viewModel().convertAndWait(selection(line, tapE = e0 + 95, tapN = n0 + 1))

        val axis = runBlocking { alignments.alignment(alignments.summaries(projectId).first().single().id) }.shouldNotBeNull()
        axis.elements.first().startEasting shouldBe (e0 + 100)
        axis.elements.first().startAzimuth shouldBe (PI * 3 / 2 plusOrMinus 1e-12) // hacia el Oeste
    }

    @Test
    fun `S049 RF-6 los arcos de la polilinea se guardan como arcos`() {
        val r = 80.0
        val es = arrayListOf(e0, e0 + 100.0)
        val ns = arrayListOf(n0, n0)
        val original = arrayListOf(true, true)
        for (deg in 10..80 step 10) {
            val a = -PI / 2 + Math.toRadians(deg.toDouble())
            es += e0 + 100.0 + r * cos(a)
            ns += n0 + r + r * sin(a)
            original += false
        }
        es += e0 + 180.0
        ns += n0 + r
        original += true
        val entity = polyline(es.toDoubleArray(), ns.toDoubleArray(), original = original.toBooleanArray())

        viewModel().convertAndWait(selection(entity, tapE = e0 - 2))

        val axis = runBlocking { alignments.alignment(alignments.summaries(projectId).first().single().id) }.shouldNotBeNull()
        axis.elements.map { it.kind } shouldBe listOf(ElementKind.LINE, ElementKind.ARC)
        axis.elements[1].startCurvature shouldBe (1.0 / r plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-7 una polilinea con cotas da un eje con rasante sin acuerdos`() {
        val line = polyline(doubleArrayOf(e0, e0 + 100), doubleArrayOf(n0, n0), z = doubleArrayOf(100.0, 102.0))

        val result = viewModel().convertAndWait(selection(line))

        result.shouldBeInstanceOf<DrawingAlignmentResult.Created>().hasProfile shouldBe true
        val axis = runBlocking { alignments.alignment(alignments.summaries(projectId).first().single().id) }.shouldNotBeNull()
        axis.profile.shouldNotBeNull().vertices.map { it.elevation } shouldBe listOf(100.0, 102.0)
    }

    @Test
    fun `S049 RF-7 una polilinea sin cotas crea el eje sin rasante`() {
        val line = polyline(doubleArrayOf(e0, e0 + 100), doubleArrayOf(n0, n0))

        viewModel().convertAndWait(selection(line)).shouldBeInstanceOf<DrawingAlignmentResult.Created>().hasProfile shouldBe false
    }

    @Test
    fun `S049 RF-6 una curva que no es un arco no se convierte y dice donde empieza`() {
        val es = DoubleArray(12) { e0 + it * 10.0 }
        val ns = DoubleArray(12) { n0 + 5.0 * sin(it * 0.6) }
        val entity = polyline(es, ns, original = BooleanArray(12) { it == 0 || it == 11 })

        val result = viewModel().convertAndWait(selection(entity))

        result.shouldBeInstanceOf<DrawingAlignmentResult.Rejected>().issue shouldBe AlignmentIssue.NonCircularCurve(1)
        runBlocking { alignments.summaries(projectId).first() } shouldBe emptyList()
    }

    @Test
    fun `S049 RF-12 una polilinea fuera del ambito del sistema no se convierte`() {
        val line = polyline(doubleArrayOf(0.0, 100.0), doubleArrayOf(0.0, 0.0))

        viewModel().convertAndWait(selection(line)).shouldBeInstanceOf<DrawingAlignmentResult.Rejected>().issue shouldBe
            AlignmentIssue.OutsideCrsArea
    }

    @Test
    fun `S049 RF-6 cerrar el dialogo borra el resultado y un nombre en blanco no crea nada`() {
        val line = polyline(doubleArrayOf(e0, e0 + 100), doubleArrayOf(n0, n0))
        val vm = viewModel()

        vm.convert(projectId, selection(line), "   ", 0.0)
        vm.state.value.result shouldBe null
        vm.convertAndWait(selection(line))
        vm.dismiss()

        vm.state.value shouldBe AlignmentFromDrawingUiState()
    }
}
