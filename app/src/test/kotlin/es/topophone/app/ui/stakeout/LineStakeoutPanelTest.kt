package es.topophone.app.ui.stakeout

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECTOR_MEASURE_TAG
import es.topophone.app.ui.collector.CollectorActions
import es.topophone.app.ui.collector.CollectorUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.point.LineInputError
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.LineLocation
import es.topophone.core.survey.LineObjective
import es.topophone.core.survey.LinePosition
import es.topophone.core.survey.LineRange
import es.topophone.core.survey.LineSeries
import es.topophone.core.survey.LineVertices
import es.topophone.core.survey.PlanePoint
import es.topophone.core.survey.RangePosition
import es.topophone.core.survey.StakeLine
import es.topophone.core.survey.StakeZone
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * El panel del replanteo de una línea con estados ya hechos: la capa, el dibujo y la cota, los cinco modos, los campos con sus
 * errores, las listas, el PK, el lado y lo que falta, antes del inicio y después del final, y las etiquetas de TalkBack
 * (S041/RF-3, RF-4, RF-8, RF-10, RF-11, RF-13, RF-14, RF-16, RF-17, RF-20, RF-21, RF-31, RF-34; RNF-T7, RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class LineStakeoutPanelTest {

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

    private val line = StakeLine.create(doubleArrayOf(0.0, 50.0, 50.0), doubleArrayOf(0.0, 0.0, 40.0), doubleArrayOf(100.0, 104.0, 110.0))!!

    private fun element(minH: Double? = 100.0, maxH: Double? = 110.0) =
        LineElementInfo("l1", "Eje", "calle.dxf", CadEntityKind.POLYLINE, 90.0, closed = false, minHeightM = minH, maxHeightM = maxH)

    private fun objective(
        lateral: Double = 1.2,
        error: Double? = 1.2,
        position: RangePosition = RangePosition.WITHIN,
        chainage: Double = 45.0,
        along: Double = 45.0,
        remaining: Double = 45.0,
    ) = LineObjective(
        point = PlanePoint(chainage, 0.0),
        chainageM = chainage,
        heightM = 103.0,
        location = LineLocation(PlanePoint(chainage, 0.0), chainage, kotlin.math.abs(lateral), lateral, LinePosition.ON_LINE, remaining, 0),
        lateralErrorM = error,
        position = position,
        alongM = along,
        remainingM = remaining,
        offsetM = 0.0,
    )

    private fun on(line: LineObjective? = objective(), zone: StakeZone = StakeZone.FAR, distance: Double = 1.2): GuidanceState.On {
        val guidance = Guidance(distance, 0.0, distance, 0.0, distance, 0.0, null)
        return GuidanceState.On(
            GuidanceUi(
                guidance = guidance,
                zone = zone,
                heading = HeadingResult.Available(0.0),
                tipEastM = 0.0,
                tipNorthM = -distance,
                dianaRadiusM = 0.2,
                solution = SolutionStatus.FIX,
                horizontalAccuracyM = 0.012,
                tipUncompensated = false,
                line = line,
            ),
        )
    }

    private fun state(
        mode: LineStakeoutMode = LineStakeoutMode.TO_LINE,
        guidance: GuidanceState = on(),
        element: LineElementInfo? = element(),
        measuring: Boolean = false,
        errors: Map<LineField, LineInputError> = emptyMap(),
        vertices: LineVertices? = LineVertices.of(line, CadEntityKind.POLYLINE),
        series: LineSeries? = null,
        vertexIndex: Int = 0,
        seriesIndex: Int = 0,
        closed: LineClosedReason? = null,
        unsupported: Boolean = false,
    ) = LineStakeoutUiState(
        project = project,
        element = element,
        unsupported = unsupported,
        mode = mode,
        offsetText = "2,000",
        aText = "0,000",
        bText = "90,000",
        intervalText = "10",
        errors = errors,
        vertices = vertices,
        vertexIndex = vertexIndex,
        series = series,
        seriesIndex = seriesIndex,
        settings = StakeoutSettings(mode = es.topophone.core.model.point.StakeMode.BY_DISTANCE),
        guidance = guidance,
        measuring = measuring,
        closed = closed,
    )

    private val calls = mutableListOf<String>()
    private val modes = mutableListOf<LineStakeoutMode>()
    private val texts = mutableListOf<String>()

    private fun show(state: LineStakeoutUiState, collector: CollectorUiState? = CollectorUiState(loaded = true)) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            LineStakeoutPanel(
                state,
                LineStakeoutActions(
                    onClose = { calls += "close" },
                    onMode = { modes += it },
                    onOffset = { texts += "offset:$it" },
                    onA = { texts += "a:$it" },
                    onB = { texts += "b:$it" },
                    onInterval = { texts += "interval:$it" },
                    onInvert = { calls += "invert" },
                    onHere = { calls += "here" },
                    onSelectVertex = { calls += "vertex:$it" },
                    onSelectPoint = { calls += "point:$it" },
                    onPrevious = { calls += "previous" },
                    onNext = { calls += "next" },
                ),
                collector = collector,
                collectorActions = CollectorActions(onMeasure = { calls += "measure" }),
            )
        }
    }

    @Test
    fun `S041 RF-4 muestra la capa, el dibujo, el tipo, la longitud y la cota de la linea`() {
        show(state())

        compose.onNodeWithTag(LINE_ELEMENT_TAG).assertTextContains("Polilínea", substring = true)
        compose.onNodeWithTag(LINE_ELEMENT_TAG).assertTextContains("Capa: Eje", substring = true)
        compose.onNodeWithTag(LINE_ELEMENT_TAG).assertTextContains("Dibujo: calle.dxf", substring = true)
        compose.onNodeWithTag(LINE_ELEMENT_TAG).assertTextContains("90,000", substring = true)
        compose.onNodeWithTag(LINE_ELEMENT_TAG).assertTextContains("de 100,000 a 110,000", substring = true)
        compose.onNodeWithTag(LINE_TITLE_TAG).assertTextContains("Eje", substring = true)
    }

    @Test
    fun `S041 RF-4 una linea sin cota dice Sin cota`() {
        show(state(element = element(minH = null, maxH = null)))

        compose.onNodeWithTag(LINE_ELEMENT_TAG).assertTextContains("Sin cota", substring = true)
    }

    @Test
    fun `S041 RF-3 ofrece los cinco modos con el elegido marcado y cambiar de modo avisa`() {
        show(state(mode = LineStakeoutMode.ALONG))

        LineStakeoutMode.entries.forEach { compose.onNodeWithTag(lineModeTag(it)).assertIsDisplayed() }
        compose.onNodeWithTag(lineModeTag(LineStakeoutMode.ALONG)).assertIsSelected()
        compose.onNodeWithTag(lineModeTag(LineStakeoutMode.INTERVALS)).performClick()
        modes shouldBe listOf(LineStakeoutMode.INTERVALS)
    }

    @Test
    fun `S041 RF-22 con una medida en marcha no se puede cambiar de modo ni de campos ni de punto`() {
        show(
            state(mode = LineStakeoutMode.INTERVALS, measuring = true, series = LineSeries(line, LineRange(0.0, 90.0), 30.0, 0.0)),
            CollectorUiState(loaded = true, measuring = true),
        )

        LineStakeoutMode.entries.forEach { compose.onNodeWithTag(lineModeTag(it)).assertIsNotEnabled() }
        compose.onNodeWithTag(LINE_OFFSET_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(LINE_A_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(LINE_B_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(LINE_INTERVAL_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(LINE_INVERT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(LINE_HERE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(LINE_NEXT_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(linePointTag(1)).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S041 RF-10 A la linea da el lado con su distancia, el PK y lo que falta, sin campos`() {
        show(state(guidance = on(objective(lateral = 1.2, chainage = 45.0, remaining = 45.0))))

        compose.onNodeWithTag(LINE_LATERAL_TAG, useUnmergedTree = true).assertTextEquals("A 1,200 m a la derecha de la línea")
        compose.onNodeWithTag(LINE_CHAINAGE_TAG, useUnmergedTree = true).assertTextEquals("PK 45,000 m")
        compose.onNodeWithTag(LINE_REMAINING_TAG, useUnmergedTree = true).assertTextEquals("Faltan 45,000 m hasta el final")
        compose.onNodeWithTag(LINE_OFFSET_TAG).assertDoesNotExist()
        compose.onNodeWithTag(LINE_A_TAG).assertDoesNotExist()
    }

    @Test
    fun `S041 RF-10 a la izquierda y sobre la linea lo dice con texto`() {
        show(state(guidance = on(objective(lateral = -0.5, error = -0.5))))
        compose.onNodeWithTag(LINE_LATERAL_TAG, useUnmergedTree = true).assertTextEquals("A 0,500 m a la izquierda de la línea")
    }

    @Test
    fun `S041 RF-10 sobre la linea dice Sobre la linea`() {
        show(state(guidance = on(objective(lateral = 0.0, error = 0.0))))
        compose.onNodeWithTag(LINE_LATERAL_TAG, useUnmergedTree = true).assertTextEquals("Sobre la línea")
    }

    @Test
    fun `S041 RF-11 A lo largo escribe el desplazamiento y muestra el error lateral con su lado`() {
        show(state(mode = LineStakeoutMode.ALONG, guidance = on(objective(lateral = 3.5, error = 1.5))))

        compose.onNodeWithTag(LINE_OFFSET_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(LINE_OFFSET_TAG).performScrollTo().performTextReplacement("-2,5")
        texts shouldBe listOf("offset:-2,5")
        compose.onNodeWithTag(LINE_ERROR_TAG, useUnmergedTree = true).assertTextEquals("Error lateral: 1,500 m a la derecha")
    }

    @Test
    fun `S041 RF-11 un error lateral de 0 o a la izquierda se dice con texto`() {
        show(state(mode = LineStakeoutMode.ALONG, guidance = on(objective(lateral = 2.0, error = 0.0))))
        compose.onNodeWithTag(LINE_ERROR_TAG, useUnmergedTree = true).assertTextEquals("Error lateral: 0")
    }

    @Test
    fun `S041 RF-11 un error lateral a la izquierda lo dice`() {
        show(state(mode = LineStakeoutMode.ALONG, guidance = on(objective(lateral = 0.5, error = -1.5))))
        compose.onNodeWithTag(LINE_ERROR_TAG, useUnmergedTree = true).assertTextEquals("Error lateral: 1,500 m a la izquierda")
    }

    @Test
    fun `S041 RF-13 dentro de la tolerancia dice Dentro de la tolerancia con texto`() {
        show(
            state(
                mode = LineStakeoutMode.ALONG,
                guidance = on(objective(lateral = 2.0, error = 0.01), zone = StakeZone.INSIDE, distance = 0.01),
            ),
        )

        compose.onNodeWithTag(STAKEOUT_INSIDE_TAG).assertTextContains("Dentro de la tolerancia")
    }

    @Test
    fun `S041 RF-14 antes del inicio y despues del final se dice con la distancia al extremo y sin lado`() {
        show(
            state(
                guidance = on(
                    objective(lateral = 0.0, error = null, position = RangePosition.BEFORE, chainage = 0.0, remaining = 90.0),
                    distance = 7.5,
                ),
            ),
        )
        compose.onNodeWithTag(LINE_LATERAL_TAG, useUnmergedTree = true).assertTextEquals("Antes del inicio, a 7,500 m")
        compose.onNodeWithTag(LINE_ERROR_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `S041 RF-14 despues del final`() {
        show(
            state(
                guidance = on(
                    objective(lateral = 0.0, error = null, position = RangePosition.AFTER, chainage = 90.0, remaining = 0.0),
                    distance = 3.0,
                ),
            ),
        )
        compose.onNodeWithTag(LINE_LATERAL_TAG, useUnmergedTree = true).assertTextEquals("Después del final, a 3,000 m")
    }

    @Test
    fun `S041 RF-17 en el Tramo A-B se dan el PK desde A y lo que falta hasta B y se dice antes de A o despues de B`() {
        show(
            state(
                mode = LineStakeoutMode.SEGMENT_AB,
                guidance = on(objective(lateral = 1.0, error = 1.0, chainage = 30.0, along = 10.0, remaining = 20.0)),
            ),
        )
        compose.onNodeWithTag(LINE_REMAINING_TAG, useUnmergedTree = true).assertTextEquals("Desde A: 10,000 m · hasta B: 20,000 m")
    }

    @Test
    fun `S041 RF-17 antes de A y despues de B`() {
        show(
            state(
                mode = LineStakeoutMode.SEGMENT_AB,
                guidance = on(objective(0.0, null, RangePosition.BEFORE, 20.0, 0.0, 40.0), distance = 4.0),
            ),
        )
        compose.onNodeWithTag(LINE_LATERAL_TAG, useUnmergedTree = true).assertTextEquals("Antes de A, a 4,000 m")
    }

    @Test
    fun `S041 RF-17 despues de B`() {
        show(
            state(
                mode = LineStakeoutMode.SEGMENT_AB,
                guidance = on(objective(0.0, null, RangePosition.AFTER, 60.0, 40.0, 0.0), distance = 6.0),
            ),
        )
        compose.onNodeWithTag(LINE_LATERAL_TAG, useUnmergedTree = true).assertTextEquals("Después de B, a 6,000 m")
    }

    @Test
    fun `S041 RF-16 Tramo A-B tiene A, B, Invertir y Mi posicion, y los botones avisan`() {
        show(state(mode = LineStakeoutMode.SEGMENT_AB))

        compose.onNodeWithTag(LINE_A_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(LINE_B_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(LINE_INTERVAL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(LINE_INVERT_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LINE_HERE_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag(LINE_A_TAG).performScrollTo().performTextReplacement("12")
        compose.onNodeWithTag(LINE_B_TAG).performScrollTo().performTextReplacement("80")
        calls shouldBe listOf("invert", "here")
        texts shouldBe listOf("a:12", "b:80")
    }

    @Test
    fun `S041 RF-16 Mi posicion no se puede usar con la guia apagada`() {
        show(state(mode = LineStakeoutMode.SEGMENT_AB, guidance = GuidanceState.Off(GuidanceOff.NO_FIX)))

        compose.onNodeWithTag(LINE_HERE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S041 RF-21 cada error se dice con su motivo debajo del campo`() {
        show(
            state(
                mode = LineStakeoutMode.INTERVALS,
                errors = mapOf(
                    LineField.OFFSET to LineInputError.NOT_A_NUMBER,
                    LineField.A to LineInputError.OUT_OF_LINE,
                    LineField.B to LineInputError.SAME_PK,
                    LineField.INTERVAL to LineInputError.INTERVAL_NOT_POSITIVE,
                ),
            ),
        )

        compose.onNodeWithTag(lineErrorTag(LineField.OFFSET), useUnmergedTree = true).assertTextEquals("No es un número.")
        compose.onNodeWithTag(lineErrorTag(LineField.A), useUnmergedTree = true).assertTextEquals("Está fuera de la línea.")
        compose.onNodeWithTag(lineErrorTag(LineField.B), useUnmergedTree = true).assertTextEquals("A y B no pueden ser el mismo punto.")
        compose.onNodeWithTag(
            lineErrorTag(LineField.INTERVAL),
            useUnmergedTree = true,
        ).assertTextEquals("El intervalo tiene que ser mayor que 0.")
    }

    @Test
    fun `S041 RF-8 Por segmentos lista los vertices con su numero y su PK y se puede elegir cualquiera`() {
        show(state(mode = LineStakeoutMode.BY_SEGMENTS, vertexIndex = 1))

        compose.onNodeWithTag(lineVertexTag(0)).assertTextContains("1 · PK 0,000 m", substring = true)
        compose.onNodeWithTag(lineVertexTag(1)).assertTextContains("2 · PK 50,000 m", substring = true)
        compose.onNodeWithTag(lineVertexTag(2)).assertTextContains("3 · PK 90,000 m", substring = true)
        compose.onNodeWithTag(lineVertexTag(1)).assertIsSelected()
        compose.onNodeWithTag(lineVertexTag(1)).assertTextContains("Elegido", substring = true)
        compose.onNodeWithTag(lineVertexTag(2)).performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag(LINE_NEXT_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LINE_PREVIOUS_TAG).performScrollTo().performClick()
        calls shouldBe listOf("vertex:2", "next", "previous")
    }

    @Test
    fun `S041 RF-34 el aviso de volver a importar solo sale en Por segmentos y con un arco de un dibujo antiguo`() {
        val old = LineVertices.of(
            StakeLine.create(DoubleArray(8) { it * 5.0 }, DoubleArray(8) { if (it % 2 == 0) 0.0 else 1.0 }, null)!!,
            CadEntityKind.ARC,
        )

        show(state(mode = LineStakeoutMode.BY_SEGMENTS, vertices = old))

        compose.onNodeWithTag(LINE_REIMPORT_TAG).assertTextContains("Vuelve a importarlo", substring = true)
    }

    @Test
    fun `S041 RF-34 sin el aviso en otros modos ni con vertices marcados`() {
        show(state(mode = LineStakeoutMode.TO_LINE))

        compose.onNodeWithTag(LINE_REIMPORT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S041 RF-20 Intervalos lista los puntos con su numero y su PK y se puede elegir uno`() {
        val series = LineSeries(line, LineRange(0.0, 90.0), 30.0, 0.0)

        show(state(mode = LineStakeoutMode.INTERVALS, series = series, seriesIndex = 2))

        compose.onNodeWithTag(linePointTag(0)).assertTextContains("1 · PK 0,000 m", substring = true)
        compose.onNodeWithTag(linePointTag(2)).assertIsSelected()
        compose.onNodeWithTag(linePointTag(1)).performSemanticsAction(SemanticsActions.OnClick)
        calls shouldBe listOf("point:1")
    }

    @Test
    fun `S041 RF-20 Intervalos sin un intervalo valido pide escribirlo`() {
        show(state(mode = LineStakeoutMode.INTERVALS, series = null))

        compose.onNodeWithTag(LINE_SERIES_EMPTY_TAG).assertTextContains("Escribe un intervalo", substring = true)
    }

    @Test
    fun `S041 RF-26 con la guia apagada dice por que`() {
        show(state(guidance = GuidanceState.Off(GuidanceOff.NO_FIX)))

        compose.onNodeWithTag(STAKEOUT_OFF_TAG).assertTextContains("FIX", substring = true)
    }

    @Test
    fun `S041 RF-31 con el dibujo borrado el panel lo dice y no deja nada mas`() {
        show(state(closed = LineClosedReason.DRAWING_DELETED))

        compose.onNodeWithTag(LINE_CLOSED_TAG).assertTextContains("dibujo", substring = true)
        compose.onNodeWithTag(LINE_ELEMENT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S041 RF-1 un elemento que no se puede replantear como linea lo dice`() {
        show(state(element = null, unsupported = true))

        compose.onNodeWithTag(LINE_UNSUPPORTED_TAG).assertTextContains("no se puede replantear", substring = true)
    }

    @Test
    fun `S041 RNF-T8 la posicion se lee entera para TalkBack con el lado, el PK y sin repetir cada refresco`() {
        show(state(guidance = on(objective(lateral = 1.2, chainage = 45.0), zone = StakeZone.AID)))

        val description = compose.onNodeWithTag(
            LINE_POSITION_TAG,
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        description shouldBe listOf("A 1,200 m a la derecha de la línea, en el PK 45,000 m")
        // La frase del anuncio es la de la entrada en la zona
        compose.onNodeWithTag(LINE_ANNOUNCE_TAG).assert(hasText("A 1,200 m a la derecha de la línea, en el PK 45,000 m"))
    }

    @Test
    fun `S041 RNF-T8 lejos de la zona de ayuda no se anuncia nada`() {
        show(state(guidance = on(objective(), zone = StakeZone.FAR)))

        compose.onNodeWithTag(LINE_ANNOUNCE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S041 RF-27 Medir y cerrar avisan y el panel no cambia el boton de medir`() {
        show(state())

        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).performClick()
        compose.onNodeWithTag(LINE_CLOSE_TAG).performClick()
        calls shouldBe listOf("measure", "close")
    }
}
