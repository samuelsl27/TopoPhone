package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.crs.CrsLibraryHost
import es.topophone.app.ui.crs.CrsLibraryRequest
import es.topophone.app.ui.map.FakeMapPreferences
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.codes.RoomCodeLibraryRepository
import es.topophone.data.crs.RoomProjectCrsHistoryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

/** Cambiar el SC de un proyecto con puntos desde el formulario: aviso de los puntos que no se calculan, solo el mismo datum y el historial (S035/RF-17 a RF-20). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ChangeProjectCrsTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val history = RoomProjectCrsHistoryRepository(db)
    private val calculator = ProjectPositionCalculator()
    private val catalog = CrsCatalog.bundled
    private val barcelona = GeodeticPosition(41.3851, 2.1734, 12.0)
    private val madrid = GeodeticPosition(40.4168, -3.7038, 667.0)

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

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

    private fun library(epsg: Int, at: GeoPoint = GeoPoint(41.4, 2.2)): LibraryCrs {
        val definition = when (val r = catalog.build(epsg, CorrectionsDatum.ETRS89, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            is CrsBuildResult.Unavailable -> error("EPSG:$epsg")
        }
        return LibraryCrs(definition, CorrectionsDatum.ETRS89)
    }

    private fun createProject(): String = runBlocking {
        projects.create(
            ProjectDefaults().toDraft().copy(name = "Obra", crs = SpainCrs.ETRS89_UTM30, heightSystem = HeightSystem.ELLIPSOIDAL),
        ).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun addPoint(projectId: String, id: String, ground: GeodeticPosition) {
        val position = calculator.fromGround(ground, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)
        insert(
            db.openHelper.writableDatabase,
            mapOf(
                "id" to id,
                "project_id" to projectId,
                "name" to id,
                "ground_lat" to ground.latitudeDeg,
                "ground_lon" to ground.longitudeDeg,
                "ground_h" to ground.ellipsoidalHeightM,
                "easting" to position.easting,
                "northing" to position.northing,
                "height" to position.height,
            ),
        )
    }

    private fun insert(sqlite: SupportSQLiteDatabase, values: Map<String, Any?>) {
        val columns = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        sqlite.query("PRAGMA table_info(points)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                val type = c.getString(2).uppercase()
                if (name in values) {
                    columns += name
                    args += values.getValue(name)
                } else if (c.getInt(3) == 1 && c.isNull(4)) {
                    columns += name
                    args += if ("INT" in type) {
                        0
                    } else if ("REAL" in type) {
                        0.0
                    } else {
                        "x"
                    }
                }
            }
        }
        sqlite.execSQL("INSERT INTO points (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})", args.toTypedArray())
    }

    private fun easting(id: String): Double? = db.openHelper.readableDatabase.query("SELECT easting FROM points WHERE id = '$id'").use {
        it.moveToFirst() shouldBe true
        if (it.isNull(0)) null else it.getDouble(0)
    }

    private fun viewModel() = ProjectFormViewModel(
        projects,
        FakeProjectPreferences(ProjectDefaults()),
        session,
        FakeMapPreferences(),
        RoomCodeLibraryRepository(db),
        history,
        centers(),
    )

    private fun ProjectFormViewModel.await(predicate: (ProjectFormUiState) -> Boolean): ProjectFormUiState = runBlocking {
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { state.collect {} }
        try {
            withTimeout(5_000) { state.first(predicate) }
        } finally {
            collector.cancel()
        }
    }

    private fun edit(id: String): ProjectFormViewModel = viewModel().also {
        it.start(projectId = id, folderId = null)
        it.await { s -> s.loaded }
    }

    @Test
    fun `S035 RF-18 cambiar a un SC del mismo datum recalcula las coordenadas de los puntos y queda en el historial`() {
        val id = createProject()
        addPoint(id, "p1", barcelona)
        val target = library(25831)
        val vm = edit(id)

        vm.update(vm.state.value.draft.copy(crs = target))
        vm.save()
        vm.await { it.savedId == id }

        projects.let { runBlocking { it.project(id) } }.shouldNotBeNull().crs shouldBe target
        val expected = calculator.fromGround(barcelona, target, HeightSystem.ELLIPSOIDAL)
        easting("p1").shouldNotBeNull() shouldBe (expected.easting!! plusOrMinus 1e-9)
        val changes = runBlocking { history.observe(id).first() }
        changes.size shouldBe 1
        changes.single().from shouldBe SpainCrs.ETRS89_UTM30
        changes.single().to shouldBe target
    }

    @Test
    fun `S035 RF-19 un punto que el SC nuevo no calcula avisa, cancelar no cambia nada y continuar lo aplica`() {
        val id = createProject()
        addPoint(id, "p1", madrid)
        val lambert93 = library(2154, GeoPoint(48.0, 2.0)) // al sur de su ambito
        val vm = edit(id)
        val before = easting("p1")

        vm.update(vm.state.value.draft.copy(crs = lambert93))
        vm.save()
        val asked = vm.await { it.crsChangePlan != null }

        asked.crsChangePlan!!.issues.map { it.id } shouldBe listOf("p1")
        asked.crsChangePlan!!.total shouldBe 1
        vm.cancelCrsChange()
        vm.await { it.crsChangePlan == null }
        runBlocking { projects.project(id) }.shouldNotBeNull().crs shouldBe SpainCrs.ETRS89_UTM30
        easting("p1") shouldBe before
        runBlocking { history.observe(id).first() } shouldBe emptyList()

        vm.save()
        vm.await { it.crsChangePlan != null }
        vm.confirmCrsChange()
        vm.await { it.savedId == id }

        runBlocking { projects.project(id) }.shouldNotBeNull().crs shouldBe lambert93
        easting("p1").shouldBeNull()
        runBlocking { history.observe(id).first() }.size shouldBe 1
    }

    @Test
    fun `S035 RF-18 guardar otros campos sin cambiar el SC no toca el historial ni los puntos`() {
        val id = createProject()
        addPoint(id, "p1", barcelona)
        val before = easting("p1")
        val vm = edit(id)

        vm.update(vm.state.value.draft.copy(name = "Otro nombre"))
        vm.save()
        vm.await { it.savedId == id }

        easting("p1") shouldBe before
        runBlocking { history.observe(id).first() } shouldBe emptyList()
        runBlocking { projects.project(id) }.shouldNotBeNull().name shouldBe "Otro nombre"
    }

    @Test
    fun `S035 RF-20 al cambiar solo el sistema de alturas tambien queda en el historial`() {
        val id = createProject()
        val vm = edit(id)

        vm.update(vm.state.value.draft.copy(heightSystem = HeightSystem.ORTHOMETRIC))
        vm.save()
        vm.await { it.savedId == id }

        runBlocking { history.observe(id).first() }.single().toHeightSystem shouldBe HeightSystem.ORTHOMETRIC
    }

    /** Un anfitrión de pega que «elige» [chosen] al pulsar. */
    private class FakeHost(private val chosen: LibraryCrs) : CrsLibraryHost {
        var chooser: (() -> Unit)? = null

        @Composable
        override fun Flow(
            themeState: ThemeState,
            onToggleSun: () -> Unit,
            request: CrsLibraryRequest,
            onChosen: (LibraryCrs) -> Unit,
            onDismiss: () -> Unit,
        ) {
            chooser = { onChosen(chosen) }
            Column { Text("flujo abierto", modifier = Modifier.testTag("fake_flow_open")) }
        }

        @Composable
        override fun MissingGrids(crs: ProjectCrs) = Unit
    }

    private fun showEditing(host: FakeHost, otherDatumCalls: MutableList<Unit>): () -> es.topophone.core.model.project.ProjectDraft {
        var current by mutableStateOf(ProjectDefaults().toDraft().copy(name = "Obra", crs = SpainCrs.ETRS89_UTM30))
        var otherDatum by mutableStateOf(false)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectFormScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectFormUiState(
                        draft = current,
                        loaded = true,
                        editing = true,
                        originalCrs = SpainCrs.ETRS89_UTM30,
                        originalHeightSystem = HeightSystem.ORTHOMETRIC,
                        otherDatum = otherDatum,
                    ),
                    ProjectFormActions(
                        onChange = { current = it },
                        onOtherDatum = {
                            otherDatumCalls += Unit
                            otherDatum = true
                        },
                    ),
                    library = host,
                )
            }
        }
        return { current }
    }

    @Test
    fun `S035 RF-17 al editar se ofrece elegir otro SC y uno de otro datum no se aplica y se avisa`() {
        val ed50 = library(23030, GeoPoint(40.4, -3.7))
        val host = FakeHost(ed50)
        val calls = mutableListOf<Unit>()
        val draft = showEditing(host, calls)

        compose.onNodeWithTag(CRS_LIBRARY_OPEN_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("fake_flow_open").assertIsDisplayed()
        compose.runOnUiThread { host.chooser!!.invoke() }
        compose.waitForIdle()

        calls.size shouldBe 1
        draft().crs shouldBe SpainCrs.ETRS89_UTM30
        compose.onNodeWithTag(CRS_OTHER_DATUM_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S035 RF-17 al editar uno del mismo datum se aplica en el formulario`() {
        val lambert = library(2154, GeoPoint(48.0, 2.0))
        val host = FakeHost(lambert)
        val calls = mutableListOf<Unit>()
        val draft = showEditing(host, calls)

        compose.onNodeWithTag(CRS_LIBRARY_OPEN_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("fake_flow_open").assertIsDisplayed()
        compose.runOnUiThread { host.chooser!!.invoke() }
        compose.waitForIdle()

        calls.size shouldBe 0
        draft().crs shouldBe lambert
    }

    private fun centers() = es.topophone.app.ui.projects.ProjectCenterProvider(
        es.topophone.app.ui.map.FakePointRepository(),
        es.topophone.app.ui.map.FakeMapViewportRepository(),
    )
}
