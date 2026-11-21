package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.crs.CrsLibraryHost
import es.topophone.app.ui.crs.CrsLibraryRequest
import es.topophone.app.ui.crs.GridStatusViewModel
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.ProjectDraft
import es.topophone.core.model.project.SpainCrs
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.grids.GridStore
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Elegir un SC de la biblioteca en el formulario de proyecto y ver lo que falta (S034/RF-1, RF-13, RF-15, RF-18, RF-24). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectFormLibraryCrsTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val catalog = CrsCatalog.bundled
    private val initial = ProjectDefaults().toDraft().copy(name = "Paris")

    private val lambert93: LibraryCrs = when (val result = catalog.build(2154, CorrectionsDatum.ETRS89, GeoPoint(48.85, 2.35))) {
        is CrsBuildResult.Ready -> LibraryCrs(result.definition, CorrectionsDatum.ETRS89, "fr_ign_RAF20.tif")
        is CrsBuildResult.NeedsGrids -> LibraryCrs(result.definition, CorrectionsDatum.ETRS89, "fr_ign_RAF20.tif")
        else -> error("$result")
    }

    /** Un anfitrión de pega: el flujo es un botón que «elige» [lambert93] y el aviso de rejillas un texto. */
    private class FakeHost(private val chosen: LibraryCrs) : CrsLibraryHost {
        val requests = mutableListOf<CrsLibraryRequest>()
        var chooser: (() -> Unit)? = null

        @Composable
        override fun Flow(
            themeState: ThemeState,
            onToggleSun: () -> Unit,
            request: CrsLibraryRequest,
            onChosen: (LibraryCrs) -> Unit,
            onDismiss: () -> Unit,
        ) {
            requests += request
            chooser = { onChosen(chosen) }
            Text("flujo abierto", modifier = Modifier.testTag("fake_flow_open"))
        }

        @Composable
        override fun MissingGrids(crs: ProjectCrs) {
            Text("faltan", modifier = Modifier.testTag("fake_missing"))
        }
    }

    private fun show(draft: ProjectDraft, host: CrsLibraryHost?, editing: Boolean = false): () -> ProjectDraft {
        var current by mutableStateOf(draft)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectFormScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectFormUiState(draft = current, loaded = true, editing = editing),
                    ProjectFormActions(onChange = { current = it }),
                    library = host,
                )
            }
        }
        return { current }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun `S034 RF-1 con la biblioteca el formulario ofrece buscar en ella y sin ella no`() {
        show(initial, host = null)
        compose.onNodeWithTag(CRS_LIBRARY_OPEN_TAG).assertDoesNotExist()
    }

    @Test
    fun `S034 RF-13 elegir un SC de la biblioteca lo pone en el proyecto con su geoide y la altitud ortometrica`() {
        val host = FakeHost(lambert93)
        val draft = show(initial.copy(correctionsDatum = CorrectionsDatum.ETRS89), host)

        click(CRS_LIBRARY_OPEN_TAG)
        compose.onNodeWithTag("fake_flow_open").assertIsDisplayed()
        compose.runOnUiThread { host.chooser!!.invoke() }
        compose.waitForIdle()

        draft().crs shouldBe lambert93
        draft().heightSystem shouldBe HeightSystem.ORTHOMETRIC
        host.requests.single().corrections shouldBe CorrectionsDatum.ETRS89
        compose.onNodeWithTag(CRS_LIBRARY_SUMMARY_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S034 RF-13 un SC de la biblioteca sin geoide deja la altura elipsoidal`() {
        val withoutGeoid = lambert93.copy(geoidGrid = null)
        val host = FakeHost(withoutGeoid)
        val draft = show(initial, host)

        click(CRS_LIBRARY_OPEN_TAG)
        compose.onNodeWithTag("fake_flow_open").assertIsDisplayed()
        compose.runOnUiThread { host.chooser!!.invoke() }
        compose.waitForIdle()

        draft().heightSystem shouldBe HeightSystem.ELLIPSOIDAL
    }

    @Test
    fun `S034 RF-13 con un SC de la biblioteca se ve su resumen y no los grupos de Espana ni el datum de las correcciones editable`() {
        show(initial.copy(crs = lambert93, heightSystem = HeightSystem.ORTHOMETRIC), FakeHost(lambert93))

        compose.onNodeWithTag(CRS_LIBRARY_SUMMARY_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(crsOptionTag(SpainCrs.ETRS89_UTM30)).assertDoesNotExist()
        compose.onNodeWithTag(datumOptionTag(CorrectionsDatum.REGCAN95)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("fake_missing").performScrollTo().assertIsDisplayed() // RF-18: lo que falta, junto al SC
    }

    @Test
    fun `S034 RF-13 se puede volver a un SC de Espana y entonces vuelven sus grupos`() {
        val draft = show(initial.copy(crs = lambert93, heightSystem = HeightSystem.ORTHOMETRIC), FakeHost(lambert93))

        click(CRS_LIBRARY_SPAIN_TAG)

        draft().crs shouldBe SpainCrs.ETRS89_UTM30
        compose.onNodeWithTag(crsOptionTag(SpainCrs.ETRS89_UTM30)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S034 RF-13 al editar un proyecto no se cambia de sistema desde la biblioteca`() {
        show(initial.copy(crs = lambert93, heightSystem = HeightSystem.ORTHOMETRIC), FakeHost(lambert93), editing = true)

        compose.onNodeWithTag(CRS_LIBRARY_SUMMARY_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(CRS_LIBRARY_CHANGE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(CRS_LIBRARY_SPAIN_TAG).assertDoesNotExist()
    }

    @Test
    fun `S034 RF-15 un proyecto con un geoide sin instalar se crea y la descarga se ofrece despues`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            val projects = RoomProjectRepository(db)
            val store = GridStore(folder.newFolder("grids")) { emptyList() }

            val saved = runBlocking { projects.create(initial.copy(crs = lambert93, heightSystem = HeightSystem.ORTHOMETRIC)) }

            saved.shouldBeInstanceOf<SaveResult.Saved>()
            GridStatusViewModel(store).missingFor(lambert93).map { it.name } shouldBe listOf("fr_ign_RAF20.tif")
        } finally {
            db.close()
        }
    }

    @Test
    fun `S034 RF-18 al abrir un proyecto sin las rejillas que usa se dice cuales faltan y al instalarlas dejan de faltar`() {
        val directory = folder.newFolder("grids")
        val status = GridStatusViewModel(GridStore(directory) { emptyList() })

        status.missingFor(lambert93).map { it.name } shouldBe listOf("fr_ign_RAF20.tif")

        File(directory, "fr_ign_RAF20.tif").writeBytes(ByteArray(4))
        status.missingFor(lambert93).shouldBeEmpty()
    }

    @Test
    fun `S034 RF-24 un SC sin rejillas descargables y un SC de Espana trabajan sin conexion y no echan nada en falta`() {
        val status = GridStatusViewModel(GridStore(folder.newFolder("grids")) { emptyList() })
        val etrs89 = when (val result = catalog.build(25830, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7))) {
            is CrsBuildResult.Ready -> LibraryCrs(result.definition)
            else -> error("$result")
        }

        status.missingFor(etrs89).shouldBeEmpty()
        status.missingFor(SpainCrs.ED50_UTM30).shouldBeEmpty()
        status.missingFor(etrs89.copy(geoidGrid = "es_ign_egm08-rednap.tif")).shouldBeEmpty() // el geoide viene en la app (RF-17)
    }
}
