package es.topophone.data.point

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
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

/** Los tipos de solución admitidos y las épocas descartadas por tipo se guardan con cada punto (S047/RF-7, RF-15). */
@RunWith(AndroidJUnit4::class)
class PointRepositoryAdmittedTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db, clock = { 50_000 })

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun point(name: String, admitted: AdmittedSolutions?, byType: Int?, failures: Set<PrecisionFailure> = emptySet()) =
        Point.measured(
            id = "id-$name",
            projectId = project,
            name = name,
            description = "",
            measurement = Measurement(
                antenna = GeodeticPosition(40.41668, -3.70371, 701.357),
                poleHeightM = 1.8,
                quality = PointQuality(
                    "FLOAT",
                    24,
                    0.6,
                    1.0,
                    0.009,
                    0.014,
                    MeasurementMode.AVERAGED,
                    50,
                    12,
                    fromReplay = false,
                    admitted = admitted,
                    epochsDiscardedByType = byType,
                ),
                rawNmea = "",
                outOfTolerance = failures,
                measuredFromMs = 1_000,
                measuredToMs = 1_000,
                coordinates = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311),
            ),
            createdAtMs = 1_000,
        )

    private fun stored(name: String) = runBlocking { points.points(project).first() }.first { it.name == name }

    @Test
    fun `S047 RF-15 los tipos admitidos y las descartadas por tipo se guardan y se leen`() {
        val admitted = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT))
        runBlocking { points.create(point("P1", admitted, 9)) } shouldBe PointSaveResult.Saved

        val quality = stored("P1").measurement.shouldNotBeNull().quality
        quality.admitted shouldBe admitted
        quality.epochsDiscardedByType shouldBe 9
        quality.epochsDiscarded shouldBe 12
    }

    @Test
    fun `S047 RF-16 un punto de antes de S047 se lee sin tipos admitidos`() {
        runBlocking { points.create(point("P2", null, null)) } shouldBe PointSaveResult.Saved

        val quality = stored("P2").measurement.shouldNotBeNull().quality
        quality.admitted.shouldBeNull()
        quality.epochsDiscardedByType.shouldBeNull()
    }

    @Test
    fun `S047 RF-7 un punto de tipo no admitido se guarda marcado fuera de tolerancia con NOT_FIX`() {
        runBlocking { points.create(point("P3", AdmittedSolutions.FIX_ONLY, 0, setOf(PrecisionFailure.NOT_FIX))) } shouldBe
            PointSaveResult.Saved

        stored("P3").measurement.shouldNotBeNull().outOfTolerance shouldBe setOf(PrecisionFailure.NOT_FIX)
    }
}
