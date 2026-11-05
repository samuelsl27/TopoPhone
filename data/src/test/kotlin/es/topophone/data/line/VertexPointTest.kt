package es.topophone.data.line

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Los vértices llegan con su altura y su posición geodésica, sin leer todos los puntos (S026/RF-3, RF-5). */
@RunWith(AndroidJUnit4::class)
class VertexPointTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private val projects = RoomProjectRepository(db, clock = { 1_000L })
    private val points = RoomPointRepository(db, clock = { 1_000L })
    private val dao = db.lines()

    @After
    fun close() = db.close()

    private val project: String = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Parcela")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun point(name: String, height: Double?, ellipsoidal: Double?) = Point(
        id = "id-$name",
        projectId = project,
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, ellipsoidal), 440_000.0, 4_472_000.5, height),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 3_000,
        deletedAtMs = null,
    )

    private suspend fun line(vararg ids: String) {
        dao.insert(ProjectLineEntity("l1", project, "L_1", "LINE", false, false, false, null, null, null, null, 5_000))
        dao.insertVertices(ids.mapIndexed { i, p -> LineVertexEntity("l1", i, p) })
    }

    @Test
    fun `S026 RF-3 el vertice trae la altura del proyecto, la elipsoidal y la latitud y longitud`() = runBlocking<Unit> {
        points.create(point("1", 650.25, 700.0))
        points.create(point("2", null, null))
        line("id-1", "id-2")

        val vertices = RoomLineRepository(db, points, clock = { 1_000L }).vertexPoints("l1").first()

        vertices.size shouldBe 2
        vertices[0].height shouldBe 650.25
        vertices[0].ellipsoidalHeightM shouldBe 700.0
        vertices[0].latitudeDeg shouldBe 40.4
        vertices[0].longitudeDeg shouldBe -3.7
        vertices[0].easting shouldBe 440_000.0
        vertices[1].height shouldBe null
        vertices[1].ellipsoidalHeightM shouldBe null
    }

    @Test
    fun `S026 RF-5 al editar la altura de un punto la lectura de los vertices cambia`() = runBlocking<Unit> {
        points.create(point("1", 650.0, 700.0))
        points.create(point("2", 651.0, 701.0))
        line("id-1", "id-2")
        val repository = RoomLineRepository(db, points, clock = { 1_000L })
        repository.vertexPoints("l1").first()[0].height shouldBe 650.0

        points.update(point("1", 660.5, 710.5), PointChange.EDIT)

        repository.vertexPoints("l1").first()[0].height shouldBe 660.5
    }
}
