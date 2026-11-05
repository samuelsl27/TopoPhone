package es.topophone.app.ui.geometry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.cad.DESIGN_INFORMATION_TAG
import es.topophone.app.ui.cad.DesignElementSheet
import es.topophone.app.ui.cad.DesignSelection
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GeometryNotice
import es.topophone.data.geometry.GeometryPreferences
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La información de una línea de un dibujo, con y sin cota (S026/RF-1, RF-4). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CadLineInfoTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakePreferences : GeometryPreferences {
        val mode = MutableStateFlow(DistanceMode.GRID)
        override val distanceMode = mode

        override suspend fun setDistanceMode(mode: DistanceMode) {
            this.mode.value = mode
        }
    }

    private val preferences = FakePreferences()
    private val viewModels = mutableListOf<DesignGeometryViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun line(z: DoubleArray?, kind: CadEntityKind = CadEntityKind.POLYLINE, ring: Boolean = false) = CadEntity(
        kind,
        doubleArrayOf(440_000.0, 440_030.0, 440_030.0) + if (ring) doubleArrayOf(440_000.0) else doubleArrayOf(),
        doubleArrayOf(4_474_000.0, 4_474_000.0, 4_474_040.0) + if (ring) doubleArrayOf(4_474_000.0) else doubleArrayOf(),
        z,
        null,
        polygonRing = ring,
    )

    private fun selection(entity: CadEntity, hasElevation: Boolean = true) =
        DesignSelection(CadEntityRef("l1", 1L, 0), entity, "Ejes", "calle.dxf", hasElevation)

    private fun input(selection: DesignSelection, crs: ProjectCrs = ProjectCrs.ETRS89_UTM30) =
        DesignGeometryInput(selection, "Calle", crs, LinearUnit.METRE, AngularUnit.DECIMAL_DEGREES)

    private fun state(input: DesignGeometryInput): GeometryInfoUi {
        val vm = DesignGeometryViewModel(preferences)
        viewModels += vm
        vm.start(input)
        return runBlocking { withTimeout(10_000) { vm.state.first { it.loaded } } }
    }

    @Test
    fun `S026 RF-1 una polilinea con cota da longitudes 2D y 3D`() {
        val ui = state(input(selection(line(doubleArrayOf(650.0, 650.0, 664.0)))))

        ui.planOnly shouldBe false
        ui.info!!.length2D shouldBe (70.0 plusOrMinus 1e-9)
        ui.info!!.length3D!! shouldBe (30.0 + Math.hypot(40.0, 14.0) plusOrMinus 1e-9)
        ui.lineName shouldBe "Ejes"
        ui.names shouldBe listOf("1", "2", "3")
    }

    @Test
    fun `S026 RF-4 un dibujo sin cota da solo las magnitudes en planta`() {
        val ui = state(input(selection(line(null), hasElevation = false)))

        ui.planOnly shouldBe true
        ui.info!!.length2D shouldBe (70.0 plusOrMinus 1e-9)
        ui.info!!.length3D shouldBe null
        ui.info!!.segments.all { it.slopePercent == null && it.deltaHeight == null } shouldBe true
    }

    @Test
    fun `S026 RF-4 si el dibujo no tiene cota se ignora la Z de un elemento`() {
        val ui = state(input(selection(line(doubleArrayOf(1.0, 2.0, 3.0)), hasElevation = false)))

        ui.planOnly shouldBe true
        ui.info!!.length3D shouldBe null
    }

    @Test
    fun `S026 RF-1 un anillo de poligono se cierra sin repetir el primer vertice y da su area`() {
        val ui = state(input(selection(line(null, CadEntityKind.POLYLINE, ring = true), hasElevation = false)))

        ui.closed shouldBe true
        ui.info!!.vertexCount shouldBe 3
        ui.info!!.perimeter!! shouldBe (120.0 plusOrMinus 1e-9)
        ui.info!!.area!! shouldBe (600.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S026 RF-1 en terreno una linea de dibujo usa el factor combinado, con aviso sin altura elipsoidal`() {
        preferences.mode.value = DistanceMode.GROUND

        val ui = state(input(selection(line(null), hasElevation = false)))

        ui.mode shouldBe DistanceMode.GROUND
        ui.info!!.notices shouldBe setOf(GeometryNotice.FACTOR_WITHOUT_HEIGHT)
        (ui.info!!.length2D > 70.0) shouldBe true
    }

    @Test
    fun `S026 RF-11 en un proyecto geografico las coordenadas del dibujo son longitud y latitud`() {
        val entity = CadEntity(
            CadEntityKind.POLYLINE,
            doubleArrayOf(-3.7038, -3.7021),
            doubleArrayOf(40.4168, 40.4182),
            null,
            null,
        )
        val ui = state(input(selection(entity, hasElevation = false), ProjectCrs.ETRS89_GEOGRAPHIC))

        ui.geographic shouldBe true
        ui.info!!.notices shouldBe setOf(GeometryNotice.LOCAL_PLANE)
        (ui.info!!.length2D in 100.0..300.0) shouldBe true
    }

    @Test
    fun `S026 RF-1 la ficha del elemento ofrece Informacion en una linea y no en un punto`() {
        var opened = 0
        val point = CadEntity(CadEntityKind.POINT, doubleArrayOf(440_000.0), doubleArrayOf(4_474_000.0), null, null)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                DesignElementSheet(
                    selection(line(null)),
                    LinearUnit.METRE,
                    "m",
                    geographic = false,
                    onDismiss = {},
                    onInformation = { opened++ },
                )
            }
        }

        compose.onNodeWithTag(DESIGN_INFORMATION_TAG).assertIsDisplayed()
        compose.onNodeWithTag(DESIGN_INFORMATION_TAG).performClick()
        opened shouldBe 1
        point.vertexCount shouldBe 1
    }

    @Test
    fun `S026 RF-1 la ficha de un punto no ofrece Informacion`() {
        val point = CadEntity(CadEntityKind.POINT, doubleArrayOf(440_000.0), doubleArrayOf(4_474_000.0), null, null)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                DesignElementSheet(selection(point), LinearUnit.METRE, "m", geographic = false, onDismiss = {}, onInformation = {})
            }
        }

        compose.onNodeWithTag(DESIGN_INFORMATION_TAG).assertDoesNotExist()
    }

    @Test
    fun `S026 RF-4 el dialogo de un dibujo sin cota no muestra la longitud 3D`() {
        val vm = DesignGeometryViewModel(preferences)
        viewModels += vm
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                DesignGeometryDialog(input(selection(line(null), hasElevation = false)), onDismiss = {}, viewModel = vm)
            }
        }

        compose.onNodeWithTag(GEOMETRY_LENGTH_2D_TAG).assertTextContains("70,000", substring = true)
        compose.onNodeWithTag(GEOMETRY_LENGTH_3D_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GEOMETRY_NOTICE_MISSING_HEIGHT_TAG).assertDoesNotExist()
    }
}
