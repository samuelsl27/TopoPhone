package es.topophone.app.ui.crs

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.isCustom
import es.topophone.data.crs.CustomCrsSave
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

/** Mis SC: la lista de Ajustes, el selector con Mis SC y un SC personalizado como SC del proyecto y de origen de una importación (S035/RF-9, RF-10). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class MyCrsListTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeCustomCrsRepository()
    private lateinit var viewModel: MyCrsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = MyCrsViewModel(repository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun definition(name: String) = CrsDefinition(
        epsg = CUSTOM_EPSG,
        name = name,
        projected = true,
        datumCode = 0,
        datumName = "Datum de obra",
        ellipsoidSemiMajorM = 6378137.0,
        ellipsoidInverseFlattening = 298.257222101,
        primeMeridianDeg = 0.0,
        method = "tm",
        parameters = mapOf(
            "latitudeOfOriginDeg" to 0.0,
            "longitudeOfOriginDeg" to -3.0,
            "scaleFactor" to 0.9996,
            "falseEasting" to 500000.0,
            "falseNorthing" to 0.0,
        ),
        unitMetres = 1.0,
        area = null,
        frame = "ETRS89",
        transformationName = "No datum shift",
        transformationAccuracyM = null,
        transformationArea = null,
        transformationSteps = "null",
    )

    private fun add(name: String) = (
        runBlocking {
            repository.create(definition(name), CustomHeight.Ellipsoidal)
        } as CustomCrsSave.Saved
        ).crs

    private val edited = mutableListOf<String>()

    private fun showList() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                val state by viewModel.state.collectAsState()
                MyCrsScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    MyCrsActions(
                        onEdit = { edited += it.id },
                        onDuplicate = viewModel::duplicate,
                        onRename = viewModel::rename,
                        onDelete = viewModel::delete,
                        onDismissNameTaken = viewModel::dismissNameTaken,
                    ),
                )
            }
        }
    }

    @Test
    fun `S035 RF-9 sin SC personalizados la lista lo dice`() {
        showList()

        compose.onNodeWithTag(MY_CRS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `S035 RF-9 la lista muestra los SC por nombre y al tocar uno se edita`() {
        val beta = add("Beta")
        add("Alfa")
        showList()

        compose.onNodeWithText("Alfa").assertIsDisplayed()
        compose.onNodeWithTag(myCrsTag(beta.id)).performClick()

        edited shouldBe listOf(beta.id)
    }

    @Test
    fun `S035 RF-10 duplicar anade una copia con otro nombre`() {
        val a = add("Obra")
        showList()

        compose.onNodeWithTag(myCrsMenuTag(a.id)).performClick()
        compose.onNodeWithTag(myCrsActionTag("duplicate", a.id)).performClick()

        runBlocking { repository.all() }.map { it.name }.toSet() shouldBe setOf("Obra", "Obra (copia)")
        compose.onNodeWithText("Obra (copia)").assertIsDisplayed()
    }

    @Test
    fun `S035 RF-10 renombrar cambia el nombre y uno repetido se dice sin cambiar nada`() {
        val a = add("Alfa")
        add("Beta")
        showList()

        compose.onNodeWithTag(myCrsMenuTag(a.id)).performClick()
        compose.onNodeWithTag(myCrsActionTag("rename", a.id)).performClick()
        compose.onNodeWithTag(MY_CRS_RENAME_FIELD_TAG).performTextReplacement("beta")
        compose.onNodeWithTag(MY_CRS_RENAME_CONFIRM_TAG).performClick()

        compose.onNodeWithTag(MY_CRS_NAME_TAKEN_TAG).assertIsDisplayed()
        runBlocking { repository.get(a.id) }!!.name shouldBe "Alfa"

        compose.onNodeWithTag(MY_CRS_RENAME_FIELD_TAG).performTextReplacement("Gamma")
        compose.onNodeWithTag(MY_CRS_RENAME_CONFIRM_TAG).performClick()

        runBlocking { repository.get(a.id) }!!.name shouldBe "Gamma"
        compose.onNodeWithText("Gamma").assertIsDisplayed()
    }

    @Test
    fun `S035 RF-10 borrar pide confirmacion y quita el SC de la lista`() {
        val a = add("Obra")
        showList()

        compose.onNodeWithTag(myCrsMenuTag(a.id)).performClick()
        compose.onNodeWithTag(myCrsActionTag("delete", a.id)).performClick()
        runBlocking { repository.all() }.size shouldBe 1
        compose.onNodeWithTag(MY_CRS_DELETE_CONFIRM_TAG).performClick()

        runBlocking { repository.all() } shouldBe emptyList()
        compose.onNodeWithTag(MY_CRS_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun `S035 RF-9 el selector de SC ofrece Mis SC junto a la biblioteca y permite elegir o crear`() {
        val a = add("Obra norte")
        val chosen = mutableListOf<String>()
        var created = 0
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CrsPickerScreen(
                    ThemeState.DEFAULT,
                    {},
                    CrsPickerUiState(mine = listOf(a)),
                    CrsPickerActions(onChooseMine = { chosen += it.id }, onCreateCustom = { created++ }),
                )
            }
        }

        compose.onNodeWithTag(crsMineTag(a.id)).performClick()
        compose.onNodeWithTag(CRS_CREATE_CUSTOM_TAG).performClick()

        chosen shouldBe listOf(a.id)
        created shouldBe 1
    }

    @Test
    fun `S035 RF-1 el selector del editor no ofrece Mis SC ni crear`() {
        val a = add("Obra norte")
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CrsPickerScreen(ThemeState.DEFAULT, {}, CrsPickerUiState(mine = listOf(a), allowCustom = false), CrsPickerActions())
            }
        }

        compose.onNodeWithTag(crsMineTag(a.id)).assertDoesNotExist()
        compose.onNodeWithTag(CRS_CREATE_CUSTOM_TAG).assertDoesNotExist()
    }

    @Test
    fun `S035 RF-9 un SC personalizado elegido es el SC del proyecto con su copia y como SC de origen de una importacion`() {
        val crs = add("Obra norte")
        val project = crs.toProjectCrs(CorrectionsDatum.ETRS89)
        val resources = ApplicationProvider.getApplicationContext<Context>().resources

        project.definition.isCustom shouldBe true
        project.definition.name shouldBe "Obra norte"
        project.correctionsDatum shouldBe CorrectionsDatum.ETRS89
        val source = LibrarySourceUi.of(LibraryCrs(project.definition, project.correctionsDatum), GeoPoint(40.4, -3.7))
        source.ready shouldBe true
        sourceSummaryText(resources, source) shouldContain "Sistema de origen: Obra norte (SC personalizado)"
        sourceSummaryText(resources, source) shouldContain "Sin cambio de datum"
    }

    @Test
    fun `S035 RF-11 un SC personalizado con el datum de WGS 84 fija las correcciones del proyecto`() {
        val wgs = (
            runBlocking {
                repository.create(definition("WGS obra").copy(frame = "WGS84"), CustomHeight.Ellipsoidal)
            } as CustomCrsSave.Saved
            ).crs

        wgs.toProjectCrs(CorrectionsDatum.ETRS89).correctionsDatum shouldBe CorrectionsDatum.ITRF_WGS84
        add("ETRS obra").toProjectCrs(CorrectionsDatum.ITRF_WGS84).correctionsDatum shouldBe CorrectionsDatum.ETRS89
        add("Regcan obra").toProjectCrs(CorrectionsDatum.REGCAN95).correctionsDatum shouldBe CorrectionsDatum.REGCAN95
    }
}
