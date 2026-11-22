package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapPreferences
import es.topophone.app.ui.map.FakeMapViewportRepository
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.TerrainError
import es.topophone.core.survey.TerrainField
import es.topophone.core.survey.TerrainProposal
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.crs.RoomProjectCrsHistoryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El plano de terreno en el formulario del proyecto: elegir, origen, factor propuesto y errores (S058/RF-1 a RF-5). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectTerrainFormTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
    private val madrid = CentralPoint(40.4168, -3.7038)

    private class FakeProjectPreferences(initial: ProjectDefaults) : ProjectPreferences {
        override val defaults = MutableStateFlow(initial)

        override suspend fun saveDefaults(defaults: ProjectDefaults) {
            this.defaults.value = defaults
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ProjectFormViewModel(
        projects,
        FakeProjectPreferences(ProjectDefaults()),
        session,
        FakeMapPreferences(),
        RoomCodeLibraryRepository(db),
        RoomProjectCrsHistoryRepository(db),
        ProjectCenterProvider(FakePointRepository(), FakeMapViewportRepository()),
    )

    private fun ProjectFormViewModel.await(predicate: (ProjectFormUiState) -> Boolean): ProjectFormUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private fun newProject(centre: CentralPoint? = madrid): ProjectFormViewModel = viewModel().also {
        it.start(null, null)
        it.await { s -> s.loaded }
        it.update(it.state.value.draft.copy(name = "Obra", crs = SpainCrs.ETRS89_UTM30, centralPoint = centre))
    }

    private fun show(vm: ProjectFormViewModel) {
        compose.setContent {
            val state by vm.state.collectAsState()
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectFormScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    ProjectFormActions(
                        onChange = vm::update,
                        onSave = vm::save,
                        onTerrainMode = vm::setTerrainMode,
                        onTerrainText = vm::setTerrainText,
                        onTerrainUseProposal = vm::useTerrainProposal,
                        onTerrainUseCenter = vm::useTerrainCenter,
                    ),
                )
            }
        }
    }

    @Test
    fun `S058 RF-1 un proyecto nuevo va en cuadricula y se puede elegir terreno`() {
        val vm = newProject()
        show(vm)

        compose.onNodeWithTag(terrainOptionTag(false)).performScrollTo().assertIsDisplayed()
        vm.state.value.terrainForm.terrain shouldBe false
        compose.onNodeWithTag(terrainOptionTag(true)).performScrollTo().performClick()

        vm.await { it.terrainForm.terrain && it.terrainForm.proposed != null }
        compose.onNodeWithTag(TERRAIN_LATITUDE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(TERRAIN_FACTOR_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S058 RF-3 al elegir terreno el origen es el centro del proyecto y el factor el propuesto en el`() {
        val vm = newProject()

        vm.setTerrainMode(true)
        val form = vm.await { it.terrainForm.terrain && it.terrainForm.proposed != null }.terrainForm

        form.originLatitude shouldBe madrid.latitudeDeg.toString()
        form.originLongitude shouldBe madrid.longitudeDeg.toString()
        val expected = TerrainProposal.factorAt(madrid.latitudeDeg, madrid.longitudeDeg, null, SpainCrs.ETRS89_UTM30).shouldNotBeNull()
        form.proposed.shouldNotBeNull() shouldBe (expected plusOrMinus 1e-12)
        form.factor.toDouble() shouldBe (expected plusOrMinus 1e-12)
        vm.state.value.draft.terrain.shouldNotBeNull().factor shouldBe (expected plusOrMinus 1e-12)
    }

    @Test
    fun `S058 RF-3 sin centro del proyecto el origen hay que escribirlo y se avisa`() {
        val vm = newProject(centre = null)

        vm.setTerrainMode(true)
        val form = vm.await { it.terrainForm.terrain && it.terrainForm.noCenter }.terrainForm

        form.originLatitude shouldBe ""
        vm.state.value.draft.terrain.shouldBeNull()
        vm.state.value.terrainForm.errors[TerrainField.ORIGIN] shouldBe TerrainError.REQUIRED
    }

    @Test
    fun `S058 RF-3 el factor propuesto se puede cambiar y volver a usar`() {
        val vm = newProject()
        vm.setTerrainMode(true)
        val proposed = vm.await { it.terrainForm.proposed != null }.terrainForm.proposed!!

        vm.setTerrainText(madrid.latitudeDeg.toString(), madrid.longitudeDeg.toString(), "1,0002")
        vm.await { it.draft.terrain?.factor == 1.0002 }
        vm.useTerrainProposal()

        vm.await { it.draft.terrain?.factor == proposed }.terrainForm.factor shouldBe proposed.toString()
    }

    @Test
    fun `S058 RF-4 un factor fuera de 0,9 a 1,1 se marca en el acto, el proyecto no queda con plano y no se guarda`() {
        val vm = newProject()
        vm.setTerrainMode(true)
        vm.await { it.terrainForm.proposed != null }

        vm.setTerrainText(madrid.latitudeDeg.toString(), madrid.longitudeDeg.toString(), "1.5")
        val marked = vm.await { it.terrainForm.errors.containsKey(TerrainField.FACTOR) }

        marked.terrainForm.errors[TerrainField.FACTOR] shouldBe TerrainError.OUT_OF_RANGE
        marked.terrainForm.shownErrors[TerrainField.FACTOR] shouldBe TerrainError.OUT_OF_RANGE
        marked.draft.terrain.shouldBeNull()
        vm.save()
        vm.await { it.terrainForm.attempted }
        vm.state.value.savedId.shouldBeNull()
    }

    @Test
    fun `S058 RF-4 lo que falta no se marca hasta intentar guardar y un texto que no es un numero se marca`() {
        val vm = newProject(centre = null)
        vm.setTerrainMode(true)
        vm.await { it.terrainForm.noCenter }

        vm.state.value.terrainForm.shownErrors shouldBe emptyMap()
        vm.setTerrainText("abc", "", "")
        vm.await { it.terrainForm.errors[TerrainField.ORIGIN] == TerrainError.NOT_FINITE }
        vm.state.value.terrainForm.shownErrors[TerrainField.ORIGIN] shouldBe TerrainError.NOT_FINITE
        vm.save()
        vm.await { it.terrainForm.attempted }
        vm.state.value.terrainForm.shownErrors[TerrainField.FACTOR] shouldBe TerrainError.REQUIRED
    }

    @Test
    fun `S058 RF-5 un origen fuera del ambito del SC se marca y cambiar a un SC que no lo admite tambien`() {
        val vm = newProject()
        vm.setTerrainMode(true)
        vm.await { it.draft.terrain != null }
        val lambert93 = LibraryCrs(
            (CrsCatalog.bundled.build(2154, CorrectionsDatum.ETRS89, GeoPoint(48.0, 2.0)) as CrsBuildResult.Ready).definition,
            CorrectionsDatum.ETRS89,
        )

        vm.update(vm.state.value.draft.withCrs(lambert93)) // Madrid queda al sur del ambito de Lambert-93

        vm.await { it.terrainForm.errors[TerrainField.ORIGIN] == TerrainError.OUT_OF_AREA }.draft.terrain.shouldBeNull()
        vm.update(vm.state.value.draft.withCrs(SpainCrs.ETRS89_UTM30))
        vm.await { it.draft.terrain != null }
    }

    @Test
    fun `S058 RF-2 con un SC geografico no se ofrece terreno`() {
        val vm = newProject()
        vm.update(vm.state.value.draft.withCrs(SpainCrs.ETRS89_GEOGRAPHIC))
        show(vm)

        compose.onNodeWithTag(terrainOptionTag(true)).assertDoesNotExist()
        compose.onNodeWithTag(terrainOptionTag(false)).assertDoesNotExist()
    }

    @Test
    fun `S058 RF-1 al guardar un proyecto nuevo en terreno queda con su plano y al elegir cuadricula sin el`() {
        val vm = newProject()
        vm.setTerrainMode(true)
        vm.await { it.draft.terrain != null }
        val plane = vm.state.value.draft.terrain!!

        vm.save()
        val id = vm.await { it.savedId != null }.savedId!!

        runBlocking { projects.project(id) }.shouldNotBeNull().terrain shouldBe plane
        vm.setTerrainMode(false)
        vm.state.value.draft.terrain.shouldBeNull()
        vm.state.value.terrainForm.terrain shouldBe false
    }

    @Test
    fun `S058 RF-1 al abrir un proyecto en terreno el formulario lo muestra con su origen y su factor`() {
        val id = runBlocking {
            projects.create(
                ProjectDefaults().toDraft().copy(
                    name = "Obra",
                    terrain = es.topophone.core.model.project.TerrainPlane(40.4168, -3.7038, 1.0002),
                ),
            ).let { (it as es.topophone.data.project.SaveResult.Saved).id }
        }
        val vm = viewModel()

        vm.start(id, null)
        val form = vm.await { it.loaded }.terrainForm

        form.terrain shouldBe true
        form.originLatitude shouldBe "40.4168"
        form.factor shouldBe "1.0002"
        vm.state.value.originalTerrain shouldBe es.topophone.core.model.project.TerrainPlane(40.4168, -3.7038, 1.0002)
    }

    @Test
    fun `S058 RF-3 los campos del formulario escriben el origen y el factor`() {
        val vm = newProject()
        vm.setTerrainMode(true)
        vm.await { it.terrainForm.proposed != null }
        show(vm)

        compose.onNodeWithTag(TERRAIN_FACTOR_TAG).performScrollTo().performTextReplacement("1.0003")

        vm.await { it.draft.terrain?.factor == 1.0003 }
        compose.onNodeWithTag(TERRAIN_USE_PROPOSED_TAG).performScrollTo().assertIsDisplayed()
    }
}
