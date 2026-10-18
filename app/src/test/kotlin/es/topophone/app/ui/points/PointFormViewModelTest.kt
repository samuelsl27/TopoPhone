package es.topophone.app.ui.points

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** Formulario de punto, nuevo y editado (S011/RF-8, RF-9, RF-16 a RF-20). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PointFormViewModelTest {

    private val es = Locale.forLanguageTag("es-ES")
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = newProject(ProjectCrs.ETRS89_UTM30)
    private val repository = FakePointRepository(listOf(measured("12", projectId = projectId)), clock = { 9_000 })
    private lateinit var vm: PointFormViewModel

    private fun newProject(crs: ProjectCrs): String = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(name = "Proyecto ${crs.name}", crs = crs),
        ).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = PointFormViewModel(repository, projects) { ProjectPositionCalculator() }
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun await(predicate: (PointFormUiState) -> Boolean): PointFormUiState =
        runBlocking { withTimeout(5_000) { vm.state.first(predicate) } }

    private fun saved() = runBlocking { withTimeout(5_000) { vm.events.first() } }

    @Test
    fun `S011 RF-16 un punto nuevo propone el siguiente nombre y empieza en el SC del proyecto`() {
        vm.start(projectId, pointId = null, locale = es)

        val state = await { it.loaded }
        state.name shouldBe "13"
        state.form shouldBe EntryForm.PROJECT_CRS
        state.editing shouldBe false
        state.canSave shouldBe false
    }

    @Test
    fun `S011 RF-17 y RF-20 lo escrito en el SC del proyecto se guarda tal cual con sus geograficas`() {
        vm.start(projectId, pointId = null, locale = es)
        await { it.loaded }
        vm.setDescription("clavo")
        vm.setFirst("440297,988")
        vm.setSecond("4.474.244,001")
        vm.setHeight("648,311")
        await { it.canSave }

        vm.save()

        saved()
        val point = repository.points.value.single { it.name == "13" }
        point.origin shouldBe PointOrigin.MANUAL
        point.description shouldBe "clavo"
        point.typed shouldBe TypedCoordinates(EntryForm.PROJECT_CRS, 440_297.988, 4_474_244.001, 648.311, HeightKind.PROJECT)
        point.coordinates.easting shouldBe 440_297.988
        point.coordinates.ground.latitudeDeg shouldBe (40.41668 plusOrMinus 1e-8)
        point.coordinates.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (699.423 plusOrMinus 0.001)
        point.createdAtMs shouldBe point.createdAtMs
    }

    @Test
    fun `S011 RF-17 en geograficas la altura puede ser elipsoidal`() {
        vm.start(projectId, pointId = null, locale = es)
        await { it.loaded }
        vm.setForm(EntryForm.GEOGRAPHIC)
        await { it.allowEllipsoidalKind }
        vm.setFirst("40 25 0,048 N")
        vm.setSecond("3 42 13,356 W")
        vm.setHeight("699,423")
        vm.setHeightKind(HeightKind.ELLIPSOIDAL)
        await { it.canSave }

        vm.save()

        saved()
        val point = repository.points.value.single { it.name == "13" }
        point.coordinates.ground.ellipsoidalHeightM shouldBe 699.423
        point.coordinates.height.shouldNotBeNull() shouldBe (648.311 plusOrMinus 0.001)
        point.coordinates.easting.shouldNotBeNull() shouldBe (440_297.988 plusOrMinus 0.001)
    }

    @Test
    fun `S011 RF-18 con la altura vacia el punto se guarda sin altura`() {
        vm.start(projectId, pointId = null, locale = es)
        await { it.loaded }
        vm.setFirst("440297,988")
        vm.setSecond("4474244,001")
        await { it.canSave }

        vm.save()

        saved()
        val point = repository.points.value.single { it.name == "13" }
        point.hasHeight shouldBe false
        point.coordinates.height.shouldBeNull()
    }

    @Test
    fun `S011 RF-19 fuera del ambito no se puede guardar y se dice por que`() {
        vm.start(projectId, pointId = null, locale = es)
        await { it.loaded }
        vm.setFirst("440000")
        vm.setSecond("5600000")

        val state = await { it.rejection != null }
        state.rejection shouldBe PositionUnavailable.OUTSIDE_CRS_AREA
        state.canSave shouldBe false
    }

    @Test
    fun `S011 RF-17 un valor que no es un numero se marca y no deja guardar`() {
        vm.start(projectId, pointId = null, locale = es)
        await { it.loaded }
        vm.setFirst("44o297")
        vm.setSecond("4474244")

        val state = await { it.firstInvalid }
        state.canSave shouldBe false
    }

    @Test
    fun `S011 RF-9 un nombre que ya existe se dice y no deja guardar`() {
        vm.start(projectId, pointId = null, locale = es)
        await { it.loaded }
        vm.setName("12")

        await { it.nameError == NameProblem.TAKEN }.canSave shouldBe false
        vm.setName(" ")
        await { it.nameError == NameProblem.EMPTY }
    }

    @Test
    fun `S011 RF-8 editar solo el nombre de un punto medido no cambia sus coordenadas ni lo marca como editado`() {
        vm.start(projectId, pointId = "id-12", locale = es)
        val loaded = await { it.loaded }
        loaded.editing shouldBe true
        loaded.first shouldBe "440297,988"

        vm.setName("12b")
        await { it.canSave }
        vm.save()

        saved()
        val point = repository.points.value.single { it.id == "id-12" }
        point.name shouldBe "12b"
        point.coordinates shouldBe PointFixtures.sol
        point.edited shouldBe false
        repository.historyEntries.value.single().change shouldBe PointChange.EDIT
    }

    @Test
    fun `S011 RF-8 y RF-12 al cambiar las coordenadas de un punto medido queda editado con lo escrito`() {
        vm.start(projectId, pointId = "id-12", locale = es)
        await { it.loaded }
        vm.setFirst("440300")
        await { it.canSave && it.first == "440300" }

        vm.save()

        saved()
        val point = repository.points.value.single { it.id == "id-12" }
        point.edited shouldBe true
        point.coordinates.easting shouldBe 440_300.0
        point.typed.shouldNotBeNull().first shouldBe 440_300.0
        point.measurement.shouldNotBeNull().coordinates shouldBe PointFixtures.sol
    }

    @Test
    fun `S011 RF-17 en un proyecto en geograficas no hay SC del proyecto aparte, y en ED50 solo altitud`() {
        val geographic = newProject(ProjectCrs.ETRS89_GEOGRAPHIC)
        vm.start(geographic, pointId = null, locale = es)
        val state = await { it.loaded }
        state.geographicCrs shouldBe true
        state.form shouldBe EntryForm.GEOGRAPHIC
        state.allowEllipsoidalKind shouldBe true

        val ed50 = PointFormViewModel(repository, projects) { ProjectPositionCalculator() }
        CoroutineScope(Dispatchers.Unconfined).launch { ed50.state.collect {} }
        ed50.start(newProject(ProjectCrs.ED50_UTM30), pointId = null, locale = es)
        runBlocking { withTimeout(5_000) { ed50.state.first { it.loaded } } }
        ed50.setForm(EntryForm.GEOGRAPHIC)
        runBlocking { withTimeout(5_000) { ed50.state.first { it.form == EntryForm.GEOGRAPHIC } } }.allowEllipsoidalKind shouldBe false
    }
}
