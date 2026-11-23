package es.topophone.data.localization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.TerrainPlane
import es.topophone.data.crs.RoomProjectCrsHistoryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Guardar la localización activa como SC de «Mis SC» y elegirlo en otro proyecto (S039/RF-24, RF-28). */
@RunWith(AndroidJUnit4::class)
class LocalizedCrsRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val localizations = RoomLocalizationRepository(db)
    private val history = RoomProjectCrsHistoryRepository(db)
    private val repository = RoomLocalizedCrsRepository(db, localizations, history)

    @After
    fun close() = db.close()

    private fun newProject(name: String, crs: ProjectCrs = ProjectCrs.ETRS89_UTM30) = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = name, crs = crs)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val source = newProject("Origen")

    private fun gnss(project: String, id: String) = Point.measured(
        id = id,
        projectId = project,
        name = "G-$id",
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
    )

    private fun localizeSource() = runBlocking {
        points.create(gnss(source, "g1"))
        localizations.addPair(source, LocalizationPair("q1", "g1", "G1", 440_290.4, 4_474_257.4, 648.3, 440_390.4, 4_474_207.4, 648.55))
        localizations.apply(source).shouldBeInstanceOf<ApplyResult.Applied>()
    }

    @Test
    fun `S039 RF-24 la localizacion activa se guarda como un SC con su base y sus parametros`() = runBlocking<Unit> {
        localizeSource()

        val saved = repository.saveFromProject(source, " Plano de obra ").shouldBeInstanceOf<LocalizedCrsSave.Saved>().crs

        saved.name shouldBe "Plano de obra"
        saved.crs shouldBe ProjectCrs.ETRS89_UTM30
        saved.params.translationE shouldBe (100.0 plusOrMinus 1e-6)
        repository.observeAll().first().map { it.name } shouldBe listOf("Plano de obra")
    }

    @Test
    fun `S039 RF-24 un nombre vacio, repetido o sin localizacion activa no se guarda`() = runBlocking<Unit> {
        repository.saveFromProject(source, "A") shouldBe LocalizedCrsSave.NothingToSave
        localizeSource()

        repository.saveFromProject(source, "   ") shouldBe LocalizedCrsSave.InvalidName
        repository.saveFromProject(source, "Plano").shouldBeInstanceOf<LocalizedCrsSave.Saved>()
        repository.saveFromProject(source, "PLANO") shouldBe LocalizedCrsSave.NameTaken
    }

    @Test
    fun `S039 RF-28 elegirlo en otro proyecto deja el SC base y la localizacion activa sin pares`() = runBlocking<Unit> {
        localizeSource()
        val saved = repository.saveFromProject(source, "Plano").shouldBeInstanceOf<LocalizedCrsSave.Saved>().crs
        val other = newProject("Otro", crs = ProjectCrs.ETRS89_UTM29)
        points.create(gnss(other, "h1"))

        val result = repository.use(other, saved.id).shouldBeInstanceOf<LocalizedCrsUse.Applied>()

        result.pointsChanged shouldBe 1
        projects.observe(other).first()!!.crs shouldBe ProjectCrs.ETRS89_UTM30
        val active = localizations.active(other).first().shouldNotBeNull()
        active.origin shouldBe LocalizationOrigin.SAVED_CRS
        active.name shouldBe "Plano"
        active.pairs shouldBe emptyList()
    }

    @Test
    fun `S039 RF-28 con terreno o con otro datum no se cambia nada`() = runBlocking<Unit> {
        localizeSource()
        val saved = repository.saveFromProject(source, "Plano").shouldBeInstanceOf<LocalizedCrsSave.Saved>().crs
        val ground = newProject("Terreno")
        projects.update(ground, projects.observe(ground).first()!!.toDraft().copy(terrain = TerrainPlane(40.4, -3.7, 0.9996)))
        val ed50 = newProject("ED50", crs = ProjectCrs.ED50_UTM30)
        points.create(gnss(ed50, "e1"))

        repository.use(ground, saved.id) shouldBe LocalizedCrsUse.TerrainActive
        repository.use(ed50, saved.id) shouldBe LocalizedCrsUse.OtherDatum

        projects.observe(ed50).first()!!.crs shouldBe ProjectCrs.ED50_UTM30
        localizations.active(ed50).first().shouldBeNull()
        localizations.active(ground).first().shouldBeNull()
    }

    @Test
    fun `S039 RF-28 un SC que no existe o borrado no se puede elegir`() = runBlocking<Unit> {
        localizeSource()
        val saved = repository.saveFromProject(source, "Plano").shouldBeInstanceOf<LocalizedCrsSave.Saved>().crs

        repository.delete(saved.id)

        repository.observeAll().first() shouldBe emptyList()
        repository.use(source, saved.id) shouldBe LocalizedCrsUse.NotFound
    }
}
