package es.topophone.app.ui.slope

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceGuide
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import es.topophone.core.survey.SlopeEdgeResult
import es.topophone.core.survey.TerrainSource
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
import es.topophone.data.slope.RoomSlopeRepository
import es.topophone.data.surface.SurfaceGuides
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** El punto de talud teórico de un PK y un lado, con el terreno de los perfiles o de la superficie (S051/RF-5 a RF-8). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SlopePointViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val sections = RoomSectionRepository(db)
    private val slopes = RoomSlopeRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    /** El eje C de los ejemplos (al Este desde 441000, PK de 0 a 200). */
    private val axis: String = runBlocking {
        AlignmentImporter(alignments).importLandXml(
            projectId,
            ProjectCrs.ETRS89_UTM30,
            "ejes.xml",
            setOf(2),
            CadSourceCrs.Project,
            LinearUnit.METRE,
            { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
        )
        alignments.summaries(projectId).first().single().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /** Una sección de diseño absoluta con el borde BD a 3,5 m y 100 m de cota, en dos PK. */
    private fun addDesign() = runBlocking {
        val pts = listOf(SectionPoint("EJE", 0.0, 100.5), SectionPoint("BD", 3.5, 100.0))
        sections.addSections(axis, listOf(0.0, 200.0).map { CrossSection(SectionKind.DESIGN, it, HeightKind.ABSOLUTE, pts) })
    }

    private fun addGround(vararg terrain: Pair<Double, Double>) = runBlocking {
        val pts = terrain.mapIndexed { i, p -> SectionPoint("T$i", p.first, p.second) }
        sections.addSections(axis, listOf(0.0, 200.0).map { CrossSection(SectionKind.GROUND, it, HeightKind.ABSOLUTE, pts) })
    }

    private fun addRule(side: SlopeSide = SlopeSide.RIGHT, from: Double = 0.0, to: Double = 200.0) =
        runBlocking { slopes.add(axis, SlopeRule(side, from, to, 1.5, 2.0, "BD")) }

    private fun open(guide: SurfaceGuide? = null): SlopePointViewModel {
        val vm = SlopePointViewModel(slopes, sections, alignments, projects, SurfaceGuides { flowOf(guide) }, dispatcher)
        vm.start(projectId, axis)
        runBlocking { withTimeout(10_000) { vm.state.first { it.loaded } } }
        return vm
    }

    private fun SlopePointViewModel.outcome(pk: String = "105"): SlopePointOutcome? {
        setChainage(pk)
        return state.value.outcome
    }

    private fun surface(source: SurfaceHeightSource) = SurfaceGuide(
        Surface(
            "s1", projectId, "terreno", "t.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE,
            SurfaceExtent(
                0.0,
                1.0e7,
                0.0,
                1.0e7,
            ),
            90.0, 120.0, 10, 8, 0,
        ),
        source,
        0.0,
    )

    @Test
    fun `S051 RF-5 una cabeza en desmonte con el terreno de un perfil da el borde el punto de talud y la altura`() {
        addDesign()
        addGround(3.5 to 100.5, 8.0 to 102.0, 15.0 to 104.0, 30.0 to 110.0, 110.0 to 140.0)
        addRule()
        val vm = open()

        val found = vm.outcome().shouldBeInstanceOf<SlopePointOutcome.Found>()

        found.edge.offsetM shouldBe 3.5
        found.edge.heightM shouldBe 100.0
        found.point.kind shouldBe SlopeKind.CUT
        found.point.offsetM shouldBe (5.0 plusOrMinus 1e-6)
        found.point.heightM shouldBe (101.0 plusOrMinus 1e-6)
        found.point.slopeHeightM shouldBe (1.0 plusOrMinus 1e-6)
        found.point.point.easting shouldBe (441_105.0 plusOrMinus 1e-6)
        found.point.point.northing shouldBe (4_474_000.0 - 5.0 plusOrMinus 1e-6)
        found.source shouldBe TerrainSource.PROFILE
    }

    @Test
    fun `S051 RF-5 un pie en terraplen`() {
        addDesign()
        addGround(3.5 to 99.2, 8.0 to 98.0, 20.0 to 95.0, 110.0 to 70.0)
        addRule()
        val vm = open()

        val found = vm.outcome().shouldBeInstanceOf<SlopePointOutcome.Found>()

        found.point.kind shouldBe SlopeKind.FILL
        found.point.distanceM shouldBe (3.4285714285714284 plusOrMinus 1e-6)
    }

    @Test
    fun `S051 RF-6 sin perfil de terreno se usa la superficie activa`() {
        addDesign()
        addRule()
        // La rampa de los ejemplos: 101,667 en el borde y sube 1/3 por metro hacia el Sur: el corte esta a 5 m.
        val ramp = SurfaceHeightSource { _, n -> SurfaceHeight.Value(100.5 + (4_474_000.0 - n) / 3.0) }
        val vm = open(surface(ramp))

        val found = vm.outcome().shouldBeInstanceOf<SlopePointOutcome.Found>()

        found.point.kind shouldBe SlopeKind.CUT
        found.point.distanceM shouldBe (5.0 plusOrMinus 1e-6)
        found.source shouldBe TerrainSource.SURFACE
    }

    @Test
    fun `S051 RF-8 con el terreno a la cota del borde el punto de talud es el borde con altura cero`() {
        addDesign()
        addGround(3.5 to 100.0, 110.0 to 105.0)
        addRule()

        open().outcome().shouldBeInstanceOf<SlopePointOutcome.AtEdge>().edge.heightM shouldBe 100.0
    }

    @Test
    fun `S051 RF-7 un terreno paralelo al talud no tiene interseccion`() {
        addDesign()
        addGround(3.5 to 100.5, 110.0 to 171.5)
        addRule()

        open().outcome().shouldBeInstanceOf<SlopePointOutcome.NoIntersection>()
    }

    @Test
    fun `S051 RF-6 sin ningun terreno conocido no se calcula el punto teorico`() {
        addDesign()
        addRule()

        open().outcome().shouldBeInstanceOf<SlopePointOutcome.NoTerrain>().edge.offsetM shouldBe 3.5
    }

    @Test
    fun `S051 RF-8 un PK sin talud o un lado sin talud o sin seccion se dice y un PK fuera del eje tambien`() {
        addDesign()
        addRule(from = 0.0, to = 100.0)
        val vm = open()

        vm.outcome("150") shouldBe SlopePointOutcome.Unavailable(SlopeEdgeResult.NoRule)
        vm.setSide(SlopeSide.LEFT)
        vm.outcome("50") shouldBe SlopePointOutcome.Unavailable(SlopeEdgeResult.NoRule)
        vm.outcome("999")
        vm.state.value.outside shouldBe true
        vm.state.value.outcome shouldBe null
    }

    @Test
    fun `S051 RF-5 un PK mal escrito se marca y el lado cambia el calculo`() {
        addDesign()
        addGround(3.5 to 100.5, 8.0 to 102.0, 15.0 to 104.0, 30.0 to 110.0, 110.0 to 140.0)
        addRule()
        val vm = open()

        vm.setChainage("abc")
        vm.state.value.chainageInvalid shouldBe true
        vm.setChainage("105")
        vm.setSide(SlopeSide.LEFT)

        vm.state.value.outcome shouldBe SlopePointOutcome.Unavailable(SlopeEdgeResult.NoRule)
    }
}
