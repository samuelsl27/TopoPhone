package es.topophone.app.ui.crs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.data.grids.GridDownloader
import es.topophone.data.grids.GridHttp
import es.topophone.data.grids.GridHttpResponse
import es.topophone.data.grids.GridStore
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Descargar una rejilla y la lista de las descargadas (S034/RF-19 a RF-23). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class GridDownloadTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    private val name = "fr_ign_ggpf02-Bora.tif"
    private val entry = CrsCatalog.bundled.grids.getValue(name)
    private val bytes = File(root, "fixtures/geodesia/rejillas/$name").readBytes()
    private val directory by lazy { folder.newFolder("grids") }
    private val projects = mutableListOf<Project>()
    private val store by lazy { GridStore(directory) { projects } }

    private class FixedHttp(private val body: ByteArray, private val fail: Boolean = false) : GridHttp {
        var calls = 0

        override suspend fun <T> get(url: String, read: (GridHttpResponse) -> T): T {
            calls++
            if (fail) throw IOException("sin cobertura")
            return read(GridHttpResponse(200, body.size.toLong(), ByteArrayInputStream(body)))
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(http: GridHttp) = GridDownloadViewModel(GridDownloader(store, http))

    private fun definition(geoid: String?) = CrsDefinition(
        epsg = 2154, name = "n", projected = true, datumCode = 1, datumName = "d", ellipsoidSemiMajorM = 6378137.0,
        ellipsoidInverseFlattening = 298.257222101, primeMeridianDeg = 0.0, method = "lcc2", parameters = emptyMap(), unitMetres = 1.0,
        area = null, frame = "ETRS89", transformationName = "t", transformationAccuracyM = null, transformationArea = null,
        transformationSteps = "null",
    ).also { check(geoid == null || geoid.isNotEmpty()) }

    @Test
    fun `S034 RF-19 pedir una rejilla solo abre la confirmacion con su tamano y licencia y no baja nada`() {
        val http = FixedHttp(bytes)
        val vm = viewModel(http)

        vm.ask(entry)

        vm.state.value.phase shouldBe DownloadPhase.CONFIRM
        vm.state.value.entry shouldBe entry
        http.calls shouldBe 0
        store.state(name) shouldBe es.topophone.data.grids.GridState.Missing
    }

    @Test
    fun `S034 RF-19 RF-21 confirmar descarga, comprueba e instala la rejilla y avisa al terminar`() {
        val vm = viewModel(FixedHttp(bytes))
        var done = false
        vm.ask(entry)

        vm.confirm { done = true }
        waitUntil { vm.state.value.phase != DownloadPhase.DOWNLOADING }

        vm.state.value.phase shouldBe DownloadPhase.DONE
        done shouldBe true
        store.state(name) shouldBe es.topophone.data.grids.GridState.Installed(bytes.size.toLong())
    }

    @Test
    fun `S034 RF-22 una descarga que falla deja la confirmacion para reintentar y no instala nada`() {
        val vm = viewModel(FixedHttp(bytes, fail = true))
        vm.ask(entry)

        vm.confirm()
        waitUntil { vm.state.value.phase != DownloadPhase.DOWNLOADING }

        vm.state.value.phase shouldBe DownloadPhase.INTERRUPTED
        directory.listFiles()!!.size shouldBe 0
    }

    @Test
    fun `S034 RF-21 un fichero con el SHA-256 alterado se muestra como danado`() {
        val tampered = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val vm = viewModel(FixedHttp(tampered))
        vm.ask(entry)

        vm.confirm()
        waitUntil { vm.state.value.phase != DownloadPhase.DOWNLOADING }

        vm.state.value.phase shouldBe DownloadPhase.DAMAGED
        directory.listFiles()!!.size shouldBe 0
    }

    @Test
    fun `S034 RF-19 RF-20 el dialogo muestra tamano, licencia y fuente y avisa de las licencias dudosas`() {
        val unclear = entry.copy(license = entry.license.copy(open = false, name = "Licencia no clara"))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GridDownloadDialog(GridDownloadUiState(unclear), onConfirm = {}, onDismiss = {})
            }
        }

        compose.onNodeWithTag(GRID_DOWNLOAD_CONFIRM_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GRID_DOWNLOAD_LICENSE_WARNING_TAG).assertIsDisplayed()
    }

    @Test
    fun `S034 RF-19 el dialogo de una licencia abierta no lleva el aviso y confirmar llama a la descarga`() {
        var confirmed = false
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                GridDownloadDialog(GridDownloadUiState(entry), onConfirm = { confirmed = true }, onDismiss = {})
            }
        }

        compose.onNodeWithTag(GRID_DOWNLOAD_LICENSE_WARNING_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GRID_DOWNLOAD_CONFIRM_TAG).performClick()
        confirmed shouldBe true
    }

    @Test
    fun `S034 RF-23 la lista de instaladas da el tamano, el proyecto que usa la rejilla y no deja borrarla`() {
        File(root, "fixtures/geodesia/rejillas/$name").copyTo(File(directory, name))
        projects += project("Obra", definition(null), name)
        val vm = InstalledGridsViewModel(store)

        vm.refresh()
        waitUntil { vm.state.value.loaded }

        vm.state.value.grids.single().usedBy shouldBe listOf("Obra")
        vm.delete(name)
        waitUntil { vm.state.value.blockedBy.isNotEmpty() }
        vm.state.value.blockedBy shouldBe listOf("Obra")
        store.state(name) shouldBe es.topophone.data.grids.GridState.Installed(bytes.size.toLong())

        projects.clear()
        vm.delete(name)
        waitUntil { vm.state.value.grids.isEmpty() }
        store.state(name) shouldBe es.topophone.data.grids.GridState.Missing
    }

    @Test
    fun `S034 RF-23 la pantalla de instaladas ofrece borrar solo las que nadie usa`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                InstalledGridsScreen(
                    ThemeState.DEFAULT,
                    {},
                    InstalledGridsUiState(
                        grids = listOf(
                            es.topophone.data.grids.InstalledGrid(entry, 1000, listOf("Obra")),
                            es.topophone.data.grids.InstalledGrid(
                                CrsCatalog.bundled.grids.getValue("us_nga_egm08_25.tif"),
                                2000,
                                emptyList(),
                            ),
                        ),
                        loaded = true,
                    ),
                    onDelete = {},
                    onDismissBlocked = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(installedGridTag(name)).assertIsDisplayed()
        compose.onNodeWithTag(installedGridDeleteTag(name)).assertIsNotEnabled()
        compose.onNodeWithTag(installedGridDeleteTag("us_nga_egm08_25.tif")).assertIsEnabled()
    }

    private fun project(projectName: String, definition: CrsDefinition, geoid: String?) = Project(
        id = projectName, name = projectName, author = "", description = "", crs = LibraryCrs(definition, CorrectionsDatum.ETRS89, geoid),
        linearUnit = LinearUnit.METRE, angularUnit = AngularUnit.DMS, correctionsDatum = CorrectionsDatum.ETRS89, folderId = null,
        createdAtMs = 0, modifiedAtMs = 0, openedAtMs = null, deletedAtMs = null,
    )

    private fun waitUntil(condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition() && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(20)
        check(condition()) { "Tiempo agotado" }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
