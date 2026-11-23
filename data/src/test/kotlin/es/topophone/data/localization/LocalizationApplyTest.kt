package es.topophone.data.localization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.LocalizationStatus
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.localization.LocalizationFailure
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

/** Aplicar, quitar y reactivar una localización: los puntos GNSS cambian sus coordenadas mostradas y nada más (S039/RF-14 a RF-22). */
@RunWith(AndroidJUnit4::class)
class LocalizationApplyTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private val projects = RoomProjectRepository(db)
    private val repository = RoomLocalizationRepository(db)
    private val points = RoomPointRepository(db)

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Camino de la Dehesa")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun gnss(id: String, easting: Double = 440_290.4) = Point.measured(
        id = id,
        projectId = project,
        name = "G-$id",
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416_801, -3.703_712, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1,
            measuredToMs = 2,
            coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), easting, 4_474_257.4, 648.3),
        ),
        createdAtMs = 3,
    )

    private fun manual(id: String) = gnss(id).copy(
        name = "M-$id",
        origin = PointOrigin.MANUAL,
        measurement = null,
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_290.4, 4_474_257.4, 648.3, HeightKind.PROJECT),
    )

    private fun pair(id: String, pointId: String) =
        LocalizationPair(id, pointId, "G-$pointId", 440_290.4, 4_474_257.4, 648.3, 440_390.4, 4_474_207.4, 648.55)

    private suspend fun load(id: String) = points.points(project).first().first { it.id == id }

    private suspend fun draftWithOnePair() {
        points.create(gnss("g1"))
        repository.addPair(project, pair("q1", "g1")) shouldBe true
    }

    @Test
    fun `S039 RF-17 aplicar mueve los puntos GNSS y deja la medida en el SC sin localizar`() = runBlocking<Unit> {
        draftWithOnePair()
        points.create(gnss("g2", easting = 440_500.0))

        val result = repository.apply(project, nowMs = 5).shouldBeInstanceOf<ApplyResult.Applied>()

        result.pointsChanged shouldBe 2
        val moved = load("g2")
        moved.coordinates.easting!! shouldBe (440_600.0 plusOrMinus 1e-6)
        moved.coordinates.northing!! shouldBe (4_474_207.4 plusOrMinus 1e-6)
        moved.coordinates.height!! shouldBe (648.55 plusOrMinus 1e-9)
        moved.measurement!!.coordinates.easting shouldBe 440_500.0
        moved.measurement!!.coordinates.northing shouldBe 4_474_257.4
        moved.localizationId shouldBe result.localizationId
        val active = repository.active(project).first().shouldNotBeNull()
        active.status shouldBe LocalizationStatus.ACTIVE
        active.appliedAtMs shouldBe 5
        active.params!!.translationE shouldBe (100.0 plusOrMinus 1e-6)
        active.residuals.getValue("q1").east!! shouldBe (0.0 plusOrMinus 1e-9)
        repository.draft(project).first().shouldBeNull()
    }

    @Test
    fun `S039 RF-20 los puntos manuales y los editados no cambian`() = runBlocking<Unit> {
        draftWithOnePair()
        points.create(manual("m1"))
        val edited = gnss("e1").copy(edited = true, typed = TypedCoordinates(EntryForm.PROJECT_CRS, 1.0, 2.0, 3.0, HeightKind.PROJECT))
        points.create(edited)
        val manualBefore = load("m1")
        val editedBefore = load("e1")

        repository.apply(project).shouldBeInstanceOf<ApplyResult.Applied>().pointsChanged shouldBe 1

        load("m1") shouldBe manualBefore
        load("e1") shouldBe editedBefore
        load("m1").localizationId.shouldBeNull()
    }

    @Test
    fun `S039 RF-16 al aplicar otra la anterior pasa al historial y solo hay una activa`() = runBlocking<Unit> {
        draftWithOnePair()
        val first = repository.apply(project, nowMs = 1).shouldBeInstanceOf<ApplyResult.Applied>()
        repository.startDraft(project)
        repository.setFixScale(project, true)

        val second = repository.apply(project, nowMs = 2).shouldBeInstanceOf<ApplyResult.Applied>()

        repository.active(project).first()!!.id shouldBe second.localizationId
        repository.history(project).first().map { it.id } shouldBe listOf(first.localizationId)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM localizations WHERE status = 'ACTIVE'").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 1
        }
    }

    @Test
    fun `S039 RF-16 editar con una localizacion activa no cambia la activa hasta aplicar`() = runBlocking<Unit> {
        draftWithOnePair()
        repository.apply(project).shouldBeInstanceOf<ApplyResult.Applied>()
        val activeBefore = repository.active(project).first()!!
        val pointBefore = load("g1")

        repository.startDraft(project)
        repository.setComponents(project, repository.draft(project).first()!!.pairs.single().id, useHorizontal = false, useVertical = false)

        repository.active(project).first() shouldBe activeBefore
        load("g1") shouldBe pointBefore
        repository.draft(project).first()!!.pairs.single().useHorizontal shouldBe false
    }

    @Test
    fun `S039 RF-21 quitar devuelve los puntos a su SC, conserva la localizacion y se puede reactivar`() = runBlocking<Unit> {
        draftWithOnePair()
        val pristine = load("g1")
        val applied = repository.apply(project).shouldBeInstanceOf<ApplyResult.Applied>()
        load("g1").coordinates.easting!! shouldBe (440_390.4 plusOrMinus 1e-6)

        repository.remove(project) shouldBe 1

        val back = load("g1")
        back.coordinates.easting shouldBe pristine.coordinates.easting
        back.coordinates.northing shouldBe pristine.coordinates.northing
        back.coordinates.height shouldBe pristine.coordinates.height
        back.localizationId.shouldBeNull()
        repository.active(project).first().shouldBeNull()
        val archived = repository.history(project).first().single()
        archived.id shouldBe applied.localizationId
        archived.pairs.size shouldBe 1

        repository.reactivate(project, archived.id).shouldBeInstanceOf<ApplyResult.DraftReady>()
        repository.apply(project).shouldBeInstanceOf<ApplyResult.Applied>()
        load("g1").coordinates.easting!! shouldBe (440_390.4 plusOrMinus 1e-6)
    }

    @Test
    fun `S039 RF-12 un borrador que no se puede calcular no cambia nada`() = runBlocking<Unit> {
        draftWithOnePair()
        repository.setComponents(project, repository.draft(project).first()!!.pairs.single().id, useHorizontal = false, useVertical = false)
        val before = load("g1")

        repository.apply(project) shouldBe ApplyResult.CannotSolve(LocalizationFailure.NO_PAIRS)

        load("g1") shouldBe before
        repository.active(project).first().shouldBeNull()
        repository.draft(project).first().shouldNotBeNull()
    }

    @Test
    fun `S039 RF-22 con el proyecto en terreno no se aplica nada`() = runBlocking<Unit> {
        draftWithOnePair()
        val withTerrain = projects.observe(project).first()!!.toDraft().copy(terrain = TerrainPlane(40.4, -3.7, 0.9996))
        projects.update(project, withTerrain).shouldBeInstanceOf<SaveResult.Saved>()
        val before = load("g1")

        repository.apply(project) shouldBe ApplyResult.TerrainActive

        load("g1") shouldBe before
        repository.active(project).first().shouldBeNull()
    }

    @Test
    fun `S039 RF-28 una localizacion sin pares de un SC guardado se aplica ya y se puede reactivar`() = runBlocking<Unit> {
        points.create(gnss("g1"))
        val params = es.topophone.core.model.localization.LocalizationParams(
            centreE = 0.0,
            centreN = 0.0,
            knownCentreE = 10.0,
            knownCentreN = -5.0,
            hasHorizontal = true,
        )

        val applied = repository.applyParams(project, "Plano de obra", params).shouldBeInstanceOf<ApplyResult.Applied>()

        applied.pointsChanged shouldBe 1
        load("g1").coordinates.easting!! shouldBe (440_300.4 plusOrMinus 1e-6)
        val active = repository.active(project).first()!!
        active.origin shouldBe LocalizationOrigin.SAVED_CRS
        active.name shouldBe "Plano de obra"
        active.pairs shouldBe emptyList()
        repository.remove(project) shouldBe 1
        repository.reactivate(project, repository.history(project).first().single().id).shouldBeInstanceOf<ApplyResult.Applied>()
        load("g1").coordinates.easting!! shouldBe (440_300.4 plusOrMinus 1e-6)
    }
}
