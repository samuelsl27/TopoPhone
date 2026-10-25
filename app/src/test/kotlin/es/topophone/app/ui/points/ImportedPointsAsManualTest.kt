package es.topophone.app.ui.points

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.map.MapPoints
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Un punto importado se trata como uno manual: edita, duplica, borra, oculta y replanta (S028/RF-33). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ImportedPointsAsManualTest {

    @get:Rule
    val compose = createComposeRule()

    private val es = Locale.forLanguageTag("es-ES")
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val imported = manual("12", projectId = projectId).copy(origin = PointOrigin.IMPORTED, importId = "imp-1")
    private val repository = FakePointRepository(listOf(imported), clock = { 5_000 })

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `S028 RF-33 se edita con historial y sigue siendo importado de la misma importacion`() {
        val vm = PointFormViewModel(repository, projects) { ProjectPositionCalculator() }
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, pointId = "id-12", locale = es)
        runBlocking { withTimeout(5_000) { vm.state.first { it.loaded } } }
        vm.setName("12b")
        vm.setFirst("440300")
        runBlocking { withTimeout(5_000) { vm.state.first { it.canSave && it.first == "440300" } } }

        vm.save()
        runBlocking { withTimeout(5_000) { vm.events.first() } }

        val point = repository.points.value.single()
        point.name shouldBe "12b"
        point.origin shouldBe PointOrigin.IMPORTED
        point.importId shouldBe "imp-1"
        point.edited shouldBe false
        point.typed.shouldNotBeNull().first shouldBe 440_300.0
        point.coordinates.easting shouldBe 440_300.0
        repository.historyEntries.value.single().change shouldBe PointChange.EDIT
    }

    @Test
    fun `S028 RF-33 se duplica como una copia manual con el siguiente nombre libre, fuera de la importacion`() {
        val vm = PointDetailsViewModel(repository, projects) { ProjectPositionCalculator() }
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, "id-12")
        runBlocking { withTimeout(5_000) { vm.state.first { it.point != null } } }

        vm.duplicate()

        val event = runBlocking { withTimeout(5_000) { vm.events.first() } }.shouldBeInstanceOf<PointDetailsEvent.Open>()
        val copy = repository.points.value.single { it.id == event.pointId }
        copy.name shouldBe "13"
        copy.origin shouldBe PointOrigin.MANUAL
        copy.copyOf shouldBe "12"
        copy.importId.shouldBeNull()
        copy.coordinates shouldBe imported.coordinates
        repository.points.value.first { it.id == "id-12" }.importId shouldBe "imp-1"
    }

    @Test
    fun `S028 RF-33 se borra a la papelera y se restaura siendo todavia importado`() {
        val vm = PointDetailsViewModel(repository, projects) { ProjectPositionCalculator() }
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, "id-12")
        runBlocking { withTimeout(5_000) { vm.state.first { it.point != null } } }

        vm.delete()
        runBlocking { withTimeout(5_000) { vm.events.first() } } shouldBe PointDetailsEvent.Close

        repository.points.value.single().deletedAtMs shouldBe 5_000L
        runBlocking { repository.restore("id-12") }
        repository.points.value.single().deletedAtMs.shouldBeNull()
        repository.points.value.single().origin shouldBe PointOrigin.IMPORTED
    }

    @Test
    fun `S028 RF-33 se oculta del mapa como cualquier otro punto`() {
        runBlocking { repository.setHidden(listOf("id-12"), true) }

        MapPoints.from(repository.points.value.map { it.summary() }) shouldBe emptyList()
    }

    @Test
    fun `S028 RF-33 la ficha ofrece editar, replantear, duplicar y borrar, y no el jalon`() {
        val calls = mutableListOf<String>()
        val project = Project(
            id = projectId,
            name = "Calle Mayor 12",
            author = "",
            description = "",
            crs = ProjectCrs.ETRS89_UTM30,
            linearUnit = LinearUnit.METRE,
            angularUnit = AngularUnit.DMS,
            correctionsDatum = CorrectionsDatum.ETRS89,
            folderId = null,
            createdAtMs = 1_000,
            modifiedAtMs = 1_000,
            openedAtMs = null,
            deletedAtMs = null,
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    PointDetailsUiState(project, imported),
                    PointDetailsActions(onEdit = { calls += "edit" }, onStakeout = { calls += "stakeout" }, onDuplicate = {
                        calls +=
                            "duplicate"
                    }),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(POINT_EDIT_TAG).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag(POINT_STAKEOUT_TAG).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag(POINT_DUPLICATE_TAG).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag(POINT_POLE_TAG).assertDoesNotExist()

        calls shouldBe listOf("edit", "stakeout", "duplicate")
    }
}
