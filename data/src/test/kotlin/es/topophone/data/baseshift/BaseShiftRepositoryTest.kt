package es.topophone.data.baseshift

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.BaseShift
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** La lista de desplazamientos de base de un proyecto, el activo y los puntos intactos (S020/RF-9, RF-12, RF-16). */
@RunWith(AndroidJUnit4::class)
class BaseShiftRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private val projects = RoomProjectRepository(db)
    private val shifts = RoomBaseShiftRepository(db)
    private val points = RoomPointRepository(db)

    @After
    fun close() = db.close()

    private fun newProject(name: String) = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val project = newProject("Camino de la Dehesa")

    private fun shift(id: String, projectId: String = project, datum: CorrectionsDatum = CorrectionsDatum.ETRS89, at: Long = 1L) =
        BaseShift(
            id = id,
            projectId = projectId,
            createdAtMs = at,
            fromReplay = false,
            knownName = "V-$id",
            knownPointId = null,
            knownEasting = 440_290.123_456_789,
            knownNorthing = 4_474_257.000_000_001,
            knownHeightM = 650.123_456_789,
            measured = GeodeticPosition(40.416_784_692_131_955, -3.703_775_259_501_478, 651.323_000_573_553_1),
            epochsUsed = 60,
            vector = BaseShiftVector(-2.147_247_769_869_864, -1.965_396_083_076_484_5, 0.516_280_195_210_129),
            horizontalOnly = false,
            correctionsDatum = datum,
            active = false,
            base = GeodeticPosition(43.5, -5.9, 78.4),
        )

    private fun point() = Point.measured(
        id = "p1",
        projectId = project,
        name = "P1",
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416_801_234_567_89, -3.703_712_345_678_9, 701.357_123_456),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1,
            measuredToMs = 2,
            coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), 440_290.4, 4_474_257.4, 648.3),
        ),
        createdAtMs = 3,
    )

    @Test
    fun `S020 RF-12 un desplazamiento se guarda y se lee igual sin redondear`() = runBlocking<Unit> {
        shifts.add(shift("a"), activate = false)

        val read = shifts.shifts(project).first().single()

        read shouldBe shift("a")
        read.vector.dx.toRawBits() shouldBe (-2.147_247_769_869_864).toRawBits()
        read.knownNorthing.toRawBits() shouldBe 4_474_257.000_000_001.toRawBits()
    }

    @Test
    fun `S020 RF-12 al activar uno el anterior se desactiva y solo hay uno activo`() = runBlocking<Unit> {
        shifts.add(shift("a", at = 1), activate = true)
        shifts.add(shift("b", at = 2), activate = true)

        shifts.shifts(project).first().filter { it.active }.map { it.id } shouldBe listOf("b")
        shifts.active(project).first()!!.id shouldBe "b"
    }

    @Test
    fun `S020 RF-12 se puede desactivar y reactivar sin borrar el calculo`() = runBlocking<Unit> {
        shifts.add(shift("a"), activate = true)

        shifts.deactivate(project)
        shifts.active(project).first().shouldBeNull()
        shifts.shifts(project).first().map { it.id } shouldBe listOf("a")

        shifts.activate("a") shouldBe ActivateResult.ACTIVATED
        shifts.active(project).first()!!.id shouldBe "a"
    }

    @Test
    fun `S020 RF-12 borrar quita el calculo y el proyecto se queda sin activo`() = runBlocking<Unit> {
        shifts.add(shift("a"), activate = true)

        shifts.delete("a")

        shifts.shifts(project).first() shouldBe emptyList()
        shifts.active(project).first().shouldBeNull()
    }

    @Test
    fun `S020 RF-9 activar, desactivar y borrar no tocan ningun punto`() = runBlocking<Unit> {
        points.create(point())
        val before = points.points(project).first()

        shifts.add(shift("a"), activate = true)
        shifts.add(shift("b"), activate = true)
        shifts.deactivate(project)
        shifts.delete("a")
        shifts.delete("b")

        points.points(project).first() shouldBe before
    }

    @Test
    fun `S020 RF-12 los desplazamientos son de su proyecto`() = runBlocking<Unit> {
        val other = newProject("Otro")
        shifts.add(shift("a"), activate = true)
        shifts.add(shift("o", projectId = other), activate = true)

        shifts.active(project).first()!!.id shouldBe "a"
        shifts.active(other).first()!!.id shouldBe "o"
        shifts.shifts(other).first().map { it.id } shouldBe listOf("o")
    }

    @Test
    fun `S020 RF-12 activar uno que no existe no hace nada`() = runBlocking<Unit> {
        shifts.activate("no-existe") shouldBe ActivateResult.NOT_FOUND
    }

    @Test
    fun `S020 RF-16 cambiar el datum de correcciones desactiva el activo y lo avisa`() = runBlocking<Unit> {
        shifts.add(shift("a"), activate = true)

        val draft = projects.project(project)!!.let {
            ProjectDefaults().toDraft().copy(name = it.name, correctionsDatum = CorrectionsDatum.ITRF_WGS84)
        }
        projects.update(project, draft) shouldBe SaveResult.Saved(project)

        shifts.active(project).first().shouldBeNull()
        val inactive = shifts.shifts(project).first().single()
        inactive.active shouldBe false
        inactive.datumNotice shouldBe true
    }

    @Test
    fun `S020 RF-16 sin cambiar el datum el activo sigue activo`() = runBlocking<Unit> {
        shifts.add(shift("a"), activate = true)

        val current = projects.project(project)!!
        projects.update(
            project,
            ProjectDefaults().toDraft().copy(name = current.name, correctionsDatum = current.correctionsDatum, description = "otra"),
        )

        shifts.active(project).first().shouldNotBeNull().id shouldBe "a"
    }

    @Test
    fun `S020 RF-16 un calculo de otro datum no se reactiva y el aviso se puede descartar`() = runBlocking<Unit> {
        shifts.add(shift("a"), activate = true)
        val current = projects.project(project)!!
        projects.update(project, ProjectDefaults().toDraft().copy(name = current.name, correctionsDatum = CorrectionsDatum.ITRF_WGS84))

        shifts.activate("a") shouldBe ActivateResult.DATUM_MISMATCH
        shifts.active(project).first().shouldBeNull()
        shifts.acknowledgeDatumNotice("a")
        shifts.shifts(project).first().single().datumNotice shouldBe false
    }

    @Test
    fun `S020 RF-9 la lectura instantanea no cambia aunque se desactive despues`() = runBlocking<Unit> {
        val active = ActiveBaseShift(shifts)
        shifts.add(shift("a"), activate = true)

        val atStart = active.current(project).shouldNotBeNull().vector
        shifts.deactivate(project)

        atStart shouldBe shift("a").vector
        active.current(project).shouldBeNull()
        active.vector(project).first().shouldBeNull()
    }

    @Test
    fun `S020 RF-14 un desplazamiento calculado en reproduccion queda marcado`() = runBlocking<Unit> {
        shifts.add(shift("a").copy(fromReplay = true), activate = false)

        shifts.shifts(project).first().single().fromReplay shouldBe true
    }
}
