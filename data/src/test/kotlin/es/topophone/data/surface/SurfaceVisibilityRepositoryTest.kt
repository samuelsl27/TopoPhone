package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.core.survey.TinOutline
import es.topophone.core.survey.TinTiler
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** El nombre por defecto, la visibilidad y el contorno de las superficies guardadas (S061/RF-2, RF-3, RF-5, RF-6). */
@RunWith(AndroidJUnit4::class)
class SurfaceVisibilityRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomSurfaceRepository(db)
    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    private val e0 = 440_000.0
    private val n0 = 4_474_000.0

    /** Una malla de [side] × [side] celdas de 2 m sin la celda del centro, en teselas pequeñas (con triángulos repetidos). */
    private class Grid(side: Int, e0: Double, n0: Double) {
        val w = side + 1
        val easting = DoubleArray(w * w) { e0 + (it % w) * 2.0 }
        val northing = DoubleArray(w * w) { n0 + (it / w) * 2.0 }
        val height = DoubleArray(w * w) { 600.0 + (it % w) * 0.01 }
        val triangles = buildList {
            for (j in 0 until side) {
                for (i in 0 until side) {
                    if (i == side / 2 && j == side / 2) continue
                    val a = j * w + i
                    addAll(listOf(a, a + 1, a + w + 1, a, a + w + 1, a + w))
                }
            }
        }.toIntArray()
    }

    private fun write(id: String, name: String, fileName: String, grid: Grid = Grid(10, e0, n0)) = runBlocking {
        repository.begin(SurfaceDraft(id, project, name, fileName, 1L, CadSourceCrs.Project, LinearUnit.METRE))
        val cells = TinTiler.tile(grid.easting, grid.northing, grid.height, grid.triangles, maxTriangles = 20)
        repository.addTiles(id, cells.map { SurfaceTileWrite(it.region, it.tile.triangleCount, SurfaceTileCodec.encode(it.tile)) })
        val extent = SurfaceExtent(grid.easting.min(), grid.easting.max(), grid.northing.min(), grid.northing.max())
        repository.finish(id, SurfaceSummary(extent, 600.0, 600.1, grid.easting.size, grid.triangles.size / 3, 0))
    }

    @Test
    fun `S061 RF-2 una superficie guardada sin nombre se lee con el del fichero`() = runBlocking<Unit> {
        write("s1", "", "explanacion.xml")
        write("s2", "terreno", "obra.xml")

        repository.surfaces(project).first().map { it.name } shouldBe listOf("explanacion", "terreno")
    }

    @Test
    fun `S061 RF-3 la visibilidad se cambia y se lee con la superficie`() = runBlocking<Unit> {
        write("s1", "terreno", "obra.xml")
        repository.surfaces(project).first().single().visible shouldBe false

        repository.setVisible("s1", true)
        repository.surfaces(project).first().single().visible shouldBe true

        repository.setVisible("s1", false)
        repository.surfaces(project).first().single().visible shouldBe false
    }

    @Test
    fun `S061 RF-6 el contorno que falta se calcula con las teselas y se guarda`() = runBlocking<Unit> {
        val grid = Grid(10, e0, n0)
        write("s1", "terreno", "obra.xml", grid)
        db.surfaces().outlineParts("s1").shouldBeEmpty()

        val outline = repository.outline("s1").shouldNotBeNull()

        val expected = TinOutline.rings(grid.easting, grid.northing, grid.triangles)
        outline.rings.map { it.exterior to it.vertexCount }.sortedBy { it.second } shouldBe
            expected.rings.map { it.exterior to it.vertexCount }.sortedBy { it.second }
        outline.rings.map { it.exterior }.sorted() shouldBe listOf(false, true) // el exterior y el hueco de la celda central
        db.surfaces().outlineParts("s1").shouldNotBeEmpty()
        repository.outline("s1") shouldBe outline
    }

    @Test
    fun `S061 RF-6 sin superficie no hay contorno y al borrarla se va el suyo`() = runBlocking<Unit> {
        repository.outline("no-existe").shouldBeNull()
        write("s1", "terreno", "obra.xml")
        repository.outline("s1").shouldNotBeNull()

        repository.delete("s1")

        db.surfaces().outlineParts("s1").shouldBeEmpty()
    }
}
