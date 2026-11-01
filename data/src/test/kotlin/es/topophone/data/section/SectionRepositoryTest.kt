package es.topophone.data.section

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.alignment.section.TemplateSegment
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Guardar y borrar secciones, secciones tipo y peraltes de un eje (S050/RF-3, RF-7, RF-8, RNF-T6). */
@RunWith(AndroidJUnit4::class)
class SectionRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val logged = mutableListOf<String>()
    private val log = object : AppLog {
        override fun record(level: LogLevel, category: LogCategory, message: String) {
            logged += message
        }
    }
    private var counter = 0
    private val alignments = RoomAlignmentRepository(db, log) { "a${++counter}" }
    private val sections = RoomSectionRepository(db, log, clock = { 9_000L }) { "s${++counter}" }

    @After
    fun close() = db.close()

    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Camino")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val axis = runBlocking {
        alignments.add(
            project,
            listOf(
                AlignmentToSave(
                    "Eje A",
                    null,
                    AlignmentSource.MANUAL,
                    listOf(AlignmentElement.line(0.0, 420.0, 440000.0, 4474000.0, 1.0)),
                    null,
                    AlignmentExtent(440000.0, 440400.0, 4474000.0, 4474300.0),
                    false,
                ),
            ),
            1L,
        ).ids.single()
    }

    private fun template(from: Double = 0.0, to: Double = 100.0) = SectionTemplate(
        from,
        to,
        "EJE",
        listOf(TemplateSegment("BI", 3.5, 0.02, true)),
        listOf(TemplateSegment("BD", 3.5, 0.02, true)),
    )

    private fun section(chainage: Double, kind: SectionKind = SectionKind.DESIGN, offset: Double = 3.500000000000001) = CrossSection(
        kind,
        chainage,
        HeightKind.RELATIVE,
        listOf(SectionPoint("BI", -offset, -0.07000000000000002), SectionPoint("BD", offset, -0.07)),
    )

    @Test
    fun `S050 RF-8 una seccion tipo se guarda y se lee igual con su fecha`() = runBlocking<Unit> {
        val saved = sections.addTemplate(axis, template())

        saved.shouldBeInstanceOf<SectionSaveResult.Saved>()
        val read = sections.templates(axis).first().single()
        read.template shouldBe template()
        read.createdAtMs shouldBe 9_000L
        read.id shouldBe (saved as SectionSaveResult.Saved).ids.single()
    }

    @Test
    fun `S050 RF-8 las secciones se guardan sin redondear y vuelven ordenadas por PK`() = runBlocking<Unit> {
        sections.addSections(axis, listOf(section(150.0), section(50.0, SectionKind.GROUND), section(100.0)))

        val read = sections.sections(axis).first()

        read.map { it.section.chainage } shouldBe listOf(50.0, 100.0, 150.0)
        read[1].section shouldBe section(100.0)
        read[1].section.points[0].offsetM.toRawBits() shouldBe (-3.500000000000001).toRawBits()
        read[0].section.kind shouldBe SectionKind.GROUND
    }

    @Test
    fun `S050 RF-3 varias secciones de diseno por eje y una nueva en el mismo PK sustituye a la anterior`() = runBlocking<Unit> {
        sections.addSections(axis, listOf(section(100.0), section(200.0)))
        sections.addSections(axis, listOf(section(100.0, offset = 4.0)))

        val read = sections.sections(axis).first()

        read.map { it.section.chainage } shouldBe listOf(100.0, 200.0)
        read[0].section.points[1].offsetM shouldBe 4.0
    }

    @Test
    fun `S050 RF-7 una seccion que no vale no se guarda ni deja nada de las demas`() = runBlocking<Unit> {
        val bad = section(300.0).copy(points = listOf(SectionPoint("BD", 3.0, 0.0), SectionPoint("BI", -3.0, 0.0)))

        val result = sections.addSections(axis, listOf(section(100.0), bad))

        result shouldBe SectionSaveResult.Rejected(SectionIssue.Unordered("BI"), index = 1)
        sections.sections(axis).first() shouldBe emptyList()
    }

    @Test
    fun `S050 RF-7 una seccion en un PK fuera del eje se rechaza`() = runBlocking<Unit> {
        sections.addSections(axis, listOf(section(999.0))) shouldBe SectionSaveResult.Rejected(SectionIssue.ChainageOutsideAxis(999.0), 0)
    }

    @Test
    fun `S050 RF-7 dos secciones tipo que se solapan no se guardan`() = runBlocking<Unit> {
        sections.addTemplate(axis, template(0.0, 100.0))

        sections.addTemplate(axis, template(50.0, 150.0)) shouldBe
            SectionSaveResult.Rejected(SectionIssue.OverlappingTemplates(50.0, 150.0), 0)
        sections.addTemplate(axis, template(100.0, 200.0)).shouldBeInstanceOf<SectionSaveResult.Saved>()
        sections.templates(axis).first().size shouldBe 2
    }

    @Test
    fun `S050 RF-6 el peralte se guarda entero y sustituye al anterior`() = runBlocking<Unit> {
        sections.setSuperelevation(axis, listOf(SuperelevationRow(0.0, 0.02, 0.02), SuperelevationRow(100.0, -0.06, 0.06)))
        sections.setSuperelevation(axis, listOf(SuperelevationRow(50.0, -0.04, 0.04)))

        sections.superelevation(axis).first() shouldBe listOf(SuperelevationRow(50.0, -0.04, 0.04))
    }

    @Test
    fun `S050 RF-7 un peralte con PK repetidos no se guarda y deja el anterior`() = runBlocking<Unit> {
        sections.setSuperelevation(axis, listOf(SuperelevationRow(0.0, 0.02, 0.02)))

        val result = sections.setSuperelevation(axis, listOf(SuperelevationRow(10.0, 0.0, 0.0), SuperelevationRow(10.0, 0.0, 0.0)))

        result shouldBe SectionSaveResult.Rejected(SectionIssue.RepeatedChainage(10.0), 0)
        sections.superelevation(axis).first() shouldBe listOf(SuperelevationRow(0.0, 0.02, 0.02))
    }

    @Test
    fun `S050 RF-8 borrar una seccion o una seccion tipo quita solo esa`() = runBlocking<Unit> {
        val t = sections.addTemplate(axis, template()).shouldBeInstanceOf<SectionSaveResult.Saved>().ids.single()
        sections.addSections(axis, listOf(section(100.0), section(200.0)))
        val firstSection = sections.sections(axis).first().first().id

        sections.deleteTemplate(t)
        sections.deleteSection(firstSection)

        sections.templates(axis).first() shouldBe emptyList()
        sections.sections(axis).first().map { it.section.chainage } shouldBe listOf(200.0)
    }

    @Test
    fun `S050 RF-8 borrar el eje borra sus secciones y peraltes sin tocar los puntos`() = runBlocking<Unit> {
        sections.addTemplate(axis, template())
        sections.addSections(axis, listOf(section(100.0)))
        sections.setSuperelevation(axis, listOf(SuperelevationRow(0.0, 0.02, 0.02)))

        alignments.delete(axis)

        sections.templates(axis).first() shouldBe emptyList()
        sections.sections(axis).first() shouldBe emptyList()
        sections.superelevation(axis).first() shouldBe emptyList()
    }

    @Test
    fun `S050 RF-8 el log anota altas y bajas con el recuento y sin nombres ni coordenadas`() = runBlocking<Unit> {
        sections.addSections(axis, listOf(section(100.0), section(200.0)))
        val id = sections.sections(axis).first().first().id
        sections.deleteSection(id)

        logged.any { it.contains("2") && it.contains("secciones") } shouldBe true
        logged.any { it.contains("Eje A") || it.contains("440000") } shouldBe false
    }

    @Test
    fun `S050 RF-8 un eje que no existe se indica y no se guarda nada`() = runBlocking<Unit> {
        sections.addTemplate("no-existe", template()) shouldBe SectionSaveResult.AxisNotFound
        sections.addSections("no-existe", listOf(section(1.0))) shouldBe SectionSaveResult.AxisNotFound
        sections.setSuperelevation("no-existe", emptyList()) shouldBe SectionSaveResult.AxisNotFound
    }
}
