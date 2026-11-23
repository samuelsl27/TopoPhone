package es.topophone.data.localization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.TerrainPlane
import es.topophone.data.crs.CrsChangeResult
import es.topophone.data.crs.RoomProjectCrsHistoryRepository
import es.topophone.data.crs.TerrainChange
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Con una localización activa no se cambia el SC, las alturas ni se pasa a terreno; al quitarla, sí (S039/RF-22, RF-27). */
@RunWith(AndroidJUnit4::class)
class LocalizationCrsLockTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val localizations = RoomLocalizationRepository(db)
    private val history = RoomProjectCrsHistoryRepository(db)
    private val plane = TerrainPlane(40.4, -3.7, 0.9996)

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Camino de la Dehesa")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun localize() = runBlocking {
        points.create(
            Point.measured(
                id = "g1",
                projectId = project,
                name = "G1",
                description = "",
                measurement = Measurement(
                    antenna = GeodeticPosition(40.4168, -3.7037, 701.357),
                    poleHeightM = 1.8,
                    quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
                    rawNmea = "",
                    outOfTolerance = emptySet(),
                    measuredFromMs = 1,
                    measuredToMs = 2,
                    coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), 440_290.4, 4_474_257.4, 648.3),
                ),
                createdAtMs = 3,
            ),
        )
        localizations.addPair(project, LocalizationPair("q1", "g1", "G1", 440_290.4, 4_474_257.4, 648.3, 440_390.4, 4_474_207.4, 648.55))
        localizations.apply(project).shouldBeInstanceOf<ApplyResult.Applied>()
    }

    @Test
    fun `S039 RF-27 con una localizacion activa no se cambia el SC ni las alturas`() = runBlocking<Unit> {
        localize()

        history.blocksChange(project, ProjectCrs.ETRS89_UTM29, HeightSystem.ORTHOMETRIC, null) shouldBe true
        history.blocksChange(project, ProjectCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL, null) shouldBe true
        history.apply(project, ProjectCrs.ETRS89_UTM29, HeightSystem.ORTHOMETRIC) shouldBe CrsChangeResult.LocalizationActive
        history.apply(project, ProjectCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL) shouldBe CrsChangeResult.LocalizationActive
    }

    @Test
    fun `S039 RF-22 con una localizacion activa no se pasa a terreno`() = runBlocking<Unit> {
        localize()

        history.blocksChange(project, ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC, plane) shouldBe true
        history.apply(project, ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC, TerrainChange.Set(plane)) shouldBe
            CrsChangeResult.LocalizationActive
    }

    @Test
    fun `S039 RF-27 sin cambios o sin localizacion activa nada se bloquea`() = runBlocking<Unit> {
        history.blocksChange(project, ProjectCrs.ETRS89_UTM29, HeightSystem.ORTHOMETRIC, plane) shouldBe false

        localize()
        history.blocksChange(project, ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC, null) shouldBe false
        localizations.remove(project)

        history.blocksChange(project, ProjectCrs.ETRS89_UTM29, HeightSystem.ORTHOMETRIC, null) shouldBe false
        history.apply(project, ProjectCrs.ETRS89_UTM29, HeightSystem.ORTHOMETRIC).shouldBeInstanceOf<CrsChangeResult.Applied>()
    }
}
