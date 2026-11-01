package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.alignment.section.TemplateSegment
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Las secciones, las secciones tipo y los peraltes viajan en las copias y los paquetes con su eje (S050/RF-8). */
@RunWith(AndroidJUnit4::class)
class SectionBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "sect-a-${System.nanoTime()}.db"
    private val theirs = "sect-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var alignments: RoomAlignmentRepository
    private lateinit var sections: RoomSectionRepository
    private lateinit var project: String
    private lateinit var ids: List<String>

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.13.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.13.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        alignments = RoomAlignmentRepository(db)
        sections = RoomSectionRepository(db)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con secciones")) as SaveResult.Saved).id
        ids = alignments.add(project, listOf(item("Eje A")), 1_792_346_000_000L).ids
        sections.addTemplate(ids[0], template)
        sections.addSections(ids[0], listOf(section(1100.0), section(1150.0, SectionKind.GROUND)))
        sections.setSuperelevation(ids[0], listOf(SuperelevationRow(1000.5, 0.02, 0.02), SuperelevationRow(1100.0, -0.06, 0.06)))
        Unit
    }

    @After
    fun close() {
        settings.close()
        db.close()
        other.close()
        context.deleteDatabase(mine)
        context.deleteDatabase(theirs)
    }

    private val template = SectionTemplate(
        1000.5,
        1180.5,
        "BD",
        listOf(TemplateSegment("BI", 3.500000000000001, 0.02, true)),
        listOf(TemplateSegment("BD", 3.5, 0.02, true)),
    )

    private fun section(chainage: Double, kind: SectionKind = SectionKind.DESIGN) = CrossSection(
        kind,
        chainage,
        HeightKind.ABSOLUTE,
        listOf(SectionPoint("BI", -3.500000000000001, 100.12345678901234), SectionPoint("BD", 3.5, 100.1)),
    )

    private fun item(name: String) = AlignmentToSave(
        name = name,
        fileName = null,
        source = AlignmentSource.MANUAL,
        elements = listOf(AlignmentElement.line(1000.5, 180.0, 440000.0, 4474000.0, 1.0471975511965976)),
        profile = VerticalProfile(listOf(VerticalVertex(1000.5, 50.0), VerticalVertex(1180.5, 49.0))),
        extent = AlignmentExtent(440000.0, 440200.0, 4474000.0, 4474100.0),
        approximated = false,
    )

    private fun file(name: String): File = context.getDatabasePath(name)

    private fun count(db: String, table: String) = rows(file(db), table).size

    private fun writePackage() = ByteArrayOutputStream().also {
        runBlocking { repository.writePackage(project, it) } shouldBe true
    }.toByteArray()

    private fun import(pkg: ByteArray, choice: ImportChoice? = null): ImportResult {
        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        return runBlocking { otherRepository.importPackage(valid, choice, importedLabel = "importado") }
    }

    @Test
    fun `S050 RF-8 el paquete lleva las secciones el peralte y la seccion tipo`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "section_templates") shouldBe 1
        count(theirs, "section_profiles") shouldBe 2
        count(theirs, "superelevation_rows") shouldBe 2
        val other = RoomSectionRepository(other)
        runBlocking { other.templates(ids[0]).first() }.single().template shouldBe template
        runBlocking { other.sections(ids[0]).first() }.map { it.section } shouldBe
            listOf(section(1100.0), section(1150.0, SectionKind.GROUND))
        runBlocking { other.sections(ids[0]).first() }[0].section.points[0].heightM.toRawBits() shouldBe 100.12345678901234.toRawBits()
        runBlocking { other.superelevation(ids[0]).first() } shouldBe
            listOf(SuperelevationRow(1000.5, 0.02, 0.02), SuperelevationRow(1100.0, -0.06, 0.06))
    }

    @Test
    fun `S050 RF-8 importar como nuevo copia las secciones al eje nuevo`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "section_templates") shouldBe 2
        count(theirs, "section_profiles") shouldBe 4
        count(theirs, "superelevation_rows") shouldBe 4
        val cols = columns(file(theirs), "section_profiles")
        val owners = rows(file(theirs), "section_profiles").map { it[cols.indexOf("alignment_id")] }.toSet()
        owners.size shouldBe 2
        val alignmentCols = columns(file(theirs), "alignments")
        val axes = rows(file(theirs), "alignments").map { it[alignmentCols.indexOf("id")] }.toSet()
        (owners - axes).isEmpty() shouldBe true
    }

    @Test
    fun `S050 RF-8 la copia completa restaura las secciones borradas`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { alignments.delete(ids[0]) }
        count(mine, "section_profiles") shouldBe 0

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { sections.templates(ids[0]).first() }.single().template shouldBe template
        runBlocking { sections.sections(ids[0]).first() }.size shouldBe 2
        runBlocking { sections.superelevation(ids[0]).first() }.size shouldBe 2
    }
}
