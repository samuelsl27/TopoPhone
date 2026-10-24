package es.topophone.app.ui.stakeout

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECTOR_BLOCKER_TAG
import es.topophone.app.ui.collector.COLLECTOR_CANCEL_TAG
import es.topophone.app.ui.collector.COLLECTOR_MEASURE_TAG
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.app.ui.collector.MeasureBlocker
import es.topophone.app.ui.collector.MeasureProgress
import es.topophone.app.ui.collector.OUT_OF_TOLERANCE_SAVE_TAG
import es.topophone.app.ui.collector.PendingPoint
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutRef
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
import es.topophone.core.survey.LevelState
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
        level: LevelUi? = null,
        levelUnavailable: LevelUnavailable? = null,
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
            level = level,
            levelUnavailable = levelUnavailable,
        ),
    )

    private fun level(dz: Double, state: LevelState, radius: Double = 5.0, tolerance: Double = 0.03) = LevelUi(dz, state, radius, tolerance)

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
    private val calls = mutableListOf<String>()
    private val headings = mutableListOf<HeadingChoice>()
    private val tolerances = mutableListOf<String>()
    private val tolerancesV = mutableListOf<String>()
    private val elevations = mutableListOf<String>()
    private var usedPoint = 0

    private fun show(state: StakeoutUiState, collector: CollectorUiState = CollectorUiState(loaded = true)) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            StakeoutPanel(
                state,
                StakeoutActions(
                    onClose = { closed++ },
                    onPrevious = { previous++ },
                    onNext = { next++ },
                    onMode = { modes += it },
                    onHeading = { headings += it },
                    onToleranceV = { text ->
                        tolerancesV += text
                        text != "mala"
                    },
                    onElevation = { text ->
                        elevations += text
                        text != "mala"
                    },
                    onUseDesignElevation = { usedPoint++ },
                    onTolerance = { text ->
                        tolerances += text
                        text != "mala"
                    },
                ),
                collector = collector,
                collectorActions = CollectorActions(
                    onMeasure = { calls += "measure" },
                    onCancel = { calls += "cancel" },
                    onConfirm = { calls += "confirm" },
                    onDiscard = { calls += "discard" },
                    onFinish = { calls += "finish" },
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
    fun `S019 RF-6 con la punta por encima de la cota dice desmonte con el valor sin signo`() {
        show(state(on(guidance(dHeight = 3.25), level = level(3.25, LevelState.CUT))))

        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertTextEquals("Desmonte 3,250 m")
    }

    @Test
    fun `S019 RF-6 con la punta por debajo de la cota dice terraplen con el valor sin signo`() {
        show(state(on(guidance(dHeight = -0.04), level = level(-0.04, LevelState.FILL, radius = 0.05))))

        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertTextEquals("Terraplén 0,040 m")
    }

    @Test
    fun `S019 RF-6 con una diferencia de decenas de metros se lee igual`() {
        show(state(on(level = level(12.5, LevelState.CUT, radius = 20.0))))
        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertTextEquals("Desmonte 12,500 m")
    }

    @Test
    fun `S019 RF-6 la cifra va en la unidad del proyecto`() {
        val feet = state(on(level = level(3.25, LevelState.CUT))).copy(project = project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT))

        show(feet)

        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertTextContains("Desmonte 10,663", substring = true)
    }

    @Test
    fun `S019 RF-7 el nivel vertical se dibuja a cualquier distancia, tambien sin diana`() {
        show(state(on(zone = StakeZone.FAR, level = level(2.0, LevelState.CUT))))

        compose.onNodeWithTag(STAKEOUT_LEVEL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_DIANA_TAG).assertDoesNotExist()
    }

    @Test
    fun `S019 RF-7 junto a la diana, en la zona de ayuda, tambien esta el nivel`() {
        show(
            state(
                on(
                    guidance(distance = 0.3, dEast = 0.1, dNorth = 0.28),
                    zone = StakeZone.AID,
                    level = level(0.1, LevelState.CUT, radius = 0.2),
                ),
            ),
        )

        compose.onNodeWithTag(STAKEOUT_DIANA_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_LEVEL_TAG).assertIsDisplayed()
    }

    @Test
    fun `S019 RF-7 el nivel lleva una descripcion de texto con el desmonte o el terraplen`() {
        show(state(on(level = level(3.25, LevelState.CUT))))
        compose.onNodeWithTag(STAKEOUT_LEVEL_TAG).assert(hasContentDescription("Nivel vertical: desmonte de 3,250 m, hay que excavar"))
    }

    @Test
    fun `S019 RF-7 en terraplen la descripcion dice que hay que rellenar`() {
        show(state(on(level = level(-1.2, LevelState.FILL))))
        compose.onNodeWithTag(STAKEOUT_LEVEL_TAG).assert(hasContentDescription("Nivel vertical: terraplén de 1,200 m, hay que rellenar"))
    }

    @Test
    fun `S019 RF-8 dentro de la tolerancia vertical dice En cota con texto, no solo con color`() {
        show(state(on(level = level(0.01, LevelState.ON_GRADE, radius = 0.05))))

        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertTextEquals("En cota")
        compose.onNodeWithTag(STAKEOUT_LEVEL_TAG).assert(hasContentDescription("Nivel vertical: en cota"))
    }

    @Test
    fun `S019 RF-5 sin altura en el punto de diseno ni cota escrita dice que falta la cota y no hay nivel`() {
        show(state(on(guidance(dHeight = null), levelUnavailable = LevelUnavailable.NO_DESIGN_ELEVATION)))

        compose.onNodeWithTag(STAKEOUT_LEVEL_UNAVAILABLE_TAG).assertTextContains("Sin cota de diseño", substring = true)
        compose.onNodeWithTag(STAKEOUT_LEVEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S019 RF-5 con la punta sin altura dice que falta la altura de la punta`() {
        show(state(on(guidance(dHeight = null), levelUnavailable = LevelUnavailable.TIP_WITHOUT_HEIGHT)))

        compose.onNodeWithTag(STAKEOUT_LEVEL_UNAVAILABLE_TAG).assertTextContains("punta no tiene altura", substring = true)
        compose.onNodeWithTag(STAKEOUT_LEVEL_TAG).assertDoesNotExist()
    }

    @Test
    fun `S019 RF-5 con nivel no hay aviso de que falte nada`() {
        show(state(on(level = level(0.5, LevelState.CUT))))

        compose.onNodeWithTag(STAKEOUT_LEVEL_UNAVAILABLE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S019 RF-11 con la guia apagada no hay nivel y se dice el motivo de la guia`() {
        show(state(GuidanceState.Off(GuidanceOff.NO_FIX)))

        compose.onNodeWithTag(STAKEOUT_OFF_TAG).assertTextContains("Sin FIX", substring = true)
        compose.onNodeWithTag(STAKEOUT_LEVEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_LEVEL_UNAVAILABLE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S019 RF-12 la diana sigue dentro de la tolerancia con el texto aunque el nivel diga desmonte`() {
        show(
            state(
                on(
                    guidance(distance = 0.01, dEast = 0.0, dNorth = 0.01),
                    zone = StakeZone.INSIDE,
                    level = level(2.0, LevelState.CUT, radius = 5.0),
                ),
            ),
        )

        compose.onNodeWithTag(STAKEOUT_INSIDE_TAG).assertTextContains("Dentro de la tolerancia", substring = true)
        compose.onNodeWithTag(STAKEOUT_LEVEL_TEXT_TAG).assertTextEquals("Desmonte 2,000 m")
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

    @Test
    fun `S018 RF-15 lejos no hay diana y en la zona de ayuda y dentro si`() {
        show(state(on(zone = StakeZone.FAR)))
        compose.onNodeWithTag(STAKEOUT_DIANA_TAG).assertDoesNotExist()
    }

    @Test
    fun `S018 RF-15 en la zona de ayuda hay diana con su descripcion y aun no dice dentro de la tolerancia`() {
        show(state(on(guidance(distance = 0.1, dEast = 0.1, dNorth = 0.0, ahead = 0.1, right = 0.0), zone = StakeZone.AID)))

        compose.onNodeWithTag(STAKEOUT_DIANA_TAG).assertIsDisplayed()
        val description = "Diana de 20 cm de radio: la punta está a 10 cm del punto de diseño."
        compose.onNodeWithTag(STAKEOUT_DIANA_TAG).assert(hasContentDescription(description))
        compose.onNodeWithTag(STAKEOUT_INSIDE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S018 RF-19 dentro de la tolerancia hay diana y el texto Dentro de la tolerancia`() {
        show(state(on(guidance(distance = 0.01, dEast = 0.01, dNorth = 0.0, ahead = 0.01, right = 0.0), zone = StakeZone.INSIDE)))

        compose.onNodeWithTag(STAKEOUT_DIANA_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_INSIDE_TAG).assertTextEquals("Dentro de la tolerancia")
    }

    @Test
    fun `S018 RF-7 por direccion con rumbo hay flecha con su descripcion para TalkBack`() {
        show(state(on(guidance(distance = 5.0, ahead = 4.0, right = 3.0)), StakeMode.BY_DIRECTION))

        compose.onNodeWithTag(STAKEOUT_ARROW_TAG).assertIsDisplayed()
        compose.onNodeWithTag(STAKEOUT_ARROW_TAG).assert(hasContentDescription("Flecha hacia el punto de diseño: 37° a la derecha"))
    }

    @Test
    fun `S018 RF-7 la flecha dice de frente y detras cuando toca`() {
        show(state(on(guidance(distance = 5.0, ahead = 5.0, right = 0.2)), StakeMode.BY_DIRECTION))
        compose.onNodeWithTag(STAKEOUT_ARROW_TAG).assert(hasContentDescription("Flecha hacia el punto de diseño: de frente"))
    }

    @Test
    fun `S018 RF-7 sin rumbo o por distancia no hay flecha`() {
        val noHeading = on(guidance(ahead = null, right = null), HeadingResult.Unavailable(HeadingUnavailable.NOT_MOVING))
        show(state(noHeading, StakeMode.BY_DIRECTION))
        compose.onNodeWithTag(STAKEOUT_ARROW_TAG).assertDoesNotExist()
    }

    @Test
    fun `S018 RF-7 por distancia no hay flecha aunque haya rumbo`() {
        show(state(on(guidance(ahead = 4.0, right = 3.0)), StakeMode.BY_DISTANCE))
        compose.onNodeWithTag(STAKEOUT_ARROW_TAG).assertDoesNotExist()
    }

    private val savedStake = StakeoutRef(
        designId = "d1",
        designName = "12",
        design = target.coordinates,
        dEastM = -0.02,
        dNorthM = 0.03,
        dHorizontalM = 0.0361,
        dHeightM = -0.005,
    )

    @Test
    fun `S018 RF-26 con la guia encendida el boton Medir mide`() {
        show(state())

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsEnabled().performClick()

        calls shouldBe listOf("measure")
    }

    @Test
    fun `S018 RF-31 sin poder medir por el recolector el boton Medir esta desactivado y dice por que`() {
        show(state(), CollectorUiState(loaded = true, blocker = MeasureBlocker.OUTSIDE_CRS_AREA))

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COLLECTOR_BLOCKER_TAG).assertTextContains("ámbito", substring = true)
    }

    @Test
    fun `S018 RF-35 sin posicion no se puede medir`() {
        show(state(GuidanceState.Off(GuidanceOff.NO_POSITION)), CollectorUiState(loaded = true, blocker = MeasureBlocker.NO_POSITION))

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S018 RF-30 midiendo se ve el progreso con Cancelar y no se cambia de punto ni de modo`() {
        val measuring = state().copy(measuring = true)
        show(measuring, CollectorUiState(loaded = true, measuring = true, progress = MeasureProgress(3.0, 10.0, 15, 1)))

        compose.onNodeWithTag(COLLECTOR_CANCEL_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(STAKEOUT_NEXT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(STAKEOUT_PREVIOUS_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(stakeModeTag(StakeMode.BY_DIRECTION)).assertIsNotEnabled()
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertDoesNotExist()

        calls shouldBe listOf("cancel")
    }

    @Test
    fun `S018 RF-29 tras guardar dice el punto y las diferencias respecto al diseno`() {
        show(state(), CollectorUiState(loaded = true, savedName = "12_stkd", savedStake = savedStake))

        compose.onNodeWithTag(STAKEOUT_RESULT_TAG).assertTextContains("12_stkd", substring = true)
        compose.onNodeWithTag(STAKEOUT_RESULT_TAG).assertTextContains("Norte +0,030", substring = true)
        compose.onNodeWithTag(STAKEOUT_RESULT_TAG).assertTextContains("Este -0,020", substring = true)
        compose.onNodeWithTag(STAKEOUT_RESULT_TAG).assertTextContains("cota -0,005", substring = true)
    }

    private val checkedStake = savedStake.copy(
        designElevationM = 648.19,
        toleranceHM = 0.05,
        toleranceVM = 0.03,
        withinTolerance = true,
    )

    private fun saved(ref: StakeoutRef) = CollectorUiState(loaded = true, savedName = "12_stkd", savedStake = ref)

    @Test
    fun `S019 RF-15 tras guardar dice la cota de diseno usada y que esta en cota`() {
        show(state(), saved(checkedStake))

        compose.onNodeWithTag(STAKEOUT_RESULT_LEVEL_TAG).assertTextContains("Cota de diseño 648,190 m", substring = true)
        compose.onNodeWithTag(STAKEOUT_RESULT_LEVEL_TAG).assertTextContains("En cota", substring = true)
    }

    @Test
    fun `S019 RF-15 tras guardar en desmonte o terraplen dice cuanto`() {
        show(state(), saved(checkedStake.copy(dHeightM = 0.45)))
        compose.onNodeWithTag(STAKEOUT_RESULT_LEVEL_TAG).assertTextContains("Desmonte 0,450 m", substring = true)
    }

    @Test
    fun `S019 RF-15 tras guardar en terraplen dice cuanto`() {
        show(state(), saved(checkedStake.copy(dHeightM = -1.25)))
        compose.onNodeWithTag(STAKEOUT_RESULT_LEVEL_TAG).assertTextContains("Terraplén 1,250 m", substring = true)
    }

    @Test
    fun `S019 RF-15 tras guardar dice si esta dentro o fuera de la tolerancia con texto`() {
        show(state(), saved(checkedStake))
        compose.onNodeWithTag(STAKEOUT_RESULT_WITHIN_TAG).assertTextEquals("Dentro de la tolerancia")
    }

    @Test
    fun `S019 RF-15 fuera de la tolerancia lo dice con texto`() {
        show(state(), saved(checkedStake.copy(withinTolerance = false)))
        compose.onNodeWithTag(STAKEOUT_RESULT_WITHIN_TAG).assertTextEquals("Fuera de la tolerancia")
    }

    @Test
    fun `S019 RF-26 un replanteo sin tolerancias guardadas dice sin evaluar`() {
        show(state(), saved(checkedStake.copy(toleranceHM = null, toleranceVM = null, withinTolerance = null)))
        compose.onNodeWithTag(STAKEOUT_RESULT_WITHIN_TAG).assertTextEquals("Sin evaluar")
    }

    @Test
    fun `S019 RF-15 sin cota de diseno no hay linea de cota pero si el veredicto`() {
        show(state(), saved(checkedStake.copy(designElevationM = null, dHeightM = null)))

        compose.onNodeWithTag(STAKEOUT_RESULT_LEVEL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_RESULT_WITHIN_TAG).assertTextEquals("Dentro de la tolerancia")
    }

    @Test
    fun `S018 RF-29 un resultado sin cota no la dice`() {
        show(state(), CollectorUiState(loaded = true, savedName = "12_stkd", savedStake = savedStake.copy(dHeightM = null)))

        compose.onNodeWithTag(STAKEOUT_RESULT_TAG).assert(hasText("cota", substring = true).not())
    }

    @Test
    fun `S018 RF-26 una medida fuera de tolerancia pide confirmar como en el recolector`() {
        show(state(), CollectorUiState(loaded = true, pending = PendingPoint(setOf(PrecisionFailure.HORIZONTAL))))

        compose.onNodeWithTag(OUT_OF_TOLERANCE_SAVE_TAG).performClick()

        calls shouldBe listOf("confirm")
    }

    @Test
    fun `S018 RF-8 y RF-18 los ajustes abren un dialogo con el origen del rumbo y la tolerancia`() {
        show(state().copy(settings = StakeoutSettings(toleranceM = 0.04)))

        compose.onNodeWithTag(STAKEOUT_SETTINGS_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_TOLERANCE_TAG).assertTextContains("0,040", substring = true)
        compose.onNodeWithTag(stakeHeadingTag(HeadingChoice.IMU)).performClick()
        headings shouldBe listOf(HeadingChoice.IMU)
    }

    @Test
    fun `S018 RF-18 una tolerancia que no vale se avisa con su rango y una que vale se envia`() {
        show(state())
        compose.onNodeWithTag(STAKEOUT_SETTINGS_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_TOLERANCE_TAG).performTextReplacement("mala")
        compose.onNodeWithTag(STAKEOUT_TOLERANCE_ERROR_TAG, useUnmergedTree = true).assertTextContains("0,010", substring = true)
        compose.onNodeWithTag(STAKEOUT_TOLERANCE_TAG).performTextReplacement("0,05")
        compose.onNodeWithTag(STAKEOUT_TOLERANCE_ERROR_TAG, useUnmergedTree = true).assertDoesNotExist()

        tolerances shouldBe listOf("mala", "0,05")
    }

    @Test
    fun `S019 RF-10 los ajustes llevan la tolerancia vertical aparte de la horizontal, con su valor`() {
        show(state().copy(settings = StakeoutSettings(toleranceM = 0.04, toleranceVM = 0.05)))

        compose.onNodeWithTag(STAKEOUT_SETTINGS_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_TOLERANCE_TAG).assertTextContains("0,040", substring = true)
        compose.onNodeWithTag(STAKEOUT_TOLERANCE_V_TAG).assertTextContains("0,050", substring = true)
    }

    @Test
    fun `S019 RF-10 una tolerancia vertical que no vale se avisa con su rango y una que vale se envia`() {
        show(state())
        compose.onNodeWithTag(STAKEOUT_SETTINGS_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_TOLERANCE_V_TAG).performTextReplacement("mala")
        compose.onNodeWithTag(STAKEOUT_TOLERANCE_V_ERROR_TAG, useUnmergedTree = true).assertTextContains("0,010", substring = true)
        compose.onNodeWithTag(STAKEOUT_TOLERANCE_V_TAG).performTextReplacement("0,05")
        compose.onNodeWithTag(STAKEOUT_TOLERANCE_V_ERROR_TAG, useUnmergedTree = true).assertDoesNotExist()

        tolerancesV shouldBe listOf("mala", "0,05")
        tolerances shouldBe emptyList()
    }

    @Test
    fun `S019 RF-10 la tolerancia vertical se muestra en la unidad del proyecto`() {
        val feet = state().copy(project = project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT))
        show(feet)

        compose.onNodeWithTag(STAKEOUT_SETTINGS_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_TOLERANCE_V_TAG).assertTextContains("0,098", substring = true) // 3 cm en pies
    }

    @Test
    fun `S019 RF-2 el boton de la cota de diseno abre un dialogo con la cota de ahora en la unidad del proyecto`() {
        show(state().copy(designElevationM = 648.19))

        compose.onNodeWithTag(STAKEOUT_ELEVATION_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_ELEVATION_FIELD_TAG).assertTextContains("648,190", substring = true)
    }

    @Test
    fun `S019 RF-2 al aplicar una cota valida se envia tal como se escribio y el dialogo se cierra`() {
        show(state().copy(designElevationM = 648.19))
        compose.onNodeWithTag(STAKEOUT_ELEVATION_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_ELEVATION_FIELD_TAG).performTextReplacement("650,25")
        compose.onNodeWithTag(STAKEOUT_ELEVATION_APPLY_TAG).performClick()

        elevations shouldBe listOf("650,25")
        compose.onNodeWithTag(STAKEOUT_ELEVATION_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun `S019 RF-4 una cota que no es un numero se avisa y el dialogo sigue abierto`() {
        show(state().copy(designElevationM = 648.19))
        compose.onNodeWithTag(STAKEOUT_ELEVATION_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_ELEVATION_FIELD_TAG).performTextReplacement("mala")
        compose.onNodeWithTag(STAKEOUT_ELEVATION_APPLY_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_ELEVATION_ERROR_TAG, useUnmergedTree = true).assertTextContains("número", substring = true)
        compose.onNodeWithTag(STAKEOUT_ELEVATION_FIELD_TAG).assertIsDisplayed()
        elevations shouldBe listOf("mala")
    }

    @Test
    fun `S019 RF-3 usar la del punto solo se puede con una cota escrita y la descarta`() {
        show(state().copy(designElevationM = 648.19, elevationOverridden = false))
        compose.onNodeWithTag(STAKEOUT_ELEVATION_TAG).performClick()
        compose.onNodeWithTag(STAKEOUT_ELEVATION_USE_POINT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S019 RF-3 con una cota escrita usar la del punto la descarta y cierra el dialogo`() {
        show(state().copy(designElevationM = 650.25, elevationOverridden = true))
        compose.onNodeWithTag(STAKEOUT_ELEVATION_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_ELEVATION_USE_POINT_TAG).assertIsEnabled().performClick()

        usedPoint shouldBe 1
        compose.onNodeWithTag(STAKEOUT_ELEVATION_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun `S019 RF-2 con una cota escrita el panel lo dice con su valor`() {
        show(state().copy(designElevationM = 650.25, elevationOverridden = true))

        compose.onNodeWithTag(STAKEOUT_ELEVATION_TEXT_TAG).assertTextContains("650,250", substring = true)
        compose.onNodeWithTag(STAKEOUT_ELEVATION_TEXT_TAG).assertTextContains("escrita", substring = true)
    }

    @Test
    fun `S019 RF-2 con la cota del punto el panel no dice que sea escrita`() {
        show(state().copy(designElevationM = 648.19, elevationOverridden = false))

        compose.onNodeWithTag(STAKEOUT_ELEVATION_TEXT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S019 RF-2 sin cota de diseno el dialogo empieza vacio y acepta una`() {
        show(state().copy(designElevationM = null))
        compose.onNodeWithTag(STAKEOUT_ELEVATION_TAG).performClick()

        compose.onNodeWithTag(STAKEOUT_ELEVATION_FIELD_TAG).assert(
            SemanticsMatcher("el texto escrito esta vacio") { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" },
        )
        compose.onNodeWithTag(STAKEOUT_ELEVATION_FIELD_TAG).performTextReplacement("100")
        compose.onNodeWithTag(STAKEOUT_ELEVATION_APPLY_TAG).performClick()

        elevations shouldBe listOf("100")
    }

    @Test
    fun `S019 RF-16 midiendo no se abre el dialogo de la cota de diseno`() {
        show(state().copy(measuring = true), CollectorUiState(loaded = true, measuring = true))

        compose.onNodeWithTag(STAKEOUT_ELEVATION_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S018 RF-30 midiendo no se abren los ajustes`() {
        show(state().copy(measuring = true), CollectorUiState(loaded = true, measuring = true))

        compose.onNodeWithTag(STAKEOUT_SETTINGS_TAG).assertIsNotEnabled()
    }
}
