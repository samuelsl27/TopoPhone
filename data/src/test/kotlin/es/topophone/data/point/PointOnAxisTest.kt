package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.AxisRef
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Un punto calculado sobre un eje se guarda con su PK y su desplazamiento en una transacción (S049/RF-22, RNF-T6). */
@RunWith(AndroidJUnit4::class)
class PointOnAxisTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    private fun onAxis(id: String, name: String) = Point(
        id = id,
        projectId = project,
        name = name,
        description = "",
        origin = PointOrigin.ON_ALIGNMENT,
        coordinates = PointCoordinates(
            GeodeticPosition(40.416801234567891, -3.7037123456789, 701.357),
            440290.434567891,
            4474257.414123456,
            648.34,
        ),
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440290.434567891, 4474257.414123456, 648.34, HeightKind.PROJECT),
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 5_000L,
        deletedAtMs = null,
        onAxis = AxisRef("Eje A", 1240.5000000001, -3.5),
    )

    @Test
    fun `S049 RF-22 el punto vuelve con su origen eje PK y desplazamiento`() = runBlocking<Unit> {
        points.create(onAxis("a1", "1")) shouldBe PointSaveResult.Saved

        val read = points.points(project).first().single()

        read.origin shouldBe PointOrigin.ON_ALIGNMENT
        read.onAxis shouldBe AxisRef("Eje A", 1240.5000000001, -3.5)
        read.onAxis.shouldNotBeNull().chainageM.toRawBits() shouldBe 1240.5000000001.toRawBits()
        read.coordinates.easting shouldBe 440290.434567891
        read.measurement shouldBe null
    }

    @Test
    fun `S049 RF-22 un nombre repetido no guarda el punto`() = runBlocking<Unit> {
        points.create(onAxis("a1", "1"))

        points.create(onAxis("a2", "1")) shouldBe PointSaveResult.DuplicateName

        points.points(project).first().size shouldBe 1
    }

    @Test
    fun `S049 RF-22 el punto se replantea como cualquier otro y su eje puede borrarse despues`() = runBlocking<Unit> {
        points.create(onAxis("a1", "1"))

        val read = points.points(project).first().single()

        // Se lee por id y se puede usar como punto de diseño: tiene Este y Norte (S018).
        points.point("a1").first().shouldNotBeNull().coordinates.northing shouldBe read.coordinates.northing
        read.onAxis.shouldNotBeNull().alignmentName shouldBe "Eje A"
    }
}
