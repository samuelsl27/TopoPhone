package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import es.topophone.core.model.surface.SurfaceTile
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Guardar, listar, activar y borrar los rásteres de cotas entre las superficies del proyecto (S045/RF-13, RF-14, RF-17, RF-18). */
@RunWith(AndroidJUnit4::class)
class RasterRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val surfaces = RoomSurfaceRepository(db)

    @After
    fun close() = db.close()

    private fun newProject(name: String) = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val project = newProject("Calle Mayor 12")

    private val grid = RasterInfo(
        columns = 300, rows = 200, originX = 440005.0, originY = 4474005.0, stepX = 10.0, stepY = 10.0,
        valueType = RasterValueType.FLOAT64, heightSystem = HeightSystem.ELLIPSOIDAL, dataCells = 0, noDataCells = 0,
    )

    private fun draft(id: String, at: Long = 1_000L) = RasterDraft(
        id = id,
        projectId = project,
        name = "mdt05",
        fileName = "mdt05.tif",
        importedAtMs = at,
        source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29),
        unit = LinearUnit.INTERNATIONAL_FOOT,
        grid = grid,
    )

    private val extent = SurfaceExtent(440005.0, 442995.0, 4473015.0, 4474005.0)

    private val summary = RasterSummary(extent, 600.123456789, 701.5, 59_990, 10)

    private fun tile(seed: Double) = RasterTile.ofDoubles(DoubleArray(RasterTile.CELLS) { seed + it / 1e7 })

    private fun write(id: String, at: Long = 1_000L): Boolean = runBlocking {
        surfaces.beginRaster(draft(id, at))
        surfaces.addRasterTiles(
            id,
            listOf(RasterTileWrite(0, 0, RasterTileCodec.encode(tile(600.0))), RasterTileWrite(1, 0, RasterTileCodec.encode(tile(610.0)))),
        )
        surfaces.finishRaster(id, summary)
    }

    private fun writeTin(id: String, at: Long = 500L): Boolean = runBlocking {
        surfaces.begin(
            SurfaceDraft(id, project, "terreno", "obra.xml", at, CadSourceCrs.Project, LinearUnit.METRE),
        )
        val tin =
            SurfaceTile(
                doubleArrayOf(0.0, 10.0, 0.0),
                doubleArrayOf(0.0, 0.0, 10.0),
                doubleArrayOf(600.0, 601.0, 602.0),
                intArrayOf(0, 1, 2),
            )
        surfaces.addTiles(id, listOf(SurfaceTileWrite(SurfaceExtent(0.0, 10.0, 0.0, 10.0), 1, SurfaceTileCodec.encode(tin))))
        surfaces.finish(id, SurfaceSummary(SurfaceExtent(0.0, 10.0, 0.0, 10.0), 600.0, 602.0, 3, 1, 0))
    }

    @Test
    fun `S045 RF-13 un raster sin terminar no existe para la app`() = runBlocking<Unit> {
        surfaces.beginRaster(draft("r1"))
        surfaces.addRasterTiles("r1", listOf(RasterTileWrite(0, 0, RasterTileCodec.encode(tile(600.0)))))

        surfaces.surfaces(project).first() shouldBe emptyList()
        surfaces.activeSurfaceId(project).first().shouldBeNull()
        surfaces.rasterTile("r1", 0, 0).shouldBeNull() // un raster a medias tampoco da bloques
    }

    @Test
    fun `S045 RF-13 abortar borra el raster a medias y sus bloques`() = runBlocking<Unit> {
        surfaces.beginRaster(draft("r1"))
        surfaces.addRasterTiles("r1", listOf(RasterTileWrite(0, 0, RasterTileCodec.encode(tile(600.0)))))

        surfaces.abort("r1")

        db.surfaces().rasterTileCount("r1") shouldBe 0
        surfaces.cleanUpUnfinished() shouldBe 0
    }

    @Test
    fun `S045 RF-13 al arrancar se borra el raster a medias y se conserva lo terminado`() = runBlocking<Unit> {
        write("r1")
        surfaces.beginRaster(draft("r2", at = 2_000L))
        surfaces.addRasterTiles("r2", listOf(RasterTileWrite(0, 0, RasterTileCodec.encode(tile(600.0)))))

        surfaces.cleanUpUnfinished() shouldBe 1

        surfaces.surfaces(project).first().map { it.id } shouldBe listOf("r1")
        db.surfaces().rasterTileCount("r2") shouldBe 0
        db.surfaces().rasterTileCount("r1") shouldBe 2
    }

    @Test
    fun `S045 RF-17 al terminar el raster aparece con su malla su unidad y sus recuentos`() = runBlocking<Unit> {
        write("r1")

        val listed = surfaces.surfaces(project).first().single()

        listed.kind shouldBe SurfaceKind.RASTER
        listed.id shouldBe "r1"
        listed.name shouldBe "mdt05"
        listed.fileName shouldBe "mdt05.tif"
        listed.importedAtMs shouldBe 1_000L
        listed.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
        listed.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        listed.extent shouldBe extent
        listed.minElevationM shouldBe 600.123456789
        listed.maxElevationM shouldBe 701.5
        listed.vertexCount shouldBe 0
        listed.triangleCount shouldBe 0
        listed.raster shouldBe grid.copy(dataCells = 59_990, noDataCells = 10)
    }

    @Test
    fun `S045 RF-14 el bloque vuelve de la base de datos sin redondear`() = runBlocking<Unit> {
        write("r1")

        surfaces.rasterTile("r1", 0, 0) shouldBe tile(600.0)
        surfaces.rasterTile("r1", 1, 0) shouldBe tile(610.0)
        surfaces.rasterTile("r1", 2, 0).shouldBeNull()
        surfaces.rasterTile("otra", 0, 0).shouldBeNull()
    }

    @Test
    fun `S045 RF-18 activar un raster con un TIN activo deja inactivo el TIN y a la inversa`() = runBlocking<Unit> {
        writeTin("t1") shouldBe true
        write("r1") shouldBe false
        surfaces.activeSurfaceId(project).first() shouldBe "t1"

        surfaces.activate(project, "r1") shouldBe true
        surfaces.activeSurfaceId(project).first() shouldBe "r1"

        surfaces.activate(project, "t1") shouldBe true
        surfaces.activeSurfaceId(project).first() shouldBe "t1"
        surfaces.surfaces(project).first().map { it.kind } shouldBe listOf(SurfaceKind.TIN, SurfaceKind.RASTER)
    }

    @Test
    fun `S045 RF-17 el primer raster del proyecto queda activo`() = runBlocking<Unit> {
        write("r1") shouldBe true

        surfaces.activeSurfaceId(project).first() shouldBe "r1"
    }

    @Test
    fun `S045 RF-17 borrar el raster activo deja el proyecto sin ninguna y borra sus bloques`() = runBlocking<Unit> {
        write("r1")
        surfaces.setVerticalOffset(project, 0.3)

        surfaces.delete("r1")

        surfaces.activeSurfaceId(project).first().shouldBeNull()
        surfaces.surfaces(project).first() shouldBe emptyList()
        db.surfaces().rasterTileCount("r1") shouldBe 0
        surfaces.verticalOffset(project).first() shouldBe 0.3
    }

    @Test
    fun `S045 RF-19 el desplazamiento vertical se conserva al pasar de un TIN a un raster`() = runBlocking<Unit> {
        writeTin("t1")
        write("r1")
        surfaces.setVerticalOffset(project, -0.25)

        surfaces.activate(project, "r1")

        surfaces.verticalOffset(project).first() shouldBe -0.25
    }

    @Test
    fun `S045 RF-14 un raster no cambia lo que hace el TIN al listar`() = runBlocking<Unit> {
        writeTin("t1")

        val tin = surfaces.surfaces(project).first().single()

        tin.kind shouldBe SurfaceKind.TIN
        tin.raster.shouldBeNull()
        tin.triangleCount shouldBe 1
    }
}
