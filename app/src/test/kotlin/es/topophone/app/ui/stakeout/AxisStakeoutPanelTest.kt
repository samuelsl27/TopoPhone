package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECTOR_MEASURE_TAG
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.AcrossMove
import es.topophone.core.survey.AxisChainageSeries
import es.topophone.core.survey.AxisGuidanceValue
import es.topophone.core.survey.AxisInputError
import es.topophone.core.survey.AxisPoint
import es.topophone.core.survey.AxisStakeResult
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.StakeZone
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El panel del replanteo por PK con estados ya hechos: los campos de partida con el PK inicial y final del eje, los errores, el punto de
 * diseño con su cota, la serie de PK, lo que hay que moverse a lo largo y a través del eje, la punta sin proyección, el cambio de eje
 * y las etiquetas de TalkBack (S054/RF-1, RF-3, RF-4, RF-5, RF-6, RF-7, RF-8, RF-9, RF-11; RNF-T7, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class AxisStakeoutPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Obra",
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

    private fun target(offset: Double = 3.5, elevation: Double? = 102.68) = AxisStakeResult.Target(
        chainageM = 150.0,
        offsetM = offset,
        thicknessM = 0.1,
        point = AxisPoint(150.0, offset, 440131.74, 4474071.44, 440130.14, 4474074.56, 1.0971, 0.0),
        profileElevationM = elevation?.minus(0.1),
        elevationM = elevation,
    )

    private fun on(zone: StakeZone = StakeZone.FAR, distance: Double = 1.3): GuidanceState.On = GuidanceState.On(
        GuidanceUi(
            guidance = Guidance(distance, 0.0, distance, 0.0, distance, 0.0, null),
            zone = zone,
            heading = HeadingResult.Available(0.0),
            tipEastM = 0.0,
            tipNorthM = -distance,
            dianaRadiusM = 0.2,
            solution = SolutionStatus.FIX,
            horizontalAccuracyM = 0.012,
            tipUncompensated = false,
        ),
    )

    private fun setup(errors: Map<AxisField, AxisInputError> = emptyMap(), measuring: Boolean = false) = AxisStakeoutUiState(
        project = project,
        loaded = true,
        alignmentId = "a1",
        alignmentName = "Eje A",
        startChainageM = 0.0,
        endChainageM = 420.0,
        hasProfile = true,
        startText = "150",
        offsetText = "0",
        errors = errors,
        measuring = measuring,
    )

    private fun started(
        target: AxisStakeResult.Target? = target(),
        series: AxisChainageSeries? = AxisChainageSeries(150.0, null, null, null, 420.0),
        seriesIndex: Int = 0,
        guidance: GuidanceState = on(),
        axis: AxisGuidanceValue? = AxisGuidanceValue(1.25, 0.4, AcrossMove.INWARD),
        noProjection: Boolean = false,
        hasProfile: Boolean = true,
        choices: List<AxisChoice> = emptyList(),
        measuring: Boolean = false,
        errors: Map<AxisField, AxisInputError> = emptyMap(),
    ) = setup(errors, measuring).copy(
        started = true,
        chainageText = "0+150,00",
        offsetText = "3,5",
        thicknessText = "0,1",
        series = series,
        seriesIndex = seriesIndex,
        target = target,
        guidance = guidance,
        axisGuidance = axis,
        noProjection = noProjection,
        hasProfile = hasProfile,
        choices = choices,
        settings = StakeoutSettings(mode = StakeMode.BY_DIRECTION),
    )

    private val calls = mutableListOf<String>()

    private fun show(state: AxisStakeoutUiState, collector: CollectorUiState? = CollectorUiState(loaded = true)) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            AxisStakeoutPanel(
                state,
                AxisStakeoutActions(
                    onClose = { calls += "close" },
                    onStart = { calls += "start" },
                    onAlignment = { calls += "alignment:$it" },
                    onStartText = { calls += "start:$it" },
                    onEndText = { calls += "end:$it" },
                    onIntervalText = { calls += "interval:$it" },
                    onSingular = { calls += "singular:$it" },
                    onChainage = { calls += "chainage:$it" },
                    onOffset = { calls += "offset:$it" },
                    onThickness = { calls += "thickness:$it" },
                    onPrevious = { calls += "previous" },
                    onNext = { calls += "next" },
                ),
                collector = collector,
                collectorActions = CollectorActions(onMeasure = { calls += "measure" }),
            )
        }
    }

    @Test
    fun `S054 RF-1 antes de abrir se ven los campos de partida y el PK inicial y final del eje`() {
        show(setup())

        compose.onNodeWithTag(AXIS_TITLE_TAG).assertTextContains("Eje A", substring = true)
        compose.onNodeWithTag(AXIS_LIMITS_TAG).assertTextContains("0+000,00", substring = true)
        compose.onNodeWithTag(AXIS_LIMITS_TAG).assertTextContains("0+420,00", substring = true)
        listOf(AXIS_START_TAG, AXIS_END_TAG, AXIS_INTERVAL_TAG, AXIS_SINGULAR_TAG, AXIS_OFFSET_TAG, AXIS_THICKNESS_TAG, AXIS_OPEN_TAG)
            .forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        compose.onAllNodesWithTag(AXIS_DESIGN_TAG).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-1 escribir en los campos y abrir llega a las acciones`() {
        show(setup())

        compose.onNodeWithTag(AXIS_START_TAG).performTextReplacement("200")
        compose.onNodeWithTag(AXIS_END_TAG).performTextReplacement("300")
        compose.onNodeWithTag(AXIS_INTERVAL_TAG).performTextReplacement("20")
        compose.onNodeWithTag(AXIS_SINGULAR_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(AXIS_OFFSET_TAG).performScrollTo().performTextReplacement("-3")
        compose.onNodeWithTag(AXIS_THICKNESS_TAG).performScrollTo().performTextReplacement("0,1")
        compose.onNodeWithTag(AXIS_OPEN_TAG).performScrollTo().performClick()

        calls shouldBe listOf("start:200", "end:300", "interval:20", "singular:true", "offset:-3", "thickness:0,1", "start")
    }

    @Test
    fun `S054 RF-9 un PK fuera del eje dice el PK inicial y el final y un numero malo dice que no lo es`() {
        show(setup(errors = mapOf(AxisField.START to AxisInputError.OUT_OF_AXIS, AxisField.OFFSET to AxisInputError.NOT_A_NUMBER)))

        compose.onNodeWithTag(axisErrorTag(AxisField.START), useUnmergedTree = true).performScrollTo()
            .assertTextContains("Fuera del eje: PK inicial 0+000,00, PK final 0+420,00.")
        compose.onNodeWithTag(
            axisErrorTag(AxisField.OFFSET),
            useUnmergedTree = true,
        ).performScrollTo().assertTextContains("No es un número.")
    }

    @Test
    fun `S054 RF-9 un intervalo no positivo dice que tiene que ser mayor que 0`() {
        show(setup(errors = mapOf(AxisField.INTERVAL to AxisInputError.INTERVAL_NOT_POSITIVE)))

        compose.onNodeWithTag(
            axisErrorTag(AxisField.INTERVAL),
            useUnmergedTree = true,
        ).performScrollTo().assertTextContains("mayor que 0", substring = true)
    }

    @Test
    fun `S054 RF-1 y RF-7 abierto muestra el PK, el lado con su palabra y la cota de diseno`() {
        show(started())

        compose.onNodeWithTag(AXIS_DESIGN_TAG).performScrollTo().assertTextContains("PK 0+150,00", substring = true)
        compose.onNodeWithTag(AXIS_DESIGN_TAG).assertTextContains("a 3,500 m a la derecha del eje", substring = true)
        compose.onNodeWithTag(AXIS_ELEVATION_TAG).performScrollTo().assertTextContains("102,680", substring = true)
        compose.onAllNodesWithTag(AXIS_NO_PROFILE_TAG).assertCountEquals(0)
    }

    @Test
    fun `S054 RNF-T8 a la izquierda y sobre el eje tambien se dicen con palabras`() {
        show(started(target = target(offset = -2.0)))
        compose.onNodeWithTag(AXIS_DESIGN_TAG).performScrollTo().assertTextContains("a 2,000 m a la izquierda del eje", substring = true)
    }

    @Test
    fun `S054 RF-7 un eje sin rasante dice que solo se replantea la planta y no muestra cota`() {
        show(started(target = target(elevation = null), hasProfile = false))

        compose.onNodeWithTag(AXIS_NO_PROFILE_TAG).performScrollTo().assertTextContains("solo la planta", substring = true)
        compose.onAllNodesWithTag(AXIS_ELEVATION_TAG).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-5 el PK el desplazamiento y el espesor se cambian a mano`() {
        show(started())

        compose.onNodeWithTag(AXIS_PK_TAG).performScrollTo().performTextReplacement("0+200,5")
        compose.onNodeWithTag(AXIS_OFFSET_TAG).performScrollTo().performTextReplacement("-4")
        compose.onNodeWithTag(AXIS_THICKNESS_TAG).performScrollTo().performTextReplacement("0,3")

        calls shouldBe listOf("chainage:0+200,5", "offset:-4", "thickness:0,3")
    }

    @Test
    fun `S054 RF-3 con una serie se ve en que punto se esta y se pasa al siguiente y al anterior`() {
        show(started(series = AxisChainageSeries(0.0, 300.0, 100.0, null, 420.0), seriesIndex = 1))

        compose.onNodeWithTag(AXIS_POSITION_TAG).performScrollTo().assertTextContains("Punto 2 de 4")
        compose.onNodeWithTag(AXIS_PREVIOUS_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag(AXIS_NEXT_TAG).performScrollTo().assertIsEnabled().performClick()

        calls shouldBe listOf("previous", "next")
    }

    @Test
    fun `S054 RF-3 en los extremos de la serie no se puede pasar mas alla`() {
        show(started(series = AxisChainageSeries(0.0, 300.0, 100.0, null, 420.0), seriesIndex = 0))
        compose.onNodeWithTag(AXIS_PREVIOUS_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S054 RF-2 sin serie no hay botones de siguiente ni anterior`() {
        show(started())

        compose.onAllNodesWithTag(AXIS_NEXT_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(AXIS_POSITION_TAG).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-6 la guia dice adelante o atras en PK y hacia dentro o fuera del eje con la distancia`() {
        show(started(axis = AxisGuidanceValue(1.25, 0.4, AcrossMove.INWARD)))

        compose.onNodeWithTag(AXIS_ALONG_TAG, useUnmergedTree = true).performScrollTo().assertTextContains("Adelante en PK: 1,250 m")
        compose.onNodeWithTag(AXIS_ACROSS_TAG, useUnmergedTree = true).assertTextContains("Hacia dentro del eje: 0,400 m")
    }

    @Test
    fun `S054 RF-6 atras y hacia fuera, y a un lado si el diseno esta sobre el eje`() {
        show(started(axis = AxisGuidanceValue(-2.0, -0.75, AcrossMove.OUTWARD)))
        compose.onNodeWithTag(AXIS_ALONG_TAG, useUnmergedTree = true).performScrollTo().assertTextContains("Atrás en PK: 2,000 m")
        compose.onNodeWithTag(AXIS_ACROSS_TAG, useUnmergedTree = true).assertTextContains("Hacia fuera del eje: 0,750 m")
    }

    @Test
    fun `S054 RF-6 con el diseno sobre el eje la guia dice izquierda o derecha`() {
        show(started(target = target(offset = 0.0), axis = AxisGuidanceValue(0.0, 0.5, AcrossMove.RIGHT)))

        compose.onNodeWithTag(AXIS_ALONG_TAG, useUnmergedTree = true).performScrollTo().assertTextContains("En el PK")
        compose.onNodeWithTag(AXIS_ACROSS_TAG, useUnmergedTree = true).assertTextContains("A la derecha: 0,500 m")
    }

    @Test
    fun `S054 RNF-T8 la guia lleva una frase para TalkBack con lo que hay que moverse`() {
        show(started(axis = AxisGuidanceValue(1.25, 0.4, AcrossMove.INWARD), guidance = on(zone = StakeZone.AID)))

        compose.onNodeWithTag(AXIS_FIGURES_TAG).performScrollTo().assertTextContains("Adelante en PK: 1,250 m", substring = true)
        compose.onNodeWithTag(AXIS_ANNOUNCE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S054 RF-8 una punta sin proyeccion lo dice y no muestra lo que hay que moverse a lo largo ni a traves`() {
        show(started(axis = null, noProjection = true))

        compose.onNodeWithTag(
            AXIS_NO_PROJECTION_TAG,
        ).performScrollTo().assertTextContains("antes del inicio o después del final", substring = true)
        compose.onAllNodesWithTag(AXIS_ALONG_TAG, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(AXIS_ACROSS_TAG, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag(STAKEOUT_DISTANCE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S054 RF-9 un PK que no cabe en el eje nuevo dice el PK inicial y el final y no abre la guia`() {
        show(started(target = null, axis = null, errors = mapOf(AxisField.CHAINAGE to AxisInputError.OUT_OF_AXIS)))

        compose.onNodeWithTag(
            AXIS_OUTSIDE_TAG,
        ).performScrollTo().assertTextContains("PK inicial 0+000,00, PK final 0+420,00", substring = true)
        compose.onAllNodesWithTag(AXIS_ALONG_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-11 con varios ejes se elige otro sin salir y el actual aparece elegido`() {
        show(started(choices = listOf(AxisChoice("a1", "Eje A"), AxisChoice("a2", "Eje B"))))

        compose.onNodeWithTag(axisChoiceTag("a1")).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(axisChoiceTag("a2")).performClick()

        calls shouldBe listOf("alignment:a2")
    }

    @Test
    fun `S054 RF-11 con un solo eje no se ofrece cambiar`() {
        show(started(choices = listOf(AxisChoice("a1", "Eje A"))))

        compose.onAllNodesWithTag(axisChoiceTag("a1")).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-11 si se borra el eje el panel dice que se ha cerrado`() {
        show(started().copy(closed = AxisClosedReason.ALIGNMENT_DELETED))

        compose.onNodeWithTag(AXIS_CLOSED_TAG).assertTextContains("Se ha borrado el eje", substring = true)
        compose.onAllNodesWithTag(AXIS_DESIGN_TAG).assertCountEquals(0)
    }

    @Test
    fun `S054 RF-1 abierto se mide con el boton de medir del recolector y cerrar llega a la accion`() {
        show(started())

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(AXIS_CLOSE_TAG).performClick()

        calls shouldBe listOf("measure", "close")
    }

    @Test
    fun `S054 RF-5 con una medida en marcha los campos no se pueden cambiar`() {
        show(started(measuring = true))

        compose.onNodeWithTag(AXIS_PK_TAG).performScrollTo().assertIsNotEnabled()
        compose.onAllNodes(hasTestTag(AXIS_OPEN_TAG)).assertCountEquals(0)
    }
}
