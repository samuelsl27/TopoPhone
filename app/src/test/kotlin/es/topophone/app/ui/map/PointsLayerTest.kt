package es.topophone.app.ui.map

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.point.CollectedPoint
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Puntos del proyecto en el mapa con su nombre (S010/RF-20, RF-21). */
@RunWith(AndroidJUnit4::class)
class PointsLayerTest {

    @get:Rule
    val compose = createComposeRule()

    private fun point(name: String, lat: Double, lon: Double, failures: Set<PrecisionFailure> = emptySet()) = CollectedPoint(
        id = "id-$name",
        projectId = "p1",
        name = name,
        description = "",
        antenna = GeodeticPosition(lat, lon, 700.0),
        poleHeightM = 1.8,
        ground = GeodeticPosition(lat, lon, 698.066),
        easting = 1.0,
        northing = 2.0,
        height = 650.0,
        quality = PointQuality("FIX", 20, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, false),
        rawNmea = "",
        outOfTolerance = failures,
        measuredFromMs = 0,
        measuredToMs = 0,
        createdAtMs = 0,
    )

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

    @Test
    fun `S010 RF-20 cada punto va al mapa en su punto del suelo, con su nombre y si esta fuera de tolerancia`() {
        val points = listOf(point("P1", 40.416_801_234, -3.703_712_345), point("P2", 40.4169, -3.7036, setOf(PrecisionFailure.NOT_FIX)))

        MapPoints.from(points) shouldBe listOf(
            MapPoint("id-P1", "P1", GeoPoint(40.416_801_234, -3.703_712_345), outOfTolerance = false),
            MapPoint("id-P2", "P2", GeoPoint(40.4169, -3.7036), outOfTolerance = true),
        )
    }

    @Test
    fun `S010 RF-20 el nombre se ve desde la escala 1 2500 en la latitud de los puntos`() {
        val points = MapPoints.from(listOf(point("P1", 40.0, -3.7), point("P2", 41.0, -3.7)))

        MapPoints.labelMinZoom(points) shouldBe (WebMercator.zoomForScale(2_500.0, 40.5) plusOrMinus 1e-9)
        MapPoints.labelMinZoom(emptyList()).shouldBeNull()
    }

    @Test
    fun `S010 RF-20 siguiendo al rover los nombres se ven aunque el rover este al norte de los puntos`() {
        val points = MapPoints.from(listOf(point("P1", 40.0, -3.7)))

        val follow = CameraPolicy().onCenterPressed(GeoPoint(40.5, -3.7), camera = null) as CameraCommand.MoveTo

        follow.viewport.zoom shouldBeGreaterThan MapPoints.labelMinZoom(points).shouldNotBeNull()
    }

    @Test
    fun `S010 RF-21 la vista de proyecto lleva al mapa los puntos guardados`() {
        val map = FakeMapController()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project, points = listOf(point("P1", 40.4168, -3.7037))),
                    onBack = {},
                    map = fakeMap(map),
                )
            }
        }
        compose.waitForIdle()

        map.points shouldBe listOf(MapPoint("id-P1", "P1", GeoPoint(40.4168, -3.7037), outOfTolerance = false))
    }
}
