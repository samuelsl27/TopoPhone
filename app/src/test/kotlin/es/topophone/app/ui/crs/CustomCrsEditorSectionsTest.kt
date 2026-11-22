package es.topophone.app.ui.crs

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GridKind
import es.topophone.core.geodesy.catalog.RotationConvention
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CustomHeight
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El editor: la transformación del datum, la proyección y la altura de un SC personalizado (S035/RF-3, RF-4, RF-5, RF-6). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CustomCrsEditorSectionsTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeCustomCrsRepository()
    private lateinit var viewModel: CustomCrsEditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = CustomCrsEditorViewModel(repository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun show() {
        viewModel.open(CustomCrsEditorRequest())
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                val state by viewModel.state.collectAsState()
                CustomCrsEditorScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    CustomCrsEditorActions(onChange = viewModel::update, onStartFrom = viewModel::startFrom, onSave = viewModel::save),
                )
            }
        }
    }

    private fun type(tag: String, text: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextInput(text)
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun basics() {
        type(CUSTOM_NAME_TAG, "Obra")
        type(CUSTOM_SEMI_MAJOR_TAG, "6378388")
        type(CUSTOM_INVERSE_FLATTENING_TAG, "297")
    }

    private fun tm(centralMeridian: String = "-3") {
        tap(CUSTOM_METHOD_TAG)
        tap(customMethodTag("tm"))
        type(customParamTag("latitudeOfOriginDeg"), "0")
        type(customParamTag("longitudeOfOriginDeg"), centralMeridian)
        type(customParamTag("scaleFactor"), "0.9996")
        type(customParamTag("falseEasting"), "500000")
        type(customParamTag("falseNorthing"), "0")
    }

    private fun saved() = runBlocking { repository.all() }.single()

    @Test
    fun `S035 RF-4 la Transversa de Mercator pide sus cinco parametros y se guarda`() {
        show()
        basics()

        tm()
        tap(CUSTOM_SAVE_TAG)

        val d = saved().definition
        d.method shouldBe "tm"
        d.projected shouldBe true
        d.parameters["longitudeOfOriginDeg"] shouldBe -3.0
        d.parameters["scaleFactor"] shouldBe 0.9996
    }

    @Test
    fun `S035 RF-4 cada metodo ofrece sus parametros y un parametro fuera de rango se marca`() {
        show()
        basics()

        tap(CUSTOM_METHOD_TAG)
        tap(customMethodTag("lcc2"))
        compose.onNodeWithTag(customParamTag("standardParallel1Deg")).performScrollTo().assertExists()
        compose.onNodeWithTag(customParamTag("scaleFactor")).assertDoesNotExist()
        type(customParamTag("standardParallel1Deg"), "100")

        compose.onNodeWithText("Fuera de rango").assertExists()

        tap(CUSTOM_METHOD_TAG)
        tap(customMethodTag("omercb"))
        compose.onNodeWithTag(customParamTag("azimuthDeg")).performScrollTo().assertExists()
        compose.onNodeWithTag(customParamTag("eastingAtCentre")).performScrollTo().assertExists()
        compose.onNodeWithTag(customParamTag("falseEasting")).assertDoesNotExist()
    }

    @Test
    fun `S035 RF-3 un Helmert de 3 parametros pide solo las traslaciones y se guarda con su convencion`() {
        show()
        basics()
        tm()

        tap(CUSTOM_TRANSFORMATION_TAG)
        tap(customTransformationTag(TransformationKind.HELMERT_3))
        compose.onNodeWithTag(customHelmertTag("rx")).assertDoesNotExist()
        type(customHelmertTag("tx"), "-87")
        type(customHelmertTag("ty"), "-98")
        type(customHelmertTag("tz"), "-121")
        tap(customConventionTag(RotationConvention.COORDINATE_FRAME))
        tap(CUSTOM_SAVE_TAG)

        val d = saved().definition
        d.transformationName shouldBe "Custom Helmert 3-parameter (CF)"
        d.transformationSteps.contains("p=-87.0,-98.0,-121.0,0.0,0.0,0.0,0.0") shouldBe true
        d.transformationSteps.contains("conv=CF") shouldBe true
    }

    @Test
    fun `S035 RF-3 un Helmert de 7 parametros y Molodensky-Badekas piden todos y el segundo el punto de rotacion`() {
        show()
        basics()
        tm()

        tap(CUSTOM_TRANSFORMATION_TAG)
        tap(customTransformationTag(TransformationKind.HELMERT_7))
        listOf("tx", "ty", "tz", "rx", "ry", "rz", "scalePpm").forEach {
            compose.onNodeWithTag(customHelmertTag(it)).performScrollTo().assertExists()
        }
        compose.onNodeWithTag(customPivotTag("x")).assertDoesNotExist()

        tap(CUSTOM_TRANSFORMATION_TAG)
        tap(customTransformationTag(TransformationKind.MOLODENSKY_BADEKAS))
        compose.onNodeWithTag(customPivotTag("x")).performScrollTo().assertExists()
        type(customPivotTag("x"), "4000000")
        type(customPivotTag("y"), "100000")
        type(customPivotTag("z"), "4800000")
        tap(CUSTOM_SAVE_TAG)

        saved().definition.transformationSteps.contains("px=4000000.0,100000.0,4800000.0") shouldBe true
    }

    @Test
    fun `S035 RF-3 un Helmert con un valor que no es un numero lo marca`() {
        show()
        basics()
        tm()
        tap(CUSTOM_TRANSFORMATION_TAG)
        tap(customTransformationTag(TransformationKind.HELMERT_3))

        type(customHelmertTag("tx"), "abc")

        compose.onNodeWithText("No se entiende").assertExists()
        tap(CUSTOM_SAVE_TAG)
        runBlocking { repository.all() } shouldBe emptyList()
    }

    @Test
    fun `S035 RF-3 una rejilla se elige de la lista de rejillas de desplazamiento`() {
        val grid = CrsCatalog.bundled.grids.values.filter { it.kind == GridKind.HORIZONTAL && it.bundled }.minBy { it.name }.name
        show()
        basics()
        tm()

        tap(CUSTOM_TRANSFORMATION_TAG)
        tap(customTransformationTag(TransformationKind.GRID))
        tap(CUSTOM_GRID_TAG)
        tap(customGridTag(grid))
        tap(CUSTOM_SAVE_TAG)

        saved().definition.transformationSteps shouldBe "G;grid=$grid;inv=0"
    }

    @Test
    fun `S035 RF-3 sin elegir rejilla no se guarda y se dice`() {
        show()
        basics()
        tm()
        tap(CUSTOM_TRANSFORMATION_TAG)
        tap(customTransformationTag(TransformationKind.GRID))

        tap(CUSTOM_SAVE_TAG)

        compose.onNodeWithText("Rejilla desconocida").assertExists()
        runBlocking { repository.all() } shouldBe emptyList()
    }

    @Test
    fun `S035 RF-5 las tres alturas se eligen y se guardan`() {
        show()
        basics()
        tm()

        tap(CUSTOM_HEIGHT_TAG)
        tap(customHeightTag(HeightKind.CONSTANT))
        type(CUSTOM_UNDULATION_TAG, "48.5")
        tap(CUSTOM_SAVE_TAG)

        val first = saved()
        first.height shouldBe CustomHeight.ConstantGeoid(48.5)
    }

    @Test
    fun `S035 RF-5 un geoide se elige de la lista y la ondulacion constante pide un numero`() {
        val geoid = CrsCatalog.bundled.grids.values.filter { it.kind == GridKind.GEOID }.minBy { it.name }.name
        show()
        basics()
        tm()

        tap(CUSTOM_HEIGHT_TAG)
        tap(customHeightTag(HeightKind.GEOID))
        tap(CUSTOM_HEIGHT_GEOID_TAG)
        tap(customGeoidTag(geoid))
        tap(CUSTOM_SAVE_TAG)

        saved().height shouldBe CustomHeight.Geoid(geoid)
    }

    @Test
    fun `S035 RF-5 con ondulacion constante sin numero no se guarda`() {
        show()
        basics()
        tm()
        tap(CUSTOM_HEIGHT_TAG)
        tap(customHeightTag(HeightKind.CONSTANT))

        tap(CUSTOM_SAVE_TAG)

        runBlocking { repository.all() } shouldBe emptyList()
        compose.onNodeWithText("Falta el valor").assertExists()
    }
}
