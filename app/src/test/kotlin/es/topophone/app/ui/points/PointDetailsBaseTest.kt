package es.topophone.app.ui.points

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
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
import io.kotest.matchers.doubles.plusOrMinus
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La base con la que se midió un punto en su ficha (S015/RF-21). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsBaseTest {

    @get:Rule
    val compose = createComposeRule()

    private val spanish = Locale.forLanguageTag("es-ES")
    private val project = Project(
        id = "p1",
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

    /** La estación 6 de RGAPA (Avilés), tal como la da su 1006. */
    private val avl13m = PointBase(
        GeodeticPosition(43.5578666032, -5.9130704113, 78.4338),
        BaseSource.CORRECTIONS,
        stationId = 6,
        antennaHeightM = 0.0,
        baselineM = 382_123.4567,
    )

    private fun withBase(base: PointBase?): Point = PointFixtures.measured("12").let {
        it.copy(measurement = it.measurement!!.copy(base = base))
    }

    private fun SemanticsNodeInteraction.shows(text: String): SemanticsNodeInteraction =
        assert(hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

    private fun show(state: PointDetailsUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) { PointDetailsScreen(ThemeState.DEFAULT, {}, state, PointDetailsActions(), {}) }
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun `S015 RF-21 la base en geograficas y en el SC del proyecto, su fuente y la linea base`() {
        val project30 = project
        val projected = ProjectPositionCalculator().fromGround(avl13m.position, project30.crs, project30.heightSystem)
        show(PointDetailsUiState(project30, withBase(avl13m), baseCoordinates = projected))

        row(POINT_BASE_TAG).shows(text(R.string.base_title))
        row("point_base_source").shows(text(R.string.base_source_corrections, 6))
        row("point_base_easting").shows("${LengthFormat.inUnit(projected.easting!!, LinearUnit.METRE, spanish)} m")
        row("point_base_northing").shows("${LengthFormat.inUnit(projected.northing!!, LinearUnit.METRE, spanish)} m")
        row("point_base_height").shows("${LengthFormat.inUnit(projected.height!!, LinearUnit.METRE, spanish)} m")
        row("point_base_lat").shows(AngleFormat.latitude(43.5578666032, AngularUnit.DMS, spanish))
        row("point_base_lon").shows(AngleFormat.longitude(-5.9130704113, AngularUnit.DMS, spanish))
        row("point_base_antenna_height").shows("0,000 m")
        row("point_baseline").shows("${LengthFormat.inUnit(382_123.4567, LinearUnit.METRE, spanish)} m")
    }

    @Test
    fun `S015 RF-21 la linea base va en la unidad lineal del proyecto`() {
        val feet = project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT)
        show(PointDetailsUiState(feet, withBase(avl13m), baseCoordinates = null))

        row("point_baseline").shows("${LengthFormat.inUnit(382_123.4567, LinearUnit.INTERNATIONAL_FOOT, spanish)} ft")
    }

    @Test
    fun `S015 RF-21 fuera del SC del proyecto el Este, el Norte y la altura de la base no estan disponibles`() {
        val canarias = avl13m.copy(position = GeodeticPosition(28.4636, -16.2518, 35.0))
        val outside = ProjectPositionCalculator().fromGround(canarias.position, project.crs, project.heightSystem)
        show(PointDetailsUiState(project, withBase(canarias), baseCoordinates = outside))

        row("point_base_easting").shows(text(R.string.point_history_none))
        row("point_base_height").shows(text(R.string.point_history_none))
    }

    @Test
    fun `S015 RF-21 sin base la ficha no la muestra`() {
        show(PointDetailsUiState(project, withBase(null)))

        compose.onAllNodesWithTag(POINT_BASE_TAG).assertCountEquals(0)
    }

    @Test
    fun `S015 RF-21 el ViewModel calcula la base en el SC del proyecto`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            val projects = RoomProjectRepository(db)
            val id = runBlocking {
                projects.create(ProjectDefaults().toDraft().copy(name = "Avilés")).shouldBeInstanceOf<SaveResult.Saved>().id
            }
            val point = withBase(avl13m).copy(projectId = id)
            val vm = PointDetailsViewModel(FakePointRepository(listOf(point), clock = { 5_000 }), projects) { ProjectPositionCalculator() }
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            vm.start(id, point.id)

            val state = runBlocking { withTimeout(5_000) { vm.state.first { it.baseCoordinates != null } } }
            val expected = ProjectPositionCalculator().fromGround(avl13m.position, state.project!!.crs, state.project!!.heightSystem)
            state.baseCoordinates.shouldNotBeNull().easting.shouldNotBeNull() shouldBe (expected.easting!! plusOrMinus 1e-6)
            collector.cancel()
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
