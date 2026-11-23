package es.topophone.data.localization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** La localización activa en vivo y el log de aplicar y quitar sin coordenadas (S039/RF-17, RF-26). */
@RunWith(AndroidJUnit4::class)
class ActiveLocalizationTest {

    private class RecordingLog : AppLog {
        val lines = mutableListOf<String>()

        override fun record(level: LogLevel, category: LogCategory, message: String) {
            lines += message
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val log = RecordingLog()
    private val repository = RoomLocalizationRepository(db, log)
    private val active = ActiveLocalization(repository)

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Camino de la Dehesa")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun gnss() = Point.measured(
        id = "g1",
        projectId = project,
        name = "G1",
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416_801, -3.703_712, 701.357),
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
    fun `S039 RF-17 la localizacion activa aparece al aplicar y desaparece al quitar`() = runBlocking<Unit> {
        active.current(project).shouldBeNull()
        points.create(gnss())
        repository.addPair(project, LocalizationPair("q1", "g1", "G1", 440_290.4, 4_474_257.4, 648.3, 440_390.4, 4_474_207.4, 648.55))

        repository.apply(project)

        val info = active.current(project).shouldNotBeNull()
        (abs(info.params.translationE - 100.0) < 1e-6) shouldBe true
        repository.remove(project)
        active.current(project).shouldBeNull()
    }

    @Test
    fun `S039 RF-26 el log anota cada aplicacion y cada retirada sin coordenadas`() = runBlocking<Unit> {
        points.create(gnss())
        repository.addPair(project, LocalizationPair("q1", "g1", "G1", 440_290.4, 4_474_257.4, 648.3, 440_390.4, 4_474_207.4, 648.55))

        repository.apply(project)
        repository.remove(project)

        log.lines shouldHaveSize 2
        log.lines[0].startsWith("Localización aplicada") shouldBe true
        log.lines[1].startsWith("Localización quitada") shouldBe true
        log.lines.forEach { line ->
            line shouldNotContain "440"
            line shouldNotContain "4474"
        }
    }

    @Test
    fun `S039 RF-17 sin localizaciones la lectura es nula`() = runBlocking<Unit> {
        ActiveLocalization.NONE.current(project).shouldBeNull()
    }
}
