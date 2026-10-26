package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTile
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Guardar, listar, activar y borrar las superficies, y el desplazamiento vertical (S042/RF-11, RF-14, RF-16 a RF-19). */
@RunWith(AndroidJUnit4::class)
class SurfaceRepositoryTest {

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

    private fun draft(id: String, projectId: String = project, at: Long = 1_000L) = SurfaceDraft(
        id = id,
        projectId = projectId,
        name = "terreno",
        fileName = "obra.xml",
        importedAtMs = at,
        source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29),
        unit = LinearUnit.INTERNATIONAL_FOOT,
    )

    private val summary = SurfaceSummary(SurfaceExtent(440000.0, 440100.0, 4474000.0, 4474100.0), 600.0, 608.7, 42, 58, 2)

    private val tile = SurfaceTile(
        doubleArrayOf(440000.123456789, 440010.0, 440000.0),
        doubleArrayOf(4474000.000000001, 4474000.0, 4474010.0),
        doubleArrayOf(600.0, 601.0, 602.0),
        intArrayOf(0, 1, 2),
    )

    private fun tileWrite() = SurfaceTileWrite(summary.extent, 1, SurfaceTileCodec.encode(tile))

    private fun write(id: String, at: Long = 1_000L, projectId: String = project): Boolean = runBlocking {
        surfaces.begin(draft(id, projectId, at))
        surfaces.addTiles(id, listOf(tileWrite()))
        surfaces.finish(id, summary)
    }

    @Test
    fun `S042 RF-11 una superficie sin terminar no existe para el resto de la app`() = runBlocking<Unit> {
        surfaces.begin(draft("s1"))
        surfaces.addTiles("s1", listOf(tileWrite()))

        surfaces.surfaces(project).first() shouldBe emptyList()
        surfaces.activeSurfaceId(project).first().shouldBeNull()
    }

    @Test
    fun `S042 RF-11 abortar borra la superficie a medias con sus teselas`() = runBlocking<Unit> {
        surfaces.begin(draft("s1"))
        surfaces.addTiles("s1", listOf(tileWrite()))

        surfaces.abort("s1")

        surfaces.cells("s1") shouldBe emptyList()
        surfaces.cleanUpUnfinished() shouldBe 0
    }

    @Test
    fun `S042 RF-11 al arrancar se borra lo que quedo a medias y se conserva lo terminado`() = runBlocking<Unit> {
        write("s1")
        surfaces.begin(draft("s2", at = 2_000L))
        surfaces.addTiles("s2", listOf(tileWrite()))

        surfaces.cleanUpUnfinished() shouldBe 1

        surfaces.surfaces(project).first().map { it.id } shouldBe listOf("s1")
        surfaces.cells("s2") shouldBe emptyList()
    }

    @Test
    fun `S042 RF-14 al terminar la superficie aparece con su origen, su unidad, su extension y sus recuentos`() = runBlocking<Unit> {
        write("s1")

        val listed = surfaces.surfaces(project).first().single()

        listed.id shouldBe "s1"
        listed.name shouldBe "terreno"
        listed.fileName shouldBe "obra.xml"
        listed.importedAtMs shouldBe 1_000L
        listed.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
        listed.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        listed.extent shouldBe summary.extent
        listed.minElevationM shouldBe 600.0
        listed.maxElevationM shouldBe 608.7
        listed.vertexCount shouldBe 42
        listed.triangleCount shouldBe 58
        listed.holeCount shouldBe 2
    }

    @Test
    fun `S042 RF-14 la tesela vuelve de la base de datos sin redondear`() = runBlocking<Unit> {
        write("s1")

        val cell = surfaces.cells("s1").single()

        cell.region shouldBe SurfaceExtent(440000.0, 440100.0, 4474000.0, 4474100.0)
        cell.triangleCount shouldBe 1
        surfaces.tile(cell.tileId) shouldBe tile
        surfaces.tile(999L).shouldBeNull()
    }

    @Test
    fun `S042 RF-17 la primera superficie de un proyecto queda activa y las siguientes no`() = runBlocking<Unit> {
        write("s1", at = 1_000L) shouldBe true
        write("s2", at = 2_000L) shouldBe false

        surfaces.activeSurfaceId(project).first() shouldBe "s1"
        surfaces.surfaces(project).first().map { it.id } shouldBe listOf("s1", "s2")
    }

    @Test
    fun `S042 RF-16 activar otra superficie o ninguna se hace en un toque y solo hay una activa`() = runBlocking<Unit> {
        write("s1")
        write("s2", at = 2_000L)

        surfaces.activate(project, "s2") shouldBe true
        surfaces.activeSurfaceId(project).first() shouldBe "s2"
        surfaces.activate(project, null) shouldBe true
        surfaces.activeSurfaceId(project).first().shouldBeNull()
        surfaces.activate(project, "s1") shouldBe true
        surfaces.activeSurfaceId(project).first() shouldBe "s1"
    }

    @Test
    fun `S042 RF-16 no se activa una superficie que no existe, que esta a medias o que es de otro proyecto`() = runBlocking<Unit> {
        val other = newProject("Otra obra")
        write("s1", projectId = other)
        surfaces.begin(draft("s2"))

        surfaces.activate(project, "s1") shouldBe false
        surfaces.activate(project, "s2") shouldBe false
        surfaces.activate(project, "no-existe") shouldBe false
        surfaces.activeSurfaceId(project).first().shouldBeNull()
    }

    @Test
    fun `S042 RF-18 borrar la superficie activa deja el proyecto sin ninguna y borra sus teselas`() = runBlocking<Unit> {
        write("s1")
        write("s2", at = 2_000L)

        surfaces.delete("s1")

        surfaces.activeSurfaceId(project).first().shouldBeNull()
        surfaces.surfaces(project).first().map { it.id } shouldBe listOf("s2")
        surfaces.cells("s1") shouldBe emptyList()
        surfaces.cells("s2").size shouldBe 1
    }

    @Test
    fun `S042 RF-18 borrar una superficie que no es la activa no cambia la activa`() = runBlocking<Unit> {
        write("s1")
        write("s2", at = 2_000L)

        surfaces.delete("s2")

        surfaces.activeSurfaceId(project).first() shouldBe "s1"
    }

    @Test
    fun `S042 RF-19 el desplazamiento vertical empieza en 0 y se conserva al cambiar o borrar la superficie`() = runBlocking<Unit> {
        write("s1")
        write("s2", at = 2_000L)
        surfaces.verticalOffset(project).first() shouldBe 0.0

        surfaces.setVerticalOffset(project, -0.45)
        surfaces.activate(project, "s2")
        surfaces.delete("s1")
        surfaces.delete("s2")

        surfaces.verticalOffset(project).first() shouldBe -0.45
    }

    @Test
    fun `S042 RF-19 el desplazamiento vertical se guarda sin redondear y solo si es un numero`() = runBlocking<Unit> {
        surfaces.setVerticalOffset(project, 0.1234567891234)
        surfaces.verticalOffset(project).first() shouldBe 0.1234567891234

        shouldThrow<IllegalArgumentException> { surfaces.setVerticalOffset(project, Double.NaN) }
        shouldThrow<IllegalArgumentException> { surfaces.setVerticalOffset(project, Double.POSITIVE_INFINITY) }
        surfaces.verticalOffset(project).first() shouldBe 0.1234567891234
    }

    @Test
    fun `S042 RF-17 cada proyecto tiene sus superficies y su activa`() = runBlocking<Unit> {
        val other = newProject("Otra obra")
        write("s1")
        write("s2", projectId = other)

        surfaces.surfaces(project).first().map { it.id } shouldBe listOf("s1")
        surfaces.surfaces(other).first().map { it.id } shouldBe listOf("s2")
        surfaces.activeSurfaceId(other).first() shouldBe "s2"
    }
}
