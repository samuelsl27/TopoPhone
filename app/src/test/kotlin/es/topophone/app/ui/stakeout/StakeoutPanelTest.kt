package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.HeadingUnavailable
import es.topophone.core.survey.StakeZone
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El panel del replanteo con estados ya hechos: el punto de diseño, los dos modos de guía, el rumbo, la diferencia de cota,
 * el estado de la solución y la guía apagada (S018/RF-4 a RF-7, RF-11 a RF-13, RF-22, RF-24, RF-25, RF-35).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class StakeoutPanelTest {

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
    private val target = StakeTarget(
        "d1",
        "12",
        "esquina norte",
        PointCoordinates(GeodeticPosition(40.4168, -3.0, 699.4), 440_290.458, 4_474_257.382, 648.19),
    )

    private fun guidance(
        distance: Double = 5.0,
        dEast: Double = 3.0,
        dNorth: Double = 4.0,
        ahead: Double? = 4.0,
        right: Double? = 3.0,
        dHeight: Double? = 0.123,
    ) = Guidance(distance, dEast, dNorth, Math.atan2(dEast, dNorth), ahead, right, dHeight)

    private fun on(
        guidance: Guidance = guidance(),
        heading: HeadingResult = HeadingResult.Available(0.0),
        solution: SolutionStatus = SolutionStatus.FIX,
        accuracy: Double? = 0.012,
        zone: StakeZone = StakeZone.FAR,
    ) = GuidanceState.On(
        GuidanceUi(
            guidance = guidance,
            zone = zone,
            heading = heading,
            tipEastM = -guidance.dEastM,
            tipNorthM = -guidance.dNorthM,
            dianaRadiusM = 0.2,
            solution = solution,
            horizontalAccuracyM = accuracy,
            tipUncompensated = false,
        ),
    )

    private fun state(
        guidance: GuidanceState = on(),
        mode: StakeMode = StakeMode.BY_DISTANCE,
        hasPrevious: Boolean = true,
        hasNext: Boolean = true,
    ) = StakeoutUiState(
        project = project,
        target = target,
        hasPrevious = hasPrevious,
        hasNext = hasNext,
        settings = StakeoutSettings(mode = mode),
        guidance = guidance,
    )

    private var closed = 0
    private var previous = 0
    private var next = 0
    private var modes = mutableListOf<StakeMode>()

    private fun show(state: StakeoutUiState) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            StakeoutPanel(
                state,
                StakeoutActions(
                    onClose = { closed++ },
                    onPrevious = { previous++ },
                    onNext = { next++ },
                    onMode = { modes += it },
                ),
            )
        }
    }

    @Test
    fun `S018 RF-4 muestra el nombre, la descripcion y las coordenadas del punto de diseno con su altura`() {
        show(state())

        compose.onNodeWithTag(STAKEOUT_NAME_TAG).assertTextContains("12", substring = true)
        compose.onNodeWithTag(STAKEOUT_DESCRIPTION_TAG).assertTextContains("esquina norte", substring = true)
        compose.onNodeWithTag(STAKEOUT_DESIGN_TAG).assertTextContains("440.290,458", substring = true)
        compose.onNodeWithTag(STAKEOUT_DESIGN_TAG).assertTextContains("4.474.257,382", substring = true)
        compose.onNodeWithTag(STAKEOUT_DESIGN_TAG).assertTextContains("648,190", substring = true)
    }

    @Test
    fun `S018 RF-4 un diseno sin altura no muestra la altura`() {
        val noHeight = state().copy(target = target.copy(coordinates = target.coordinates.copy(height = null)))
        show(noHeight)

        compose.onNodeWithTag(STAKEOUT_DESIGN_TAG).assertTextContains("440.290,458", substring = true)
        compose.onNodeWithTag(STAKEOUT_DESIGN_TAG).assert(hasText("648", substring = true).not())
    }

    @Test
    fun `S018 RF-5 muestra los dos modos de guia con el elegido seleccionado y permite cambiar`() {
        show(state(mode = StakeMode.BY_DISTANCE))

        compose.onNodeWithTag(stakeModeTag(StakeMode.BY_DISTANCE)).assertIsDisplayed()
        compose.onNodeWithTag(stakeModeTag(StakeMode.BY_DIRECTION)).performClick()

        modes shouldBe listOf(StakeMode.BY_DIRECTION)
    }

    @Test
    fun `S018 RF-6 por distancia muestra la distancia horizontal y las diferencias de Norte y Este con su signo`() {
        show(state(on(guidance(distance = 5.0, dEast = -3.0, dNorth = 4.0)), StakeMode.BY_DISTANCE))

        compose.onNodeWithTag(STAKEOUT_DISTANCE_TAG).assertTextContains("5,000", substring = true)
        compose.onNodeWithTag(STAKEOUT_NORTH_TAG).assertTextContains("+4,000", substring = true)
        compose.onNodeWithTag(STAKEOUT_EAST_TAG).assertTextContains("-3,000", substring = true)
    }

    @Test
    fun `S018 RF-6 las cifras van en la unidad del proyecto`() {
        val feet = state().copy(project = project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT))
        show(feet.copy(guidance = on(guidance(distance = 3.048, dEast = 0.0, dNorth = 3.048))))

        compose.onNodeWithTag(STAKEOUT_DISTANCE_TAG).assertTextContains("10,000 ft", substring = true)
    }

    @Test
    fun `S018 RF-7 por direccion muestra la distancia y adelante y derecha, o atras e izquierda`() {
        show(state(on(guidance(distance = 5.0, ahead = 4.0, right = 3.0)), StakeMode.BY_DIRECTION))

        compose.onNodeWithTag(STAKEOUT_DISTANCE_TAG).assertTextContains("5,000", substring = true)
        compose.onNodeWithTag(STAKEOUT_AHEAD_TAG).assertTextContains("Adelante", substring = true)
        compose.onNodeWithTag(STAKEOUT_AHEAD_TAG).assertTextContains("4,000", substring = true)
        compose.onNodeWithTag(STAKEOUT_RIGHT_TAG).assertTextContains("Derecha", substring = true)
        compose.onNodeWithTag(STAKEOUT_RIGHT_TAG).assertTextContains("3,000", substring = true)
    }

    @Test
    fun `S018 RF-7 con el punto detras y a la izquierda dice atras e izquierda`() {
        show(state(on(guidance(distance = 5.0, ahead = -4.0, right = -3.0)), StakeMode.BY_DIRECTION))

        compose.onNodeWithTag(STAKEOUT_AHEAD_TAG).assertTextContains("Atrás", substring = true)
        compose.onNodeWithTag(STAKEOUT_AHEAD_TAG).assertTextContains("4,000", substring = true)
        compose.onNodeWithTag(STAKEOUT_RIGHT_TAG).assertTextContains("Izquierda", substring = true)
    }

    @Test
    fun `S018 RF-11 sin rumbo por direccion dice por que y sigue dando distancia y Norte y Este`() {
        val noHeading = on(guidance(ahead = null, right = null), HeadingResult.Unavailable(HeadingUnavailable.NOT_MOVING))
        show(state(noHeading, StakeMode.BY_DIRECTION))

        compose.onNodeWithTag(STAKEOUT_HEADING_TAG).assertTextContains("Sin rumbo", substring = true)
        compose.onNodeWithTag(STAKEOUT_DISTANCE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_NORTH_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_EAST_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_AHEAD_TAG).assertDoesNotExist()
    }

    @Test
    fun `S018 RF-11 el IMU sin ETC o sin estado 30 da su propio motivo`() {
        show(state(on(heading = HeadingResult.Unavailable(HeadingUnavailable.NO_IMU)), StakeMode.BY_DIRECTION))
        compose.onNodeWithTag(STAKEOUT_HEADING_TAG).assertTextContains("IMU", substring = true)
    }

    @Test
    fun `S018 RF-9 el ultimo rumbo de marcha dice cuanto hace`() {
        show(state(on(heading = HeadingResult.Available(Math.toRadians(90.0), ageS = 4.2)), StakeMode.BY_DIRECTION))

        compose.onNodeWithTag(STAKEOUT_HEADING_TAG).assertTextContains("Último rumbo", substring = true)
        compose.onNodeWithTag(STAKEOUT_HEADING_TAG).assertTextContains("4 s", substring = true)
        compose.onNodeWithTag(STAKEOUT_HEADING_TAG).assertTextContains("90", substring = true)
    }

    @Test
    fun `S018 RF-12 la diferencia de cota se muestra como dato con su signo y solo si hay`() {
        show(state(on(guidance(dHeight = -0.123))))
        compose.onNodeWithTag(STAKEOUT_DZ_TAG).assertTextContains("-0,123", substring = true)
    }

    @Test
    fun `S018 RF-12 sin diferencia de cota no se muestra`() {
        show(state(on(guidance(dHeight = null))))
        compose.onNodeWithTag(STAKEOUT_DZ_TAG).assertDoesNotExist()
    }

    @Test
    fun `S018 RF-13 muestra la solucion y la precision horizontal`() {
        show(state(on(solution = SolutionStatus.FIX, accuracy = 0.012)))

        compose.onNodeWithTag(STAKEOUT_STATUS_TAG).assertTextContains("Fija", substring = true)
        compose.onNodeWithTag(STAKEOUT_STATUS_TAG).assertTextContains("0,012", substring = true)
    }

    @Test
    fun `S018 RF-13 con otra solucion la dice con texto y no solo con color`() {
        show(state(on(solution = SolutionStatus.FLOAT)))

        compose.onNodeWithTag(STAKEOUT_STATUS_TAG).assertTextContains("Flotante", substring = true)
    }

    @Test
    fun `S018 RF-35 sin posicion la guia esta apagada con su motivo y no hay cifras`() {
        show(state(GuidanceState.Off(GuidanceOff.NO_POSITION)))

        compose.onNodeWithTag(STAKEOUT_OFF_TAG).assertTextContains("Sin posición", substring = true)
        compose.onNodeWithTag(STAKEOUT_DISTANCE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S018 RF-22 sin FIX con Solo FIX la guia esta apagada y lo dice`() {
        show(state(GuidanceState.Off(GuidanceOff.NO_FIX)))

        compose.onNodeWithTag(STAKEOUT_OFF_TAG).assertTextContains("Sin FIX", substring = true)
    }

    @Test
    fun `S018 RF-24 sin estado 30 la guia esta apagada por la inclinacion`() {
        show(state(GuidanceState.Off(GuidanceOff.TILT_NOT_READY)))

        compose.onNodeWithTag(STAKEOUT_OFF_TAG).assertTextContains("inclinación", substring = true)
    }

    @Test
    fun `S018 RF-25 con un corte la guia esta apagada y lo dice`() {
        show(state(GuidanceState.Off(GuidanceOff.NO_DATA)))

        compose.onNodeWithTag(STAKEOUT_OFF_TAG).assertTextContains("Sin datos", substring = true)
    }

    @Test
    fun `S018 RF-3 siguiente y anterior se pueden pulsar y en los extremos no`() {
        show(state(hasPrevious = true, hasNext = true))
        compose.onNodeWithTag(STAKEOUT_PREVIOUS_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(STAKEOUT_NEXT_TAG).assertIsEnabled().performClick()

        previous shouldBe 1
        next shouldBe 1
    }

    @Test
    fun `S018 RF-3 en el primero no hay anterior y en el ultimo no hay siguiente`() {
        show(state(hasPrevious = false, hasNext = true))
        compose.onNodeWithTag(STAKEOUT_PREVIOUS_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(STAKEOUT_NEXT_TAG).assertIsEnabled()
    }

    @Test
    fun `S018 RF-1 cerrar el replanteo avisa`() {
        show(state())
        compose.onNodeWithTag(STAKEOUT_CLOSE_TAG).performClick()

        closed shouldBe 1
    }

    @Test
    fun `S018 RF-1 el titulo lleva el nombre del punto`() {
        show(state())

        compose.onNodeWithTag(STAKEOUT_NAME_TAG).assertTextEquals("Replantear 12")
    }
}
