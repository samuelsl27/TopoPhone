package es.topophone.app.ui.points

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.Point
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El tiempo promediado, las desviaciones típicas y las frases por tramos de un punto, en su ficha (S017/RF-12, RF-13). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointDetailsAveragedTest {

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

    private fun averaged(auto: Boolean? = false, time: Double? = 720.0, h: Double? = 0.0042, v: Double? = 0.0068, chunks: Int = 0): Point =
        PointFixtures.measured("12").let { point ->
            val measurement = checkNotNull(point.measurement)
            point.copy(
                measurement = measurement.copy(
                    quality = measurement.quality.copy(averagedTimeS = time, deviationHorizontalM = h, deviationVerticalM = v, auto = auto),
                    rawNmea = if (chunks > 0) "" else measurement.rawNmea,
                    nmeaChunks = chunks,
                ),
            )
        }

    private fun SemanticsNodeInteraction.shows(text: String): SemanticsNodeInteraction =
        assert(hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)))

    private fun show(point: Point, loadChunk: suspend (Int) -> String? = { null }) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            PointDetailsScreen(ThemeState.DEFAULT, {
            }, PointDetailsUiState(project, point), PointDetailsActions(), {}, loadNmeaChunk = loadChunk)
        }
    }

    private fun row(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    @Test
    fun `S017 RF-13 la ficha de un punto promediado tiene su tiempo y sus desviaciones tipicas en la unidad del proyecto`() {
        show(averaged())

        row("point_averaged_time").shows("12:00")
        row("point_deviation_h").shows("0,004 m")
        row("point_deviation_v").shows("0,007 m")
    }

    @Test
    fun `S017 RF-14 un punto medido por el modo automatico lo dice y uno a mano no`() {
        show(averaged(auto = true))
        row("point_auto").shows("Sí")
    }

    @Test
    fun `S017 RF-14 un punto a mano no dice nada del modo automatico`() {
        show(averaged(auto = false))

        compose.onNodeWithTag("point_auto").assertDoesNotExist()
    }

    @Test
    fun `S017 RF-13 un punto de antes de S017 o instantaneo no muestra el promediado`() {
        show(averaged(auto = null, time = null, h = null, v = null))

        compose.onNodeWithTag("point_averaged_time").assertDoesNotExist()
        compose.onNodeWithTag("point_deviation_h").assertDoesNotExist()
        compose.onNodeWithTag("point_deviation_v").assertDoesNotExist()
        compose.onNodeWithTag("point_auto").assertDoesNotExist()
    }

    @Test
    fun `S017 RF-13 con menos de dos epocas las desviaciones salen sin dato`() {
        show(averaged(h = null, v = null))

        row("point_averaged_time").shows("12:00")
        row("point_deviation_h").shows("—")
        row("point_deviation_v").shows("—")
    }

    @Test
    fun `S017 RF-12 un punto con frases en varios tramos las muestra de una en una con Anterior y Siguiente`() {
        show(averaged(chunks = 3)) { seq -> "\$GNGGA,tramo$seq,1*00\n\$GNGST,tramo$seq,2*00" }

        row(POINT_NMEA_TAG).performClick()

        compose.onNodeWithTag(NMEA_CHUNK_TITLE_TAG).assert(hasText("Frases NMEA · tramo 1 de 3"))
        compose.onNodeWithTag(NMEA_DIALOG_TAG).assert(hasText("tramo0", substring = true))
        compose.onNodeWithTag(NMEA_PREVIOUS_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(NMEA_NEXT_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(NMEA_CHUNK_TITLE_TAG).assert(hasText("Frases NMEA · tramo 2 de 3"))
        compose.onNodeWithTag(NMEA_DIALOG_TAG).assert(hasText("tramo1", substring = true))
        compose.onNodeWithTag(NMEA_NEXT_TAG).performClick()
        compose.onNodeWithTag(NMEA_NEXT_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(NMEA_PREVIOUS_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(NMEA_CHUNK_TITLE_TAG).assert(hasText("Frases NMEA · tramo 2 de 3"))
    }

    @Test
    fun `S017 RF-12 un tramo grande se muestra por lineas sin cargarlo entero en un solo texto`() {
        val chunk = (0 until 3_000).joinToString("\n") {
            "\$GNGGA,$it,4025.0080000,N,00342.2280000,W,4,28,0.5,650.123,M,51.234,M,1.0,0001*41"
        }
        show(averaged(chunks = 2)) { chunk }

        row(POINT_NMEA_TAG).performClick()

        compose.onNodeWithText("\$GNGGA,0,4025.0080000", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S017 RF-12 un punto con las frases en su fila las muestra como siempre y sin tramos`() {
        show(averaged(chunks = 0))

        row(POINT_NMEA_TAG).performClick()

        compose.onNodeWithTag(NMEA_CHUNK_TITLE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(NMEA_DIALOG_TAG).assertIsDisplayed()
    }
}
