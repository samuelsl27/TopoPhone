package es.topophone.app.ui.points

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointTilt
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.TiltAngles
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.PoleTip
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
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

/** La inclinación de un punto en su ficha y la corrección del jalón a lo largo de él (S016/RF-23, RF-25, RF-26). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsTiltTest {

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
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private val pole: PoleVector = run {
        val theta = Math.toRadians(12.0)
        val alpha = Math.toRadians(210.5)
        PoleVector(sin(theta) * sin(alpha), sin(theta) * cos(alpha), cos(theta))
    }
    private val angles = TiltAngles(
        tiltRad = Math.toRadians(12.0),
        directionRad = Math.toRadians(210.5),
        headingRad = Math.toRadians(123.4),
        maxTiltRad = Math.toRadians(14.0),
        horizontalAccuracyM = 0.0027,
        verticalAccuracyM = 0.0020,
        pole = pole,
    )

    private fun withTilt(tilt: PointTilt?, projectId: String = "p1"): Point = PointFixtures.measured("12", projectId = projectId).let {
        it.copy(measurement = it.measurement!!.copy(tilt = tilt))
    }

    private fun SemanticsNodeInteraction.shows(text: String): SemanticsNodeInteraction =
        assert(hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

    private fun show(point: Point) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            PointDetailsScreen(ThemeState.DEFAULT, {}, PointDetailsUiState(project, point), PointDetailsActions(), {})
        }
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun `S016 RF-23 la ficha tiene la inclinacion en las unidades`() {
        show(withTilt(PointTilt(TiltMode.APP, verified = true, angles = angles)))

        row(POINT_TILT_TAG).shows(text(R.string.point_tilt_title))
        row("point_tilt_angle").shows(AngleFormat.direction(Math.toRadians(12.0), AngularUnit.DECIMAL_DEGREES, spanish))
        row("point_tilt_direction").shows("210,500°")
        row("point_tilt_heading").shows("123,400°")
        row("point_tilt_max").shows("14,000°")
        row("point_tilt_sigma_h").shows("${LengthFormat.inUnit(0.0027, LinearUnit.METRE, spanish)} m")
        row("point_tilt_sigma_v").shows("${LengthFormat.inUnit(0.0020, LinearUnit.METRE, spanish)} m")
        row("point_tilt_mode").shows(text(R.string.tilt_mode_verified, text(R.string.tilt_mode_app)))
    }

    @Test
    fun `S016 RF-23 sin angulos no hay tarjeta pero si el modo`() {
        show(withTilt(PointTilt(TiltMode.VERTICAL, verified = false, angles = null)))

        compose.onNodeWithTag(POINT_TILT_TAG).assertDoesNotExist()
        row("point_tilt_mode").shows(text(R.string.tilt_mode_vertical))
    }

    @Test
    fun `S016 RF-26 en RECEIVER la antena se rotula como punta`() {
        show(withTilt(PointTilt(TiltMode.RECEIVER, verified = false, angles = angles.copy(receiverPoleHeightM = 1.8))))

        row("point_antenna_lat").shows(text(R.string.point_receiver_tip_latitude))
        row("point_antenna_h").shows(text(R.string.point_receiver_tip_height))
    }

    @Test
    fun `S016 RF-26 en APP sigue siendo la antena`() {
        show(withTilt(PointTilt(TiltMode.APP, verified = true, angles = angles)))

        row("point_antenna_lat").shows(text(R.string.point_antenna_latitude))
    }

    @Test
    fun `S016 RF-25 corregir el jalon mueve la punta a lo largo del jalon`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            val projects = RoomProjectRepository(db)
            val id = runBlocking {
                projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
            }
            val point = withTilt(PointTilt(TiltMode.APP, verified = true, angles = angles), projectId = id)
            val repository = FakePointRepository(listOf(point), clock = { 5_000 })
            val vm = PointDetailsViewModel(repository, projects) { ProjectPositionCalculator() }
            CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            vm.start(id, point.id)
            runBlocking { withTimeout(5_000) { vm.state.first { it.point != null } } }

            vm.correctPole(2.0)

            val corrected = runBlocking { withTimeout(5_000) { vm.state.first { it.point?.measurement?.poleHeightM == 2.0 } } }.point
            val expected = PoleTip.fromAntenna(point.measurement!!.antenna, pole, 2.0 + 0.134)
            val ground = corrected.shouldNotBeNull().coordinates.ground
            ground.latitudeDeg shouldBe (expected.latitudeDeg plusOrMinus 1e-11)
            ground.longitudeDeg shouldBe (expected.longitudeDeg plusOrMinus 1e-11)
            ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (expected.ellipsoidalHeightM!! plusOrMinus 1e-6)
        } finally {
            Dispatchers.resetMain()
            db.close()
        }
    }
}
