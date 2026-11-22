package es.topophone.app.ui.points

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.TerrainView
import io.kotest.matchers.nulls.shouldNotBeNull
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La ficha de un punto y su base con el proyecto en terreno: Este y Norte de terreno, la altura igual y «Terreno» (S058/RF-9, RF-11). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsTerrainTest {

    @get:Rule
    val compose = createComposeRule()

    private val spanish = Locale.forLanguageTag("es-ES")
    private val plane = TerrainPlane(40.4, -3.7, 1.0004)
    private val grid = Project(
        "p1", "Obra", "", "", ProjectCrs.ETRS89_UTM30, LinearUnit.METRE, AngularUnit.DMS, CorrectionsDatum.ETRS89,
        null, 1_000, 1_000, null, null,
    )
    private val ground = grid.copy(terrain = plane)
    private val base =
        PointBase(GeodeticPosition(40.5, -3.6, 700.0), BaseSource.RECEIVER, stationId = null, antennaHeightM = 0.0, baselineM = 100.0)

    private fun SemanticsNodeInteraction.shows(text: String): SemanticsNodeInteraction =
        assert(hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

    private fun metres(value: Double) = "${LengthFormat.inUnit(value, LinearUnit.METRE, spanish)} m"

    private fun show(project: Project) {
        val point = PointFixtures.measured("12").let { it.copy(measurement = it.measurement!!.copy(base = base)) }
        val projected = ProjectPositionCalculator().fromGround(base.position, project.crs, project.heightSystem)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    PointDetailsUiState(project, point, baseCoordinates = projected),
                    PointDetailsActions(),
                    {},
                )
            }
        }
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun `S058 RF-9 la ficha de un punto da el Este y el Norte de terreno y lo indica`() {
        val point = PointFixtures.measured("12").coordinates
        val view = TerrainView.of(plane, grid.crs).shouldNotBeNull()
        val expected = view.coordinates(point.easting!!, point.northing!!)

        show(ground)

        compose.onNodeWithTag("point_terrain", useUnmergedTree = true).assertExists()
        row("point_easting").shows(metres(expected.easting))
        row("point_northing").shows(metres(expected.northing))
    }

    @Test
    fun `S058 RF-11 la altura de la ficha es la misma en terreno que en cuadricula`() {
        val height = PointFixtures.measured("12").coordinates.height!!

        show(ground)

        row("point_height").shows(metres(height))
    }

    @Test
    fun `S058 RF-9 la base del punto tambien va en terreno`() {
        val projected = ProjectPositionCalculator().fromGround(base.position, ground.crs, ground.heightSystem)
        val expected = TerrainView.of(plane, ground.crs).shouldNotBeNull().coordinates(projected.easting!!, projected.northing!!)

        show(ground)

        compose.onNodeWithTag("point_base_terrain", useUnmergedTree = true).assertExists()
        row("point_base_easting").shows(metres(expected.easting))
        row("point_base_northing").shows(metres(expected.northing))
    }

    @Test
    fun `S058 RF-9 en cuadricula la ficha no dice Terreno y da las coordenadas de siempre`() {
        val point = PointFixtures.measured("12").coordinates

        show(grid)

        compose.onNodeWithTag("point_terrain").assertDoesNotExist()
        row("point_easting").shows(metres(point.easting!!))
    }
}
