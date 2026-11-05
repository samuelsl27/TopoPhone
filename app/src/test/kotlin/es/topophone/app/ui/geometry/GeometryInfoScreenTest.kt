package es.topophone.app.ui.geometry

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GeometryCalculator
import es.topophone.core.survey.geometry.GeometryResult
import es.topophone.core.survey.geometry.GeometryVertex
import es.topophone.core.survey.geometry.GroundScale
import es.topophone.core.survey.geometry.SegmentScale
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Totales, selector, avisos y tabla de la información geométrica (S026/RF-1, RF-2, RF-3, RF-6, RF-9, RF-11, RF-12). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GeometryInfoScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun vertex(e: Double, n: Double, h: Double? = null, ell: Double? = null) = GeometryVertex(e, n, h, 40.4, -3.7, ell)

    private val triangle = listOf(vertex(0.0, 0.0, 650.0), vertex(30.0, 0.0, 650.0), vertex(30.0, 40.0, 664.0))

    private fun ui(
        vertices: List<GeometryVertex> = triangle,
        closed: Boolean = false,
        mode: DistanceMode = DistanceMode.GRID,
        linear: LinearUnit = LinearUnit.METRE,
        angular: AngularUnit = AngularUnit.DECIMAL_DEGREES,
        geographic: Boolean = false,
    ): GeometryInfoUi {
        val scale = if (mode == DistanceMode.GROUND) GroundScale(ProjectCrs.ETRS89_UTM30, null) else SegmentScale.Grid
        val info = (GeometryCalculator.of(vertices, closed, scale) as GeometryResult.Info).info
        return GeometryInfoUi(
            loaded = true,
            lineName = "BORD_1",
            closed = closed,
            mode = mode,
            linearUnit = linear,
            angularUnit = angular,
            geographic = geographic,
            info = info,
            names = vertices.indices.map { "P${it + 1}" },
        )
    }

    private fun show(state: GeometryInfoUi, actions: GeometryInfoActions = GeometryInfoActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { GeometryInfoScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    private fun segment(index: Int): SemanticsNodeInteraction {
        compose.onNodeWithTag(GEOMETRY_INFO_TAG).performScrollToNode(hasTestTag(geometrySegmentTag(index)))
        return compose.onNodeWithTag(geometrySegmentTag(index))
    }

    @Test
    fun `S026 RF-1 muestra la longitud 2D, la 3D y el numero de vertices y segmentos`() {
        show(ui())

        compose.onNodeWithTag(GEOMETRY_LENGTH_2D_TAG).assertTextContains("70,000", substring = true)
        compose.onNodeWithTag(GEOMETRY_LENGTH_3D_TAG).assertTextContains("72,379", substring = true)
        compose.onNodeWithTag(GEOMETRY_COUNTS_TAG).assertTextContains("3", substring = true)
        compose.onNodeWithTag(GEOMETRY_PERIMETER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GEOMETRY_AREA_TAG).assertDoesNotExist()
    }

    @Test
    fun `S026 RF-1 un poligono muestra tambien el perimetro y el area`() {
        show(ui(closed = true))

        compose.onNodeWithTag(GEOMETRY_PERIMETER_TAG).assertTextContains("120,000", substring = true)
        compose.onNodeWithTag(GEOMETRY_AREA_TAG).assertTextContains("600,000", substring = true)
        compose.onNodeWithTag(GEOMETRY_AREA_TAG).assertTextContains("m²", substring = true)
    }

    @Test
    fun `S026 RF-12 el area va en pies cuadrados y las longitudes en pies`() {
        show(ui(closed = true, linear = LinearUnit.INTERNATIONAL_FOOT))

        compose.onNodeWithTag(GEOMETRY_AREA_TAG).assertTextContains("ft²", substring = true)
        compose.onNodeWithTag(GEOMETRY_AREA_TAG).assertTextContains("6.458,346", substring = true)
        compose.onNodeWithTag(GEOMETRY_LENGTH_2D_TAG).assertTextContains("393,701", substring = true)
    }

    @Test
    fun `S026 RF-2 la tabla tiene una fila por segmento con vertices, PK, azimut, desnivel y pendiente`() {
        show(ui())

        segment(0).assertIsDisplayed()
        segment(0).assertTextContains("P1", substring = true)
        segment(0).assertTextContains("90,", substring = true)
        segment(1).assertTextContains("14,000", substring = true)
        segment(1).assertTextContains("35,00", substring = true)
        compose.onNodeWithText("Pendiente (%)", substring = true).assertExists()
    }

    @Test
    fun `S026 RF-12 el azimut va en la unidad angular del proyecto`() {
        show(ui(angular = AngularUnit.GON))

        segment(0).assertTextContains("100,", substring = true)
    }

    @Test
    fun `S026 RF-3 sin altura no hay 3D total y se indican los segmentos sin altura`() {
        val noHeight = listOf(vertex(0.0, 0.0, 650.0), vertex(30.0, 0.0, null), vertex(30.0, 40.0, 664.0))
        show(ui(noHeight))

        compose.onNodeWithTag(GEOMETRY_LENGTH_3D_TAG).assertTextContains("—", substring = true)
        compose.onNodeWithTag(GEOMETRY_NOTICE_MISSING_HEIGHT_TAG).assertTextContains("1, 2", substring = true)
        compose.onNodeWithTag(GEOMETRY_LENGTH_2D_TAG).assertTextContains("70,000", substring = true)
        segment(0).assertTextContains("—", substring = true)
    }

    @Test
    fun `S026 RF-3 con todas las alturas no hay aviso de altura`() {
        show(ui())

        compose.onNodeWithTag(GEOMETRY_NOTICE_MISSING_HEIGHT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S026 RF-6 un poligono con cruces avisa y el area es con signo`() {
        val bowtie = listOf(vertex(0.0, 0.0), vertex(100.0, 100.0), vertex(100.0, 0.0), vertex(0.0, 60.0))
        show(ui(bowtie, closed = true))

        compose.onNodeWithTag(GEOMETRY_NOTICE_CROSSES_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GEOMETRY_AREA_TAG).assertTextContains("2.000,000", substring = true)
    }

    @Test
    fun `S026 RF-9 muestra cual esta activa y deja elegir la otra`() {
        var chosen: DistanceMode? = null
        show(ui(), GeometryInfoActions(onMode = { chosen = it }))

        compose.onNodeWithTag(GEOMETRY_ACTIVE_MODE_TAG).assertTextContains("cuadrícula", substring = true, ignoreCase = true)
        compose.onNodeWithTag(GEOMETRY_MODE_GROUND_TAG).performClick()
        chosen shouldBe DistanceMode.GROUND
    }

    @Test
    fun `S026 RF-9 en terreno la activa es terreno`() {
        show(ui(mode = DistanceMode.GROUND))

        compose.onNodeWithTag(GEOMETRY_ACTIVE_MODE_TAG).assertTextContains("terreno", substring = true, ignoreCase = true)
    }

    @Test
    fun `S026 RF-11 con un SC geografico se avisa del plano local`() {
        val geo = listOf(
            GeometryVertex(latitudeDeg = 40.4168, longitudeDeg = -3.7038, ellipsoidalHeightM = 700.0),
            GeometryVertex(latitudeDeg = 40.4182, longitudeDeg = -3.7021, ellipsoidalHeightM = 705.0),
        )
        show(ui(geo, geographic = true))

        compose.onNodeWithTag(GEOMETRY_NOTICE_LOCAL_PLANE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S026 RF-10 sin altura elipsoidal el terreno avisa de que no corrige por altura`() {
        show(ui(mode = DistanceMode.GROUND))

        compose.onNodeWithTag(GEOMETRY_NOTICE_FACTOR_NO_HEIGHT_TAG).assertIsDisplayed()
    }

    @Test
    fun `S026 RF-7 con menos de dos vertices se dice por que no hay informacion`() {
        show(GeometryInfoUi(loaded = true, lineName = "L", problem = GeometryProblem.TOO_FEW_VERTICES))

        compose.onNodeWithTag(GEOMETRY_PROBLEM_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GEOMETRY_LENGTH_2D_TAG).assertDoesNotExist()
    }

    @Test
    fun `S026 RF-2 una linea larga se recorre con la tabla perezosa`() {
        val long = (0..200).map { vertex(it * 10.0, 0.0, 600.0 + it) }
        show(ui(long))

        segment(199).assertIsDisplayed()
    }
}
