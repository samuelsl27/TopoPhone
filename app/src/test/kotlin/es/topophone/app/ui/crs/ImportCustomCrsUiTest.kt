package es.topophone.app.ui.crs

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.crs.CustomCrsJson
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CUSTOM_EPSG
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
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

/** Exportar un SC a JSON e importarlo con confirmación, los motivos de rechazo y el nombre repetido (S035/RF-12 a RF-15). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ImportCustomCrsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeCustomCrsRepository()
    private lateinit var viewModel: MyCrsViewModel
    private val savedFiles = mutableListOf<CustomCrs>()
    private val sharedFiles = mutableListOf<CustomCrs>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = MyCrsViewModel(repository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun definition(name: String, meridian: Double = -3.0) = CrsDefinition(
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
            "longitudeOfOriginDeg" to meridian,
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

    private fun add(name: String, meridian: Double = -3.0) =
        (runBlocking { repository.create(definition(name, meridian), CustomHeight.Ellipsoidal) } as CustomCrsSave.Saved).crs

    private fun show() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                val state by viewModel.state.collectAsState()
                MyCrsScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    MyCrsActions(
                        onExport = viewModel::startExport,
                        onConfirmImport = viewModel::confirmImport,
                        onResolveConflict = viewModel::resolveConflict,
                        onDismissImport = viewModel::dismissImport,
                        onExportSave = { crs ->
                            savedFiles += crs
                            viewModel.exportDone(true)
                        },
                        onExportShare = { crs ->
                            sharedFiles += crs
                            viewModel.exportDone(false)
                        },
                        onCancelExport = viewModel::cancelExport,
                        onDismissExportFailed = viewModel::dismissExportFailed,
                    ),
                )
            }
        }
    }

    @Test
    fun `S035 RF-12 exportar pregunta si se guarda o se comparte y el JSON vuelve a leerse igual`() {
        val crs = add("Obra norte", meridian = -3.123456789012345)
        show()

        compose.onNodeWithTag(myCrsMenuTag(crs.id)).performClick()
        compose.onNodeWithTag(myCrsActionTag("export", crs.id)).performClick()
        compose.onNodeWithTag(MY_CRS_EXPORT_SAVE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MY_CRS_EXPORT_SHARE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MY_CRS_EXPORT_SAVE_TAG).performClick()

        savedFiles shouldBe listOf(crs)
        fileNameOf(crs) shouldBe "Obra_norte.json"
        val read = CustomCrsJson.read(CustomCrsJson.write(savedFiles.single()))
        (read as es.topophone.core.formats.crs.CustomCrsReadResult.Ok).crs.definition shouldBe crs.definition
    }

    @Test
    fun `S035 RF-12 un fallo al guardar o compartir se avisa`() {
        val crs = add("Obra")
        show()

        compose.onNodeWithTag(myCrsMenuTag(crs.id)).performClick()
        compose.onNodeWithTag(myCrsActionTag("export", crs.id)).performClick()
        compose.onNodeWithTag(MY_CRS_EXPORT_SHARE_TAG).performClick()

        compose.onNodeWithTag(MY_CRS_EXPORT_FAILED_TAG).assertIsDisplayed()
    }

    @Test
    fun `S035 RF-13 un SC exportado se importa tras confirmarlo y queda en Mis SC igual`() {
        val original = add("Obra norte", meridian = -3.123456789012345)
        val json = CustomCrsJson.write(original)
        runBlocking { repository.delete(original.id) }
        show()

        viewModel.importText(json)
        compose.onNodeWithTag(MY_CRS_IMPORT_SUMMARY_TAG).assertIsDisplayed()
        runBlocking { repository.all() } shouldBe emptyList() // todavía no se ha confirmado
        compose.onNodeWithTag(MY_CRS_IMPORT_ADD_TAG).performClick()

        compose.onNodeWithTag(MY_CRS_DONE_TAG).assertIsDisplayed()
        val added = runBlocking { repository.all() }.single()
        added.name shouldBe "Obra norte"
        added.definition shouldBe original.definition.copy(name = "Obra norte")
    }

    @Test
    fun `S035 RF-13 el resumen de la confirmacion dice el nombre, el datum y el elipsoide`() {
        val json = CustomCrsJson.write(add("Obra norte"))
        runBlocking { repository.delete(repository.all().single().id) }
        show()

        viewModel.importText(json)

        val text = compose.onNodeWithTag(
            MY_CRS_IMPORT_SUMMARY_TAG,
        ).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }
        text shouldContain "Obra norte"
        text shouldContain "Datum de obra"
        text shouldContain "Transversa de Mercator"
        text shouldContain "6378137.0"
    }

    @Test
    fun `S035 RF-14 un fichero no valido, de otra version o con un metodo no soportado no se importa y dice el motivo`() {
        val valid = CustomCrsJson.write(add("Obra"))
        runBlocking { repository.delete(repository.all().single().id) }
        show()

        listOf(
            "esto no es un JSON" to "El fichero no es un SC de TopoPhone.",
            valid.replace("\"version\":1", "\"version\":2") to "Es de una versión de TopoPhone que esta no conoce.",
            valid.replace("\"method\":\"tm\"", "\"method\":\"xyz\"") to
                "Usa una proyección o una transformación que esta versión no calcula.",
            valid.replace("\"scaleFactor\":0.9996", "\"scaleFactor\":-1.0") to "Algún número del fichero está fuera de rango.",
        ).forEach { (text, reason) ->
            viewModel.importText(text)
            val shown = compose.onNodeWithTag(
                MY_CRS_REJECTED_TAG,
            ).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString("") { it.text }
            shown shouldBe reason
            viewModel.dismissImport()
            compose.waitForIdle()
        }
        runBlocking { repository.all() } shouldBe emptyList()
    }

    @Test
    fun `S035 RF-14 un fichero que no se pudo leer se rechaza`() {
        show()

        viewModel.importText(null)

        compose.onNodeWithTag(MY_CRS_REJECTED_TAG).assertIsDisplayed()
    }

    @Test
    fun `S035 RF-15 con el mismo nombre y otra definicion se pregunta y reemplazar sustituye la definicion`() {
        add("Obra", meridian = -3.0)
        val json = CustomCrsJson.write(CustomCrs("", "Obra", definition("Obra", meridian = -2.0), CustomHeight.Ellipsoidal))
        show()

        viewModel.importText(json)
        compose.onNodeWithTag(MY_CRS_IMPORT_ADD_TAG).performClick()
        compose.onNodeWithTag(MY_CRS_CONFLICT_REPLACE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(MY_CRS_CONFLICT_REPLACE_TAG).performClick()

        runBlocking { repository.all() }.single().definition.parameters["longitudeOfOriginDeg"] shouldBe -2.0
    }

    @Test
    fun `S035 RF-15 conservar ambos anade el importado con otro nombre y omitir no cambia nada`() {
        val first = add("Obra", meridian = -3.0)
        val json = CustomCrsJson.write(CustomCrs("", "Obra", definition("Obra", meridian = -2.0), CustomHeight.Ellipsoidal))
        show()

        viewModel.importText(json)
        compose.onNodeWithTag(MY_CRS_IMPORT_ADD_TAG).performClick()
        compose.onNodeWithTag(MY_CRS_CONFLICT_SKIP_TAG).performClick()
        runBlocking { repository.all() } shouldBe listOf(first)

        viewModel.importText(json)
        compose.onNodeWithTag(MY_CRS_IMPORT_ADD_TAG).performClick()
        compose.onNodeWithTag(MY_CRS_CONFLICT_KEEP_TAG).performClick()
        runBlocking { repository.all() }.map { it.name }.toSet() shouldBe setOf("Obra", "Obra (2)")
    }

    @Test
    fun `S035 RF-15 el mismo SC otra vez no pregunta y dice que ya estaba`() {
        val json = CustomCrsJson.write(add("Obra"))
        show()

        viewModel.importText(json)
        compose.onNodeWithTag(MY_CRS_IMPORT_ADD_TAG).performClick()

        compose.onNodeWithTag(MY_CRS_DONE_TAG).assertIsDisplayed()
        runBlocking { repository.all() }.size shouldBe 1
    }
}
