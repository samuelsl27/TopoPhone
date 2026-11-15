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
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * La ficha del punto dice de dónde vino la posición: el GNSS del móvil, el móvil con una ubicación simulada por otra app, el receptor
 * virtual o una reproducción; un receptor de siempre no lleva nada (S022/RF-7, RF-11, RF-19).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointSourceDetailsTest {

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

    private fun measured(source: PositionSource, fromReplay: Boolean = source.simulated): Point = PointFixtures.measured("12").let {
        it.copy(
            measurement = it.measurement!!.copy(
                quality = it.measurement!!.quality.copy(solution = "SINGLE", fromReplay = fromReplay, source = source),
            ),
        )
    }

    private fun show(point: Point) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            PointDetailsScreen(ThemeState.DEFAULT, {}, PointDetailsUiState(project, point), PointDetailsActions(), {})
        }
    }

    private fun SemanticsNodeInteraction.shows(text: String) = assert(
        hasText(text, substring = true) or hasAnyDescendant(hasText(text, substring = true)),
    )

    private fun source() = compose.onNodeWithTag("point_source").performScrollTo()

    @Test
    fun `S022 RF-11 la ficha de un punto del movil dice que se midio con el GNSS del movil`() {
        show(measured(PositionSource.PHONE))

        source().shows("GNSS del móvil")
        compose.onNodeWithTag("point_source").shows("pocos metros")
    }

    @Test
    fun `S022 RF-7 la ficha de un punto con ubicacion simulada lo dice y lo marca como simulado`() {
        show(measured(PositionSource.PHONE_MOCK))

        source().shows("ubicación simulada por otra aplicación")
        compose.onNodeWithTag("point_source").shows("simulado")
    }

    @Test
    fun `S022 RF-19 la ficha de un punto del receptor virtual lo marca como simulado`() {
        show(measured(PositionSource.VIRTUAL))

        source().shows("receptor virtual")
        compose.onNodeWithTag("point_source").shows("simulado")
    }

    @Test
    fun `S022 RF-19 una reproduccion sigue diciendo Medido en una reproduccion`() {
        show(measured(PositionSource.REPLAY))

        source().shows("Medido en una reproducción")
    }

    @Test
    fun `S022 RF-19 un punto de antes de S022 hecho en una reproduccion sigue diciendolo`() {
        show(measured(PositionSource.RECEIVER, fromReplay = true))

        source().shows("Medido en una reproducción")
    }

    @Test
    fun `S022 RF-11 un punto de un receptor de siempre no lleva fuente en su ficha`() {
        show(measured(PositionSource.RECEIVER))

        compose.onNodeWithTag("point_source").assertDoesNotExist()
    }

    @Test
    fun `S022 RF-19 la columna Replay de la exportacion sigue siendo la de simulado con cualquier fuente`() {
        // S022 plan D3: no se cambia ningún formato; «Replay» dice si la posición es simulada.
        for (source in PositionSource.entries) {
            val quality = measured(source).measurement!!.quality
            quality.fromReplay shouldBe source.simulated
        }
        ExportColumn.REPLAY.name shouldBe "REPLAY"
    }
}
