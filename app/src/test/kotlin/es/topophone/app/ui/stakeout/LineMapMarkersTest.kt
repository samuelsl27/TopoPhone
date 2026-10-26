package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.LineLocation
import es.topophone.core.survey.LineObjective
import es.topophone.core.survey.LinePosition
import es.topophone.core.survey.PlanePoint
import es.topophone.core.survey.RangePosition
import es.topophone.core.survey.StakeZone
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que ve el mapa en el replanteo de una línea: la línea elegida más gruesa, el objetivo de ahora (el vértice, el punto más
 * cercano o el punto de la serie), la punta y la línea hasta él; y que la línea elegida se ve aunque la capa esté oculta
 * (S041/RF-5).
 */
@RunWith(AndroidJUnit4::class)
class LineMapMarkersTest {

    @get:Rule
    val compose = createComposeRule()

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

    private val chosen = listOf(GeoPoint(40.41, -3.70), GeoPoint(40.42, -3.69), GeoPoint(40.43, -3.68))
    private val tipGround = GeodeticPosition(40.4168, -3.7038, 699.0)
    private val designGround = GeodeticPosition(40.4169, -3.7038, 699.0)
    private val element = LineElementInfo("l1", "Eje", "calle.dxf", CadEntityKind.POLYLINE, 90.0, false, null, null)

    private fun objective(e: Double, n: Double) = LineObjective(
        point = PlanePoint(e, n),
        chainageM = 30.0,
        heightM = null,
        location = LineLocation(PlanePoint(e, n), 30.0, 1.0, 1.0, LinePosition.ON_LINE, 60.0, 0),
        lateralErrorM = 1.0,
        position = RangePosition.WITHIN,
        alongM = 30.0,
        remainingM = 60.0,
        offsetM = 0.0,
    )

    private fun on(distance: Double, line: LineObjective? = null, objective: PlanePoint? = null) = GuidanceState.On(
        GuidanceUi(
            guidance = Guidance(distance, 0.0, distance, 0.0, null, null, null),
            zone = StakeZone.FAR,
            heading = HeadingResult.Available(0.0),
            tipEastM = 0.0,
            tipNorthM = -distance,
            dianaRadiusM = 0.2,
            solution = SolutionStatus.FIX,
            horizontalAccuracyM = 0.01,
            tipUncompensated = false,
            tip = tipGround,
            line = line,
            objective = objective,
        ),
    )

    private fun state(guidance: GuidanceState, mode: LineStakeoutMode = LineStakeoutMode.BY_SEGMENTS, closed: LineClosedReason? = null) =
        LineStakeoutUiState(
            project = project,
            element = element,
            mode = mode,
            guidance = guidance,
            closed = closed,
            target = StakeTarget("cad:l1:7:3", "Eje", "", PointCoordinates(designGround, 440_290.0, 4_474_257.0, null)),
        )

    @Test
    fun `S041 RF-5 con un punto fijo el objetivo es el vertice elegido y la linea elegida va con el`() {
        val marker = LineStakeoutMarkers.of(state(on(5.0)), chosen).shouldNotBeNull()

        marker.design shouldBe GeoPoint(designGround.latitudeDeg, designGround.longitudeDeg)
        marker.chosen shouldBe chosen
        marker.tip shouldBe GeoPoint(tipGround.latitudeDeg, tipGround.longitudeDeg)
    }

    @Test
    fun `S041 RF-5 con la punta a mas de 40 cm del objetivo se dibuja la linea hasta el y a menos no`() {
        LineStakeoutMarkers.of(state(on(5.0)), chosen).shouldNotBeNull().line shouldBe true
        LineStakeoutMarkers.of(state(on(0.4)), chosen).shouldNotBeNull().line shouldBe false
        LineStakeoutMarkers.of(state(on(0.1)), chosen).shouldNotBeNull().line shouldBe false
    }

    @Test
    fun `S041 RF-5 con A la linea el objetivo es el punto mas cercano, que se mueve con la punta`() {
        val first = LineStakeoutMarkers.of(
            state(on(3.0, objective(440_300.0, 4_474_300.0), PlanePoint(440_300.0, 4_474_300.0)), LineStakeoutMode.TO_LINE),
            chosen,
        ).shouldNotBeNull()
        val second = LineStakeoutMarkers.of(
            state(on(3.0, objective(440_320.0, 4_474_300.0), PlanePoint(440_320.0, 4_474_300.0)), LineStakeoutMode.TO_LINE),
            chosen,
        ).shouldNotBeNull()

        val expected = CadPlacement.geographic(project.crs, 440_300.0, 4_474_300.0).shouldNotBeNull()
        first.design.shouldNotBeNull().latitudeDeg shouldBe (expected.first plusOrMinus 1e-9)
        first.design.shouldNotBeNull().longitudeDeg shouldBe (expected.second plusOrMinus 1e-9)
        (second.design.shouldNotBeNull().longitudeDeg > first.design.shouldNotBeNull().longitudeDeg) shouldBe true
        second.chosen shouldBe chosen
    }

    @Test
    fun `S041 RF-5 con la guia apagada se ve la linea elegida y no hay objetivo ni punta`() {
        val marker = LineStakeoutMarkers.of(state(GuidanceState.Off(GuidanceOff.NO_FIX), LineStakeoutMode.ALONG), chosen).shouldNotBeNull()

        marker.chosen shouldBe chosen
        marker.design.shouldBeNull()
        marker.tip.shouldBeNull()
        marker.line shouldBe false
    }

    @Test
    fun `S041 RF-5 sin replanteo, sin linea o cerrado por el borrado del dibujo no se dibuja nada`() {
        LineStakeoutMarkers.of(null, chosen).shouldBeNull()
        LineStakeoutMarkers.of(state(on(5.0)).copy(element = null), chosen).shouldBeNull()
        LineStakeoutMarkers.of(state(on(5.0), closed = LineClosedReason.DRAWING_DELETED), chosen).shouldBeNull()
    }

    @Test
    fun `S041 RF-5 la pantalla del proyecto lleva al mapa la linea elegida aunque la capa del dibujo este oculta`() {
        val map = FakeMapController()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(map),
                    design = null, // la capa está oculta: el mapa no dibuja el dibujo
                    lineStakeout = state(on(5.0)),
                    lineHighlight = chosen,
                )
            }
        }
        compose.waitForIdle()

        map.stakeout.shouldNotBeNull().chosen shouldBe chosen
        map.design.shouldBeNull()
    }
}
