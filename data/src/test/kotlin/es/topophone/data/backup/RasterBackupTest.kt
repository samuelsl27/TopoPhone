package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.RasterInfo
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.RasterTileCodec
import es.topophone.core.model.surface.RasterValueType
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceKind
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RasterDraft
import es.topophone.data.surface.RasterSummary
import es.topophone.data.surface.RasterTileWrite
import es.topophone.data.surface.RoomSurfaceRepository
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
 * Los rásteres viajan en las copias y los paquetes con sus bloques, el que está activo y el desplazamiento vertical del proyecto
 * (S045/RF-20).
 */
@RunWith(AndroidJUnit4::class)
class RasterBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "rast-a-${System.nanoTime()}.db"
    private val theirs = "rast-b-${System.nanoTime()}.db"
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
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con raster")) as SaveResult.Saved).id
        raster("r1", "uno.tif")
        raster("r2", "dos.tif")
        surfaces.activate(project, "r2")
        surfaces.setVerticalOffset(project, 0.3)
        // Un ráster a medias, que no debe viajar.
        surfaces.beginRaster(draft("r3", "a-medias.tif"))
        surfaces.addRasterTiles("r3", listOf(tileWrite(0, 0)))
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

    private val grid = RasterInfo(
        columns = 300, rows = 100, originX = 440005.0, originY = 4474005.0, stepX = 10.0, stepY = 10.0,
        valueType = RasterValueType.FLOAT64, heightSystem = HeightSystem.ELLIPSOIDAL, dataCells = 0, noDataCells = 0,
    )

    private fun draft(id: String, file: String) = RasterDraft(
        id,
        project,
        "mdt",
        file,
        1_792_346_000_000L + counter++,
        CadSourceCrs.Utm(GeodeticDatum.ED50, 30),
        LinearUnit.US_SURVEY_FOOT,
        grid,
    )

    private val tile = RasterTile.ofDoubles(DoubleArray(RasterTile.CELLS) { 600.123456789012 + it / 1e9 })

    private fun tileWrite(col: Int, row: Int) = RasterTileWrite(col, row, RasterTileCodec.encode(tile))

    private suspend fun raster(id: String, file: String) {
        surfaces.beginRaster(draft(id, file))
        surfaces.addRasterTiles(id, listOf(tileWrite(0, 0), tileWrite(1, 0), tileWrite(2, 0)))
        surfaces.finishRaster(id, RasterSummary(SurfaceExtent(1.0, 2.0, 3.0, 4.0), 600.0, 700.5, 29_990, 10))
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
    fun `S045 RF-20 el paquete lleva los rasters y sus bloques`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "surfaces") shouldBe 2
        count(theirs, "raster_tiles") shouldBe 6
        val cols = columns(file(theirs), "surfaces")
        rows(file(theirs), "surfaces").map { it[cols.indexOf("file_name")] }.toSet() shouldBe setOf("uno.tif", "dos.tif")
        rows(file(theirs), "surfaces").all { it[cols.indexOf("status")] == "READY" && it[cols.indexOf("kind")] == "RASTER" } shouldBe true
    }

    @Test
    fun `S045 RF-20 el paquete conserva la malla la activa y el desplazamiento`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        val otherSurfaces = RoomSurfaceRepository(other)
        runBlocking { otherSurfaces.activeSurfaceId(project).first() } shouldBe "r2"
        runBlocking { otherSurfaces.verticalOffset(project).first() } shouldBe 0.3
        val listed = runBlocking { otherSurfaces.surfaces(project).first() }
        listed.map { it.fileName } shouldBe listOf("uno.tif", "dos.tif")
        listed.first().kind shouldBe SurfaceKind.RASTER
        listed.first().source shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
        listed.first().unit shouldBe LinearUnit.US_SURVEY_FOOT
        listed.first().raster shouldBe grid.copy(dataCells = 29_990, noDataCells = 10)
    }

    @Test
    fun `S045 RF-20 los bloques del paquete son los mismos sin redondear`() {
        import(writePackage()).shouldBeInstanceOf<ImportResult.Imported>()

        val otherSurfaces = RoomSurfaceRepository(other)
        runBlocking { otherSurfaces.rasterTile("r2", 0, 0) } shouldBe tile
        runBlocking { otherSurfaces.rasterTile("r2", 2, 0) } shouldBe tile
        val original = rows(file(mine), "raster_tiles").map { it.last() }.toSet()
        val copied = rows(file(theirs), "raster_tiles").map { it.last() }.toSet()
        copied.size shouldBe 1
        original.containsAll(copied) shouldBe true
    }

    @Test
    fun `S045 RF-20 importar como nuevo da ids nuevos y activo propio`() {
        val pkg = writePackage()
        import(pkg).shouldBeInstanceOf<ImportResult.Imported>()

        val second = import(pkg, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "surfaces") shouldBe 4
        count(theirs, "raster_tiles") shouldBe 12
        val newActive = projectColumn(theirs, second.projectId, "active_surface_id") as String
        (newActive != "r2") shouldBe true
        val ids = columns(file(theirs), "surfaces").let { cols ->
            rows(file(theirs), "surfaces").filter { it[cols.indexOf("project_id")] == second.projectId }.map { it[cols.indexOf("id")] }
        }
        (newActive in ids) shouldBe true
        projectColumn(theirs, second.projectId, "vertical_offset_m") shouldBe 0.3
        val newTiles = runBlocking { RoomSurfaceRepository(other).rasterTile(newActive, 1, 0) }
        newTiles shouldBe tile
    }

    @Test
    fun `S045 RF-20 sustituir deja los rasters del paquete`() {
        val pkg = writePackage()
        runBlocking { surfaces.activate(project, "r1") }
        runBlocking { surfaces.setVerticalOffset(project, -1.0) }
        val valid = runBlocking { repository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()

        runBlocking {
            repository.importPackage(valid, ImportChoice.REPLACE, importedLabel = "importado")
        }.shouldBeInstanceOf<ImportResult.Imported>()

        runBlocking { surfaces.activeSurfaceId(project).first() } shouldBe "r2"
        runBlocking { surfaces.verticalOffset(project).first() } shouldBe 0.3
        runBlocking { surfaces.surfaces(project).first() }.map { it.id } shouldBe listOf("r1", "r2")
        runBlocking { surfaces.rasterTile("r2", 1, 0) } shouldBe tile
    }

    @Test
    fun `S045 RF-20 la copia completa restaura rasters y activo`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { surfaces.delete("r2") }
        runBlocking { surfaces.setVerticalOffset(project, 5.0) }
        runBlocking { surfaces.activeSurfaceId(project).first() } shouldBe null

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        runBlocking { surfaces.surfaces(project).first() }.map { it.id } shouldBe listOf("r1", "r2")
        runBlocking { surfaces.activeSurfaceId(project).first() } shouldBe "r2"
        runBlocking { surfaces.verticalOffset(project).first() } shouldBe 0.3
        runBlocking { surfaces.rasterTile("r2", 2, 0) } shouldBe tile
        runBlocking { surfaces.rasterTile("r3", 0, 0) } shouldBe null
    }
}
