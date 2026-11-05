package es.topophone.app.ui.geometry

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.linear.PROFILE_EXPORT_FILE_TAG
import es.topophone.app.ui.linear.PROFILE_EXPORT_SAVE_TAG
import es.topophone.app.ui.linear.PROFILE_EXPORT_SHARE_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.geometry.GeometryCalculator
import es.topophone.core.survey.geometry.GeometryResult
import es.topophone.core.survey.geometry.GeometryVertex
import es.topophone.core.survey.geometry.SegmentScale
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exportar la tabla de segmentos a CSV con la distancia activa y las unidades del proyecto (S026/RF-8, RNF-T5, RNF-T9). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GeometryExportTest {

    @get:Rule
    val compose = createComposeRule()

    private fun ui(closed: Boolean = false, linear: LinearUnit = LinearUnit.METRE): GeometryInfoUi {
        val vertices = listOf(
            GeometryVertex(0.0, 0.0, 650.0),
            GeometryVertex(30.0, 0.0, 650.0),
            GeometryVertex(30.0, 40.0, 664.0),
        )
        val info = (GeometryCalculator.of(vertices, closed, SegmentScale.Grid) as GeometryResult.Info).info
        return GeometryInfoUi(
            loaded = true,
            projectName = "Parcela",
            lineName = "BORD_1",
            closed = closed,
            mode = DistanceMode.GRID,
            linearUnit = linear,
            angularUnit = AngularUnit.DECIMAL_DEGREES,
            info = info,
            names = listOf("P1", "P2", "P3"),
        )
    }

    private fun lines(state: GeometryInfoUi): List<String> =
        StringBuilder().also { GeometryExport.write(state, it) }.toString().split("\r\n")

    @Test
    fun `S026 RF-8 el CSV lleva un segmento por fila, la distancia activa y las unidades en la cabecera`() {
        val csv = lines(ui())

        csv[0] shouldBe
            "Segment,From,To,Chainage (m grid),Length 2D (m grid),Length 3D (m grid),Azimuth (deg),Delta height (m),Slope (%)"
        csv[1] shouldBe "1,P1,P2,0.0000,30.0000,30.0000,90.0000000,0.0000,0.0000"
        csv[2] shouldBe "2,P2,P3,30.0000,40.0000,42.3792,0.0000000,14.0000,35.0000"
        csv[3] shouldBe "Total,,,,70.0000,72.3792,,,"
    }

    @Test
    fun `S026 RF-8 un poligono anade el perimetro y el area y las unidades del proyecto`() {
        val csv = lines(ui(closed = true, linear = LinearUnit.INTERNATIONAL_FOOT))

        csv[0].contains("(ft grid)") shouldBe true
        csv.any { it.startsWith("Perimeter,,,,393.7008") } shouldBe true
        csv.any { it.startsWith("Area (ft2),,,,6458.3463") } shouldBe true
    }

    @Test
    fun `S026 RF-8 el punto decimal no depende de la configuracion regional del movil`() {
        val csv = lines(ui())

        csv[2].contains("42.3792") shouldBe true
        csv[2].contains("42,3792") shouldBe false
    }

    private fun show(state: GeometryInfoUi) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { GeometryInfoScreen(ThemeState.DEFAULT, {}, state, GeometryInfoActions(), onBack = {}) }
        }
    }

    @Test
    fun `S026 RF-8 la pantalla ofrece guardar y compartir con el nombre del fichero y zonas de 56 dp`() {
        show(ui())

        compose.onNodeWithTag(GEOMETRY_INFO_TAG).performScrollToNode(hasTestTag(PROFILE_EXPORT_SAVE_TAG))
        compose.onNodeWithTag(PROFILE_EXPORT_SAVE_TAG).performScrollTo().assertIsEnabled().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(PROFILE_EXPORT_SHARE_TAG).assertIsEnabled().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(PROFILE_EXPORT_FILE_TAG).assertTextContains("Parcela_BORD_1_", substring = true)
        compose.onNodeWithTag(PROFILE_EXPORT_FILE_TAG).assertTextContains(".csv", substring = true)
    }

    @Test
    fun `S026 RF-8 sin informacion no se puede exportar`() {
        show(GeometryInfoUi(loaded = true, lineName = "L", projectName = "Parcela", problem = GeometryProblem.TOO_FEW_VERTICES))

        compose.onNodeWithTag(GEOMETRY_INFO_TAG).performScrollToNode(hasTestTag(PROFILE_EXPORT_SAVE_TAG))
        compose.onNodeWithTag(PROFILE_EXPORT_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(PROFILE_EXPORT_SHARE_TAG).assertIsNotEnabled()
    }
}
