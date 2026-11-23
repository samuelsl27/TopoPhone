package es.topophone.app.ui.export

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.ObjectLines
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.shp.export.ShpCoordinates
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.export.ShpExportPreferences
import es.topophone.data.gpkg.GpkgExportCrs
import es.topophone.data.localization.ActiveLocalization
import es.topophone.data.localization.RoomLocalizationRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

/** Con una localización activa, Shapefile y GeoPackage solo van en geográficas y la pantalla lo dice (S039/RF-23). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LocalizationExportNoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private class FixedShpPreferences(initial: ShpCoordinates) : ShpExportPreferences {
        override val coordinates: Flow<ShpCoordinates> = MutableStateFlow(initial)

        override suspend fun save(coordinates: ShpCoordinates) = Unit
    }

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val localizations = RoomLocalizationRepository(db)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val repository = FakePointRepository(listOf(measured("1", projectId = projectId)))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun activate() = runBlocking {
        localizations.addPair(projectId, LocalizationPair("q1", "x", "X", 1.0, 2.0, null, 11.0, 2.0, null))
        localizations.apply(projectId)
    }

    private fun gpkg(): GpkgExportViewModel {
        val vm =
            GpkgExportViewModel(repository, projects, ExportSelection(), RecordingLog(), localization = ActiveLocalization(localizations))
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, false)
        return vm
    }

    private fun shp(): ShpExportViewModel {
        val vm = ShpExportViewModel(
            repository,
            projects,
            ExportSelection(),
            FixedShpPreferences(ShpCoordinates.PROJECTED),
            RecordingLog(),
            ObjectLines.NONE,
            localization = ActiveLocalization(localizations),
        )
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, false)
        return vm
    }

    @Test
    fun `S039 RF-23 el GeoPackage con localizacion activa va en geograficas y no deja elegir el sistema del proyecto`() {
        activate()

        val state = runBlocking { withTimeout(5_000) { gpkg().state.first { it.loaded } } }

        state.localized shouldBe true
        state.canChooseCrs shouldBe false
        state.crs shouldBe GpkgExportCrs.GEOGRAPHIC
        state.srsId shouldBe state.geographicSrsId
    }

    @Test
    fun `S039 RF-23 el Shapefile con localizacion activa va en geograficas y no deja elegir el sistema del proyecto`() {
        activate()

        val state = runBlocking { withTimeout(5_000) { shp().state.first { it.loaded } } }

        state.localized shouldBe true
        state.canChooseCoordinates shouldBe false
        state.coordinates shouldBe ShpCoordinates.GEOGRAPHIC
        state.epsg shouldBe state.geographicEpsg
    }

    @Test
    fun `S039 RF-23 sin localizacion activa se sigue pudiendo elegir el sistema del proyecto`() {
        val g = runBlocking { withTimeout(5_000) { gpkg().state.first { it.loaded } } }
        val s = runBlocking { withTimeout(5_000) { shp().state.first { it.loaded } } }

        g.localized shouldBe false
        g.canChooseCrs shouldBe true
        s.localized shouldBe false
        s.canChooseCoordinates shouldBe true
    }

    @Test
    fun `S039 RF-23 las pantallas de Shapefile y GeoPackage lo dicen con texto`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ShpExportScreen(
                    ThemeState.DEFAULT,
                    {},
                    ShpExportUiState(
                        loaded = true,
                        projectName = "Obra",
                        pointCount = 3,
                        epsg = 4258,
                        projectEpsg = 25830,
                        localized = true,
                    ),
                    ShpExportActions(),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(SHP_LOCALIZED_NOTICE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S039 RF-23 la pantalla de GeoPackage dice que va en geograficas`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgExportScreen(
                    ThemeState.DEFAULT,
                    {},
                    GpkgExportUiState(
                        loaded = true,
                        projectName = "Obra",
                        pointCount = 3,
                        srsId = 4258,
                        projectSrsId = 25830,
                        localized = true,
                    ),
                    GpkgExportActions(),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(GPKG_LOCALIZED_NOTICE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S039 RF-23 sin localizacion las pantallas no llevan el aviso`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GpkgExportScreen(
                    ThemeState.DEFAULT,
                    {},
                    GpkgExportUiState(loaded = true, projectName = "Obra", pointCount = 3, srsId = 25830, projectSrsId = 25830),
                    GpkgExportActions(),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(GPKG_LOCALIZED_NOTICE_TAG).assertDoesNotExist()
    }
}
