package es.topophone.app.ui.section

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.alignment.section.TemplateSegment
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
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

/** Calcular, ver y guardar un punto de la sección de un eje (S050/RF-9 a RF-11, RF-13, RF-15). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SectionPointViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val alignments = RoomAlignmentRepository(db)
    private val sections = RoomSectionRepository(db)
    private val points = RoomPointRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    /** Eje A (con rasante, PK de 0 a 420) y eje C (sin rasante, PK de 0 a 200) de los ejemplos. */
    private val ids: Map<String, String> = runBlocking {
        AlignmentImporter(alignments).importLandXml(
            projectId,
            ProjectCrs.ETRS89_UTM30,
            "ejes.xml",
            setOf(0, 2),
            CadSourceCrs.Project,
            LinearUnit.METRE,
            { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
        )
        alignments.summaries(projectId).first().associate { it.name to it.id }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private val template = SectionTemplate(
        0.0,
        420.0,
        "EJE",
        listOf(TemplateSegment("BI", 3.5, 0.02, true)),
        listOf(TemplateSegment("BD", 3.5, 0.02, true)),
    )

    private fun explicit(chainage: Double, kind: SectionKind = SectionKind.DESIGN) = CrossSection(
        kind,
        chainage,
        HeightKind.ABSOLUTE,
        listOf(SectionPoint("BI", -6.0, 100.1), SectionPoint("EJE", 0.0, 100.3), SectionPoint("BD", 6.0, 100.1)),
    )

    private fun open(axis: String, prepare: suspend () -> Unit = {}): SectionPointViewModel {
        runBlocking { prepare() }
        val vm = SectionPointViewModel(sections, alignments, projects, points, dispatcher)
        vm.start(projectId, ids.getValue(axis))
        runBlocking { withTimeout(10_000) { vm.state.first { it.loaded } } }
        return vm
    }

    private fun openA(superelevation: List<SuperelevationRow> = emptyList()) = open("Eje A") {
        sections.addTemplate(ids.getValue("Eje A"), template)
        if (superelevation.isNotEmpty()) sections.setSuperelevation(ids.getValue("Eje A"), superelevation)
    }

    @Test
    fun `S050 RF-9 un codigo en un PK da desplazamiento Este Norte cota y pendiente`() {
        val vm = openA()

        vm.setChainage("0")
        vm.setCode("BD")

        val reading = vm.state.value.reading.shouldNotBeNull()
        reading.code shouldBe "BD"
        reading.point.offset shouldBe 3.5
        reading.point.easting shouldBe (440001.75 plusOrMinus 1e-6)
        reading.point.northing shouldBe (4473996.968911087 plusOrMinus 1e-6)
        reading.elevationM.shouldNotBeNull() shouldBe (99.93 plusOrMinus 1e-9)
        reading.slope.shouldNotBeNull() shouldBe (0.02 plusOrMinus 1e-12)
        vm.state.value.codes shouldBe listOf("BI", "EJE", "BD")
    }

    @Test
    fun `S050 RF-9 con peralte la cota del borde cambia`() {
        val vm = openA(listOf(SuperelevationRow(0.0, -0.06, 0.06)))

        vm.setChainage("0")
        vm.setCode("BD")

        vm.state.value.reading.shouldNotBeNull().elevationM.shouldNotBeNull() shouldBe (100.0 - 3.5 * 0.06 plusOrMinus 1e-9)
    }

    @Test
    fun `S050 RF-10 por desplazamiento da la cota de la seccion y mas alla de los extremos dice Fuera de la seccion`() {
        val vm = openA()
        vm.setChainage("0")
        vm.setMode(SectionPointMode.BY_OFFSET)

        vm.setOffset("1.75")
        vm.state.value.reading.shouldNotBeNull().elevationM.shouldNotBeNull() shouldBe (99.965 plusOrMinus 1e-9)

        vm.setOffset("9")
        vm.state.value.reading.shouldBeNull()
        vm.state.value.status shouldBe SectionPointStatus.OutsideSection
    }

    @Test
    fun `S050 RF-11 sin rasante una seccion de cotas absolutas da la cota`() {
        val vm = open("Eje C") { sections.addSections(ids.getValue("Eje C"), listOf(explicit(0.0), explicit(100.0))) }

        vm.setChainage("50")
        vm.setCode("BD")

        vm.state.value.reading.shouldNotBeNull().elevationM.shouldNotBeNull() shouldBe (100.1 plusOrMinus 1e-9)
    }

    @Test
    fun `S050 RF-11 sin rasante y con una seccion relativa solo hay planta`() {
        val vm = open("Eje C") { sections.addTemplate(ids.getValue("Eje C"), template.copy(toChainage = 200.0)) }

        vm.setChainage("50")
        vm.setCode("BD")

        vm.state.value.reading.shouldNotBeNull().elevationM.shouldBeNull()
    }

    @Test
    fun `S050 RF-12 un PK sin seccion o entre secciones que no se interpolan lo dice`() {
        val vm = open("Eje C") {
            sections.addSections(
                ids.getValue("Eje C"),
                listOf(explicit(0.0), CrossSection(SectionKind.DESIGN, 100.0, HeightKind.ABSOLUTE, listOf(SectionPoint("EJE", 0.0, 1.0)))),
            )
        }

        vm.setChainage("150")
        vm.state.value.status shouldBe SectionPointStatus.NoSection
        vm.setChainage("50")
        vm.state.value.status shouldBe SectionPointStatus.Missing(listOf("BD", "BI"), false)
    }

    @Test
    fun `S050 RF-9 un PK fuera del eje no calcula nada`() {
        val vm = openA()

        vm.setChainage("999")

        vm.state.value.outside shouldBe true
        vm.state.value.reading.shouldBeNull()
    }

    @Test
    fun `S050 RF-9 un PK mal escrito se marca y un codigo que no esta en la seccion elige el eje`() {
        val vm = openA()

        vm.setChainage("abc")
        vm.state.value.chainageInvalid shouldBe true

        vm.setChainage("10")
        vm.setCode("ZZ")
        vm.state.value.code shouldBe "EJE"
        vm.state.value.reading.shouldNotBeNull().code shouldBe "EJE"
    }

    @Test
    fun `S050 RF-13 la seccion del PK escrito se ofrece para dibujarla con el perfil de terreno de ese PK si lo hay`() {
        val ground = explicit(
            100.0,
            SectionKind.GROUND,
        ).copy(points = listOf(SectionPoint("T1", -8.0, 99.0), SectionPoint("T2", 8.0, 99.5)))
        val vm = open("Eje C") {
            sections.addSections(ids.getValue("Eje C"), listOf(explicit(0.0), explicit(200.0), ground))
        }

        vm.setChainage("100")
        vm.state.value.section.shouldNotBeNull().points.map { it.code } shouldBe listOf("BI", "EJE", "BD")
        vm.state.value.ground.shouldNotBeNull().points.map { it.code } shouldBe listOf("T1", "T2")
        vm.setChainage("50")
        vm.state.value.ground.shouldBeNull()
        vm.setShowSection(true)
        vm.state.value.showSection shouldBe true
    }

    @Test
    fun `S050 RF-15 guardar un punto por codigo lo deja Calculado sobre eje con su codigo de seccion`() {
        val vm = openA()
        vm.setChainage("120")
        vm.setCode("BD")
        vm.setName("D1")

        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { !it.saving } } }

        vm.state.value.savedName shouldBe "D1"
        val saved = runBlocking { points.points(projectId).first() }.single()
        saved.origin shouldBe PointOrigin.ON_ALIGNMENT
        saved.onAxis.shouldNotBeNull().sectionCode shouldBe "BD"
        saved.onAxis.shouldNotBeNull().chainageM shouldBe (120.0 plusOrMinus 1e-9)
        saved.onAxis.shouldNotBeNull().offsetM shouldBe 3.5
    }

    @Test
    fun `S050 RF-15 un punto pedido por desplazamiento se guarda sin codigo de seccion`() {
        val vm = openA()
        vm.setChainage("120")
        vm.setMode(SectionPointMode.BY_OFFSET)
        vm.setOffset("2")
        vm.setName("D2")

        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { !it.saving } } }

        runBlocking { points.points(projectId).first() }.single().onAxis.shouldNotBeNull().sectionCode.shouldBeNull()
    }

    @Test
    fun `S050 RF-15 un nombre repetido no se guarda y dos puntos seguidos se pueden calcular`() {
        val vm = openA()
        vm.setChainage("120")
        vm.setName("D1")
        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { !it.saving && it.savedName != null } } }

        vm.another()
        vm.setName("D1")
        vm.save()
        runBlocking { withTimeout(10_000) { vm.state.first { !it.saving } } }

        vm.state.value.duplicateName shouldBe true
        vm.state.value.savedName.shouldBeNull()
    }
}
