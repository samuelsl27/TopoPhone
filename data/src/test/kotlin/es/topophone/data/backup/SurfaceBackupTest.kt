package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTile
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceDraft
import es.topophone.data.surface.SurfaceSummary
import es.topophone.data.surface.SurfaceTileWrite
import io.kotest.matchers.nulls.shouldBeNull
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

/**
 * Las superficies viajan en las copias y los paquetes con sus teselas, la que está activa y el desplazamiento vertical del proyecto
 * (S042/RF-21).
 */
@RunWith(AndroidJUnit4::class)
class SurfaceBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "surf-a-${System.nanoTime()}.db"
    private val theirs = "surf-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var surfaces: RoomSurfaceRepository
    private lateinit var project: String
    private var counter = 0

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
        surfaces = RoomSurfaceRepository(db)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con superficie")) as SaveResult.Saved).id
        surface("s1", "uno.xml")
        surface("s2", "dos.xml")
        surfaces.activate(project, "s2")
        surfaces.setVerticalOffset(project, 0.3)
        // Una superficie a medias, que no debe viajar.
        surfaces.begin(draft("s3", "a-medias.xml"))
        surfaces.addTiles("s3", listOf(tileWrite()))
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

    private fun draft(id: String, file: String) = SurfaceDraft(
        id,
        project,
        "terreno",
        file,
        1_792_346_000_000L + counter++,
        CadSourceCrs.Utm(GeodeticDatum.ED50, 30),
        LinearUnit.US_SURVEY_FOOT,
    )

    private val tile = SurfaceTile(
        doubleArrayOf(440000.123456789012, 440010.0, 440000.0),
        doubleArrayOf(4474000.987654321098, 4474000.0, 4474010.0),
        doubleArrayOf(600.0, 601.0, 602.0),
        intArrayOf(0, 1, 2),
    )

    private fun tileWrite() = SurfaceTileWrite(SurfaceExtent(440000.0, 440100.0, 4474000.0, 4474100.0), 1, SurfaceTileCodec.encode(tile))

    private suspend fun surface(id: String, file: String) {
        surfaces.begin(draft(id, file))
        surfaces.addTiles(id, listOf(tileWrite(), tileWrite()))
        surfaces.finish(id, SurfaceSummary(SurfaceExtent(1.0, 2.0, 3.0, 4.0), 600.0, 602.0, 3, 1, 0))
    }

    private fun file(name: String): File = context.getDatabasePath(name)

    private fun count(db: String, table: String) = rows(file(db), table).size

    private fun projectColumn(db: String, projectId: String, column: String): Any? {
        val cols = columns(file(db), "projects")
        val row = rows(file(db), "projects").first { it[cols.indexOf("id")] == projectId }
        return row[cols.indexOf(column)]
    }

    private fun writePackage() = ByteArrayOutputStream().also {
        runBlocking { repository.writePackage(project, it) } shouldBe true
    }.toByteArray()

    private fun import(pkg: ByteArray, choice: ImportChoice? = null): ImportResult {
        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        return runBlocking { otherRepository.importPackage(valid, choice, importedLabel = "importado") }
    }

    @Test
    fun `S042 RF-21 el paquete lleva las superficies y sus teselas`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "surfaces") shouldBe 2
        count(theirs, "surface_tiles") shouldBe 4
        val cols = columns(file(theirs), "surfaces")
        rows(file(theirs), "surfaces").map { it[cols.indexOf("file_name")] }.toSet() shouldBe setOf("uno.xml", "dos.xml")
        rows(file(theirs), "surfaces").all { it[cols.indexOf("status")] == "READY" } shouldBe true
    }

    @Test
    fun `S042 RF-21 el paquete conserva la activa y el desplazamiento`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        val otherSurfaces = RoomSurfaceRepository(other)
        runBlocking { otherSurfaces.activeSurfaceId(project).first() } shouldBe "s2"
        runBlocking { otherSurfaces.verticalOffset(project).first() } shouldBe 0.3
        val listed = runBlocking { otherSurfaces.surfaces(project).first() }
        listed.map { it.fileName } shouldBe listOf("uno.xml", "dos.xml")
        listed.first().source shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
        listed.first().unit shouldBe LinearUnit.US_SURVEY_FOOT
        listed.first().extent shouldBe SurfaceExtent(1.0, 2.0, 3.0, 4.0)
    }

    @Test
    fun `S042 RF-21 las teselas del paquete son los mismos bytes`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        val otherSurfaces = RoomSurfaceRepository(other)
        val cell = runBlocking { otherSurfaces.cells("s2") }.first()
        runBlocking { otherSurfaces.tile(cell.tileId) } shouldBe tile
        val original = rows(file(mine), "surface_tiles").map { it.last() }.toSet()
        val copied = rows(file(theirs), "surface_tiles").map { it.last() }.toSet()
        copied.size shouldBe 1
        original.containsAll(copied) shouldBe true
    }

    @Test
    fun `S042 RF-21 importar como nuevo da ids nuevos y activa propia`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        val second = import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        second.projectId shouldBe second.projectId.takeIf { it != project }
        count(theirs, "surfaces") shouldBe 4
        count(theirs, "surface_tiles") shouldBe 8
        val newActive = projectColumn(theirs, second.projectId, "active_surface_id") as String
        (newActive != "s2") shouldBe true
        val ids = columns(file(theirs), "surfaces").let { cols ->
            rows(file(theirs), "surfaces").filter { it[cols.indexOf("project_id")] == second.projectId }.map { it[cols.indexOf("id")] }
        }
        (newActive in ids) shouldBe true
        projectColumn(theirs, second.projectId, "vertical_offset_m") shouldBe 0.3
    }

    @Test
    fun `S042 RF-21 sustituir deja las superficies del paquete`() {
        val pkg = writePackage()
        runBlocking { surfaces.activate(project, "s1") }
        runBlocking { surfaces.setVerticalOffset(project, -1.0) }
        val valid = runBlocking { repository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()

        runBlocking {
            repository.importPackage(valid, ImportChoice.REPLACE, importedLabel = "importado")
        }.shouldBeInstanceOf<ImportResult.Imported>()

        runBlocking { surfaces.activeSurfaceId(project).first() } shouldBe "s2"
        runBlocking { surfaces.verticalOffset(project).first() } shouldBe 0.3
        runBlocking { surfaces.surfaces(project).first() }.map { it.id } shouldBe listOf("s1", "s2")
        // Las 2 del paquete y las 2 de la copia del móvil en la papelera; la que estaba a medias se va con el proyecto que se sustituye.
        count(mine, "surfaces") shouldBe 4
    }

    @Test
    fun `S042 RF-21 la copia completa restaura superficies y activa`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { surfaces.delete("s2") }
        runBlocking { surfaces.setVerticalOffset(project, 5.0) }
        runBlocking { surfaces.activeSurfaceId(project).first() }.shouldBeNull()

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { surfaces.surfaces(project).first() }.map { it.id } shouldBe listOf("s1", "s2")
        runBlocking { surfaces.activeSurfaceId(project).first() } shouldBe "s2"
        runBlocking { surfaces.verticalOffset(project).first() } shouldBe 0.3
        runBlocking { surfaces.cells("s2") }.size shouldBe 2
    }
}
