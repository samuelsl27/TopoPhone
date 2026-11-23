package es.topophone.app.ui.localization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationPair
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
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.localization.RoomLocalizationRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Formar los pares de la localización: candidatos, coordenadas conocidas, rechazos y confirmación de la calidad (S039/RF-2 a RF-4, RF-6). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LocalizationViewModelTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val localizations = RoomLocalizationRepository(db)
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var viewModel: LocalizationViewModel
    private lateinit var project: String

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        project = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Camino de la Dehesa")).shouldBeInstanceOf<SaveResult.Saved>().id
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun gnss(id: String, solution: String = "FIX", easting: Double = 440_290.4) = Point.measured(
        id = id,
        projectId = project,
        name = "G-$id",
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.4168, -3.7037, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality(solution, 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
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
        coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7037, 699.4), 440_390.0, 4_474_207.0, 648.55),
        typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_390.0, 4_474_207.0, 648.55, HeightKind.PROJECT),
    )

    private fun viewModel(): LocalizationViewModel {
        viewModel = LocalizationViewModel(localizations, projects, points)
        viewModel.start(project)
        scope.launch { viewModel.state.collect {} }
        runBlocking { withTimeout(10_000) { viewModel.state.first { it.loaded } } }
        return viewModel
    }

    private fun create(vararg list: Point) = runBlocking { list.forEach { points.create(it) } }

    private fun state() = viewModel.state.value

    /** El estado llega de otro hilo (la combinación va en `Dispatchers.Default`): se espera a que cumpla [predicate]. */
    private fun await(predicate: (LocalizationUiState) -> Boolean): LocalizationUiState =
        runBlocking { withTimeout(10_000) { viewModel.state.first(predicate) } }

    @Test
    fun `S039 RF-2 los candidatos GNSS son los medidos y los conocidos los manuales o importados`() {
        create(gnss("g1"), gnss("g2"), manual("m1"))
        viewModel()
        await { it.gnssCandidates.size == 2 && it.knownCandidates.size == 1 }

        state().gnssCandidates.map { it.id } shouldBe listOf("g1", "g2")
        state().knownCandidates.map { it.id } shouldBe listOf("m1")
        state().addPair.shouldBeNull()
    }

    @Test
    fun `S039 RF-2 un par con coordenadas escritas se anade al borrador y cierra el formulario`() {
        create(gnss("g1"))
        viewModel()
        viewModel.openAddPair()
        viewModel.selectGnss("g1")
        viewModel.setEasting("440390,5")
        viewModel.setNorthing("4474207.25")
        viewModel.setHeight("648,55")

        viewModel.confirmAddPair()
        await { it.pairs.size == 1 && it.addPair == null }

        val pair = runBlocking { localizations.draft(project).first() }.shouldNotBeNull().pairs.single()
        pair.pointId shouldBe "g1"
        pair.knownE shouldBe (440_390.5 plusOrMinus 1e-9)
        pair.knownN shouldBe (4_474_207.25 plusOrMinus 1e-9)
        pair.knownH.shouldNotBeNull() shouldBe (648.55 plusOrMinus 1e-9)
        pair.scE shouldBe 440_290.4
        state().addPair.shouldBeNull()
        await { it.gnssCandidates.isEmpty() }
    }

    @Test
    fun `S039 RF-2 las coordenadas se pueden tomar de un punto manual`() {
        create(gnss("g1"), manual("m1"))
        viewModel()
        viewModel.openAddPair()
        viewModel.selectGnss("g1")
        viewModel.setSource(KnownSource.POINT)
        viewModel.selectKnown("m1")

        viewModel.confirmAddPair()

        val pair = await { it.pairs.size == 1 }.pairs.single()
        pair.knownE shouldBe 440_390.0
        pair.knownN shouldBe 4_474_207.0
        pair.knownH shouldBe 648.55
    }

    @Test
    fun `S039 RF-2 sin punto GNSS o con coordenadas invalidas el formulario dice el motivo`() {
        create(gnss("g1"))
        viewModel()
        viewModel.openAddPair()

        viewModel.confirmAddPair()
        await { it.addPair?.error == PairError.NO_GNSS }

        viewModel.selectGnss("g1")
        viewModel.confirmAddPair()
        await { it.addPair?.error == PairError.INVALID_COORDINATES }

        viewModel.setEasting("12")
        viewModel.setNorthing("34")
        viewModel.setHeight("abc")
        viewModel.confirmAddPair()
        await { it.addPair?.error == PairError.INVALID_COORDINATES && it.addPair?.heightText == "abc" }

        viewModel.setSource(KnownSource.POINT)
        viewModel.confirmAddPair()
        await { it.addPair?.error == PairError.NO_KNOWN_POINT }
        state().pairs.size shouldBe 0
    }

    @Test
    fun `S039 RF-3 un punto manual no puede ser GNSS y uno ya usado tampoco`() {
        create(gnss("g1"), manual("m1"))
        viewModel()
        viewModel.openAddPair()
        viewModel.setEasting("1")
        viewModel.setNorthing("2")

        viewModel.selectGnss("m1")
        viewModel.confirmAddPair()
        await { it.addPair?.error == PairError.NO_MEASUREMENT }

        viewModel.selectGnss("g1")
        viewModel.confirmAddPair()
        await { it.pairs.size == 1 }
        viewModel.openAddPair()
        viewModel.selectGnss("g1")
        viewModel.setEasting("1")
        viewModel.setNorthing("2")
        viewModel.confirmAddPair()
        await { it.addPair?.error == PairError.ALREADY_IN_DRAFT }
    }

    @Test
    fun `S039 RF-4 el par 51 se rechaza con su motivo`() {
        create(gnss("g1"))
        runBlocking {
            for (i in 1..LocalizationLimits.MAX_PAIRS) {
                localizations.addPair(project, LocalizationPair("q$i", "other$i", "O$i", i.toDouble(), 2.0, null, 3.0, 4.0, null))
            }
        }
        viewModel()
        viewModel.openAddPair()
        viewModel.selectGnss("g1")
        viewModel.setEasting("1")
        viewModel.setNorthing("2")

        viewModel.confirmAddPair()

        await { it.addPair?.error == PairError.LIMIT_REACHED }
        state().pairs.size shouldBe LocalizationLimits.MAX_PAIRS
    }

    @Test
    fun `S039 RF-6 un punto en FLOAT queda sin confirmar y confirmarlo lo deja contar`() {
        create(gnss("g1", solution = "FLOAT"))
        viewModel()
        viewModel.openAddPair()
        viewModel.selectGnss("g1")
        viewModel.setEasting("1")
        viewModel.setNorthing("2")
        viewModel.confirmAddPair()
        val pair = await { it.pairs.size == 1 }.pairs.single()
        pair.confirmed shouldBe false

        viewModel.confirmPair(pair.id)

        await { it.pairs.singleOrNull()?.confirmed == true }
    }
    private fun addPairTo(gnssId: String, e: String, n: String) {
        viewModel.openAddPair()
        viewModel.selectGnss(gnssId)
        viewModel.setEasting(e)
        viewModel.setNorthing(n)
        viewModel.confirmAddPair()
    }

    @Test
    fun `S039 RF-5 y RF-10 excluir una componente recalcula y quitar un par tambien`() {
        create(gnss("g1"), gnss("g2", easting = 440_500.0), gnss("g3", easting = 440_700.0))
        viewModel()
        addPairTo("g1", "440390", "4474207")
        await { it.pairs.size == 1 }
        addPairTo("g2", "440600", "4474207")
        await { it.pairs.size == 2 }
        addPairTo("g3", "440800", "4474207")
        val full = await { it.pairs.size == 3 && it.outcome is es.topophone.core.survey.localization.LocalizationOutcome.Solved }
        val firstId = full.pairs.first().id

        viewModel.setComponent(firstId, horizontal = true, enabled = false)
        val excluded = await { it.pairs.firstOrNull()?.useHorizontal == false }

        excluded.pairs.size shouldBe 3
        val solved = excluded.outcome.shouldBeInstanceOf<es.topophone.core.survey.localization.LocalizationOutcome.Solved>().solution
        solved.residuals.first().horizontal.shouldBeNull()
        viewModel.removePair(excluded.pairs[1].id)
        await { it.pairs.size == 2 }
    }

    @Test
    fun `S039 RF-8 la escala fija se guarda en el borrador y deja la escala en uno`() {
        create(gnss("g1"), gnss("g2", easting = 440_500.0))
        viewModel()
        addPairTo("g1", "440390", "4474207")
        await { it.pairs.size == 1 }
        addPairTo("g2", "440603", "4474207")
        await { it.pairs.size == 2 }

        viewModel.setFixScale(true)

        val s = await { it.fixScale }
        val solved = s.outcome.shouldBeInstanceOf<es.topophone.core.survey.localization.LocalizationOutcome.Solved>().solution
        solved.params.scale shouldBe 1.0
    }

    @Test
    fun `S039 RF-16 editar con una localizacion activa parte de la activa y no la toca`() {
        create(gnss("g1"))
        viewModel()
        addPairTo("g1", "440390", "4474207")
        await { it.pairs.size == 1 }
        runBlocking { localizations.apply(project) }
        val applied = await { it.active != null && it.draft == null }

        viewModel.setComponent(applied.pairs.single().id, horizontal = true, enabled = false)

        val edited = await { it.draft != null && it.pairs.singleOrNull()?.useHorizontal == false }
        edited.active.shouldNotBeNull().pairs.single().useHorizontal shouldBe true
    }

    @Test
    fun `S039 RF-13 los umbrales se guardan en el borrador y un texto invalido no los cambia`() {
        create(gnss("g1"))
        viewModel()
        addPairTo("g1", "440390", "4474207")
        await { it.pairs.size == 1 }

        viewModel.setLimits("0,05", "0,002")
        val changed = await { it.residualLimitM == 0.05 }
        viewModel.setLimits("abc", "-1")

        changed.scaleLimit shouldBe 0.002
        state().residualLimitM shouldBe 0.05
        state().scaleLimit shouldBe 0.002
    }
    private fun threePairs(secondEasting: String = "440600") {
        create(gnss("g1"), gnss("g2", easting = 440_500.0), gnss("g3", easting = 440_700.0))
        viewModel()
        addPairTo("g1", "440390,4", "4474207")
        await { it.pairs.size == 1 }
        addPairTo("g2", secondEasting, "4474207")
        await { it.pairs.size == 2 }
        addPairTo("g3", "440800", "4474207")
        await { it.pairs.size == 3 && it.outcome is es.topophone.core.survey.localization.LocalizationOutcome.Solved }
    }

    @Test
    fun `S039 RF-14 aplicar pide confirmacion con el recuento y deja la localizacion activa`() {
        threePairs()

        viewModel.requestApply()
        val dialog = await { it.confirmApply != null }.confirmApply!!

        dialog.stage shouldBe ApplyStage.CONFIRM
        dialog.pointsAffected shouldBe 3
        dialog.hasWarnings shouldBe false
        viewModel.confirmApply()
        val applied = await { it.active != null && it.draft == null }
        applied.notice shouldBe LocalizationNotice.Applied(3)
        applied.confirmApply.shouldBeNull()
    }

    @Test
    fun `S039 RF-15 con un residuo fuera de umbral hace falta una segunda confirmacion que lo nombra`() {
        threePairs(secondEasting = "440640")

        viewModel.requestApply()
        await { it.confirmApply != null }
        viewModel.confirmApply()
        val second = await { it.confirmApply?.stage == ApplyStage.CONFIRM_WARNINGS }.confirmApply!!

        second.highPairNames.isNotEmpty() shouldBe true
        state().active.shouldBeNull()
        viewModel.confirmApply()
        await { it.active != null }
    }

    @Test
    fun `S039 RF-21 quitar pide confirmacion, deja la localizacion en el historial y se puede reactivar`() {
        threePairs()
        viewModel.requestApply()
        await { it.confirmApply != null }
        viewModel.confirmApply()
        await { it.active != null }

        viewModel.requestRemove()
        await { it.confirmRemove == 3 }
        viewModel.confirmRemove()
        val removed = await { it.active == null && it.history.size == 1 }
        removed.notice shouldBe LocalizationNotice.Removed(3)

        viewModel.reactivate(removed.history.single().id)
        val ready = await { it.draft != null }
        ready.notice shouldBe LocalizationNotice.DraftReady
        ready.pairs.size shouldBe 3
    }

    @Test
    fun `S039 RF-22 con el proyecto en terreno no se puede aplicar`() {
        threePairs()
        runBlocking {
            val draft = projects.observe(project).first()!!.toDraft()
            projects.update(project, draft.copy(terrain = es.topophone.core.model.project.TerrainPlane(40.4, -3.7, 0.9996)))
        }
        await { it.terrain }

        state().canApply shouldBe false
        viewModel.requestApply()
        state().confirmApply.shouldBeNull()
    }

    @Test
    fun `S039 RF-16 descartar el borrador deja la activa como estaba`() {
        threePairs()
        viewModel.requestApply()
        await { it.confirmApply != null }
        viewModel.confirmApply()
        await { it.active != null }
        viewModel.setFixScale(true)
        await { it.draft != null }

        viewModel.discardDraft()

        val s = await { it.draft == null }
        s.active.shouldNotBeNull().fixScale shouldBe false
    }

    @Test
    fun `S039 RF-25 el informe lleva los parametros, los pares y los residuos del borrador`() {
        threePairs()
        val out = java.io.ByteArrayOutputStream()

        val ok = runBlocking { viewModel.writeReport(out) }

        ok shouldBe true
        val text = out.toByteArray().toString(Charsets.UTF_8)
        text.contains("Scale,1.0000") shouldBe true
        text.contains("Point,CRS easting,CRS northing") shouldBe true
        text.contains("G-g1") shouldBe true
        text.contains("G-g3") shouldBe true
        viewModel.reportFileName(java.time.Instant.parse("2026-11-23T10:15:00Z"), java.time.ZoneOffset.UTC) shouldBe
            "Camino de la Dehesa_localizacion_20261123-1015.csv"
    }

    @Test
    fun `S039 RF-25 sin pares ni localizacion activa no hay informe`() {
        create(gnss("g1"))
        viewModel()

        runBlocking { viewModel.writeReport(java.io.ByteArrayOutputStream()) } shouldBe false
    }
}
