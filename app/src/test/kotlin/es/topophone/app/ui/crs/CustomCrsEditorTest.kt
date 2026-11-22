package es.topophone.app.ui.crs

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.nulls.shouldNotBeNull
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

/** El editor de SC personalizados: nombre, punto de partida, elipsoide y unidades, y el marcado de los campos que fallan (S035/RF-1, RF-2, RF-5, RF-6). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CustomCrsEditorTest {

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

    private fun show(mine: List<CustomCrs> = emptyList(), request: CustomCrsEditorRequest = CustomCrsEditorRequest()) {
        viewModel.open(request)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                val state by viewModel.state.collectAsState()
                CustomCrsEditorScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    CustomCrsEditorActions(onChange = viewModel::update, onStartFrom = viewModel::startFrom, onSave = viewModel::save),
                    mine,
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

    private fun definition(name: String) = CrsDefinition(
        epsg = CUSTOM_EPSG,
        name = name,
        projected = false,
        datumCode = 0,
        datumName = "Datum viejo",
        ellipsoidSemiMajorM = 6378388.0,
        ellipsoidInverseFlattening = 297.0,
        primeMeridianDeg = 0.0,
        method = "geo",
        parameters = emptyMap(),
        unitMetres = 1.0,
        area = null,
        frame = "ETRS89",
        transformationName = "No datum shift",
        transformationAccuracyM = null,
        transformationArea = null,
        transformationSteps = "null",
    )

    @Test
    fun `S035 RF-2 con nombre y elipsoide validos se guarda un SC nuevo`() {
        show()

        type(CUSTOM_NAME_TAG, "Obra norte")
        type(CUSTOM_SEMI_MAJOR_TAG, "6378137")
        type(CUSTOM_INVERSE_FLATTENING_TAG, "298.257222101")
        tap(CUSTOM_SAVE_TAG)

        val saved = runBlocking { repository.all() }.single()
        saved.name shouldBe "Obra norte"
        saved.definition.ellipsoidSemiMajorM shouldBe 6378137.0
        saved.definition.ellipsoidInverseFlattening shouldBe 298.257222101
        viewModel.state.value.saved.shouldNotBeNull()
    }

    @Test
    fun `S035 RF-6 al guardar con campos sin escribir se marcan y no se guarda`() {
        show()

        tap(CUSTOM_SAVE_TAG)

        compose.onAllNodesWithText("Falta el valor").fetchSemanticsNodes().size shouldBe 3 // nombre, semieje y aplanamiento
        runBlocking { repository.all() } shouldBe emptyList()
    }

    @Test
    fun `S035 RF-6 un valor no valido se marca en cuanto se escribe`() {
        show()

        type(CUSTOM_SEMI_MAJOR_TAG, "-5")
        type(CUSTOM_INVERSE_FLATTENING_TAG, "abc")

        compose.onNodeWithText("Tiene que ser mayor que 0").assertIsDisplayed()
        compose.onNodeWithText("No se entiende").assertIsDisplayed()
    }

    @Test
    fun `S035 RF-2 una esfera es un aplanamiento 0 y la coma decimal se entiende`() {
        show()

        type(CUSTOM_NAME_TAG, "Esfera")
        type(CUSTOM_SEMI_MAJOR_TAG, "6371000,5")
        type(CUSTOM_INVERSE_FLATTENING_TAG, "0")
        tap(CUSTOM_SAVE_TAG)

        val saved = runBlocking { repository.all() }.single()
        saved.definition.ellipsoidSemiMajorM shouldBe 6371000.5
        saved.definition.ellipsoidInverseFlattening shouldBe 0.0
    }

    @Test
    fun `S035 RF-5 las unidades en pies se guardan como metros por unidad`() {
        show()
        type(CUSTOM_NAME_TAG, "Pies")
        type(CUSTOM_SEMI_MAJOR_TAG, "6378137")
        type(CUSTOM_INVERSE_FLATTENING_TAG, "298.257222101")

        tap(customUnitTag(LinearUnit.US_SURVEY_FOOT))
        compose.onNodeWithTag(customUnitTag(LinearUnit.US_SURVEY_FOOT)).assertIsSelected()
        tap(CUSTOM_SAVE_TAG)

        runBlocking { repository.all() }.single().definition.unitMetres shouldBe LinearUnit.US_SURVEY_FOOT.metres
    }

    @Test
    fun `S035 RF-10 un nombre que ya existe se dice y no se guarda`() {
        runBlocking { repository.create(definition("Obra"), CustomHeight.Ellipsoidal) }
        show()

        type(CUSTOM_NAME_TAG, "obra")
        type(CUSTOM_SEMI_MAJOR_TAG, "6378137")
        type(CUSTOM_INVERSE_FLATTENING_TAG, "298.257222101")
        tap(CUSTOM_SAVE_TAG)

        compose.onNodeWithText("Ya tienes un SC con ese nombre").assertExists()
        runBlocking { repository.all() }.size shouldBe 1
    }

    @Test
    fun `S035 RF-1 partir de uno de Mis SC trae su elipsoide y deja el nombre para escribirlo`() {
        val source = runBlocking {
            repository.create(definition("Mi datum"), CustomHeight.Ellipsoidal)
        } as es.topophone.data.crs.CustomCrsSave.Saved
        show(mine = listOf(source.crs))

        type(CUSTOM_NAME_TAG, "Copia de trabajo")
        tap(CUSTOM_START_MINE_TAG)
        compose.onNodeWithTag(customStartMineTag(source.crs.id)).performClick()

        viewModel.state.value.form.semiMajor shouldBe "6378388.0"
        viewModel.state.value.form.inverseFlattening shouldBe "297.0"
        viewModel.state.value.form.name shouldBe "Copia de trabajo"
        compose.onNodeWithText("Copia de trabajo").assertIsDisplayed()
    }

    @Test
    fun `S035 RF-1 partir de vacio borra lo escrito de elipsoide pero deja el nombre`() {
        show()
        type(CUSTOM_NAME_TAG, "X")
        type(CUSTOM_SEMI_MAJOR_TAG, "6378137")

        tap(CUSTOM_START_EMPTY_TAG)

        viewModel.state.value.form.semiMajor shouldBe ""
        viewModel.state.value.form.name shouldBe "X"
    }

    @Test
    fun `S035 RF-1 editar un SC de Mis SC trae todo y al guardar lo actualiza`() {
        val source = (
            runBlocking {
                repository.create(definition("Mi datum"), CustomHeight.Ellipsoidal)
            } as es.topophone.data.crs.CustomCrsSave.Saved
            ).crs
        show(request = CustomCrsEditorRequest(editing = source))

        compose.onNodeWithText("Editar SC personalizado").assertIsDisplayed()
        viewModel.state.value.form.name shouldBe "Mi datum"
        compose.onNodeWithTag(CUSTOM_DATUM_NAME_TAG).performScrollTo().performTextReplacement("Datum nuevo")
        tap(CUSTOM_SAVE_TAG)

        val all = runBlocking { repository.all() }
        all.size shouldBe 1
        all.single().definition.datumName shouldBe "Datum nuevo"
    }
}
