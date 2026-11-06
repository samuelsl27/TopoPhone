package es.topophone.app.ui.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.stakeout.RoomStakeLines
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.line.LineSaveResult
import es.topophone.data.line.NewLine
import es.topophone.data.line.RoomLineRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

/** «Crear eje» desde una línea abierta del proyecto: sus vértices como tramos rectos, sus alturas como rasante y la línea intacta (S027/RF-17, RF-18). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CreateAxisFromLineTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val lines = RoomLineRepository(db, points)
    private val alignments = RoomAlignmentRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun viewModel() = AlignmentFromDrawingViewModel(
        AlignmentImporter(alignments),
        projects,
        dispatcher,
        stakeLines = RoomStakeLines(lines),
    )

    private fun point(name: String, east: Double, north: Double, height: Double?) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, height?.plus(50.0)), east, north, height),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    /** Una línea (o un polígono) de 30 + 40 m por A, B y C con las alturas dadas. */
    private fun createLine(heights: List<Double?> = listOf(null, null, null), closed: Boolean = false): String {
        runBlocking {
            listOf(
                point("A", 440_000.0, 4_474_000.0, heights[0]),
                point("B", 440_030.0, 4_474_000.0, heights[1]),
                point("C", 440_030.0, 4_474_040.0, heights[2]),
            ).forEach { points.create(it) }
        }
        val geometry = if (closed) CodeGeometry.POLYGON else CodeGeometry.LINE
        return (
            runBlocking {
                lines.createFromPoints(NewLine(projectId, "BORD_1", null, geometry, closed, listOf("id-A", "id-B", "id-C")))
            } as LineSaveResult.Saved
            ).id
    }

    private fun AlignmentFromDrawingViewModel.convertAndWait(lineId: String, name: String = "Zanja", start: Double = 0.0) = runBlocking {
        convertLine(projectId, lineId, name, start)
        withTimeout(10_000) { state.first { it.result != null } }.result.shouldNotBeNull()
    }

    @Test
    fun `S027 RF-17 una linea abierta crea un eje con sus vertices como tramos rectos y el PK inicial escrito`() {
        val result = viewModel().convertAndWait(createLine(), start = 500.0)

        val created = result.shouldBeInstanceOf<DrawingAlignmentResult.Created>()
        created.lengthM shouldBe (70.0 plusOrMinus 1e-9)
        created.startChainage shouldBe 500.0
        created.hasProfile shouldBe false
        val saved = runBlocking { alignments.summaries(projectId).first() }.single()
        saved.name shouldBe "Zanja"
        saved.source shouldBe AlignmentSource.PROJECT_LINE
        saved.startChainage shouldBe 500.0
        val axis = runBlocking { alignments.alignment(saved.id) }.shouldNotBeNull()
        axis.elements.map { it.kind } shouldBe listOf(ElementKind.LINE, ElementKind.LINE)
        axis.elements.map { it.length } shouldBe listOf(30.0, 40.0)
    }

    @Test
    fun `S027 RF-17 el eje empieza en el primer vertice de la linea`() {
        viewModel().convertAndWait(createLine())

        val axis = runBlocking { alignments.alignment(alignments.summaries(projectId).first().single().id) }.shouldNotBeNull()
        axis.elements.first().startEasting shouldBe 440_000.0
        axis.elements.first().startNorthing shouldBe 4_474_000.0
    }

    @Test
    fun `S027 RF-17 con alturas en todos los vertices el eje lleva rasante sin acuerdos`() {
        val result = viewModel().convertAndWait(createLine(listOf(650.0, 655.0, 664.0)))

        result.shouldBeInstanceOf<DrawingAlignmentResult.Created>().hasProfile shouldBe true
        val axis = runBlocking { alignments.alignment(alignments.summaries(projectId).first().single().id) }.shouldNotBeNull()
        axis.profile.shouldNotBeNull().vertices.map { it.elevation } shouldBe listOf(650.0, 655.0, 664.0)
    }

    @Test
    fun `S027 RF-17 con alturas en solo algunos vertices la rasante usa solo esos`() {
        val result = viewModel().convertAndWait(createLine(listOf(650.0, null, 664.0)))

        result.shouldBeInstanceOf<DrawingAlignmentResult.Created>().hasProfile shouldBe true
        val axis = runBlocking { alignments.alignment(alignments.summaries(projectId).first().single().id) }.shouldNotBeNull()
        axis.profile.shouldNotBeNull().vertices.map { it.chainage } shouldBe listOf(0.0, 70.0)
    }

    @Test
    fun `S027 RF-17 la linea y sus puntos quedan intactos`() {
        val lineId = createLine(listOf(650.0, 655.0, 664.0))
        val lineBefore = runBlocking { lines.line(lineId).first() }
        val pointsBefore = runBlocking { points.points(projectId).first() }

        viewModel().convertAndWait(lineId)

        runBlocking { lines.line(lineId).first() } shouldBe lineBefore
        runBlocking { points.points(projectId).first() } shouldBe pointsBefore
    }

    @Test
    fun `S027 RF-17 un poligono o una linea que no existe no se convierte en eje`() {
        val polygon = createLine(closed = true)

        viewModel().convertAndWait(polygon).shouldBeInstanceOf<DrawingAlignmentResult.Rejected>().issue shouldBe AlignmentIssue.NotAPolyline
        viewModel().convertAndWait("no-existe").shouldBeInstanceOf<DrawingAlignmentResult.Rejected>().issue shouldBe
            AlignmentIssue.NotAPolyline
        runBlocking { alignments.summaries(projectId).first() }.size shouldBe 0
    }

    @Test
    fun `S027 RF-18 el eje creado es un eje del proyecto como los demas, listo para el replanteo por PK de S054`() {
        viewModel().convertAndWait(createLine(listOf(650.0, 655.0, 664.0)), name = "Eje de la zanja", start = 0.0)

        val summary = runBlocking { alignments.summaries(projectId).first() }.single()
        summary.name shouldBe "Eje de la zanja"
        summary.lengthM shouldBe (70.0 plusOrMinus 1e-9)
        runBlocking { alignments.alignment(summary.id) }.shouldNotBeNull().elements.size shouldBe 2
    }
}
