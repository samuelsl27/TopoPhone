package es.topophone.app.ui.points

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.projects.ProjectListActions
import es.topophone.app.ui.projects.ProjectListScreen
import es.topophone.app.ui.projects.ProjectListUiState
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.app.ui.projects.SurveyPage
import es.topophone.app.ui.projects.SurveyPageScreen
import es.topophone.app.ui.projects.SurveyUiState
import es.topophone.app.ui.projects.projectMenuTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Los accesos a "Importar CSV/TXT", la navegación y el fichero elegido (S028/RF-1, RF-2, RF-3). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class PointImportEntriesTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    @Test
    fun `S028 RF-1 el menu de un proyecto de la lista ofrece Importar CSV o TXT`() {
        var imported: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onImportPoints = { imported = it }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Importar CSV/TXT").assertIsDisplayed().performClick()

        imported shouldBe "p1"
    }

    @Test
    fun `S028 RF-1 el menu de la lista de objetos ofrece Importar CSV o TXT`() {
        var imported = 0
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true),
                    ObjectListActions(onImportPoints = { imported++ }),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithText("Importar CSV/TXT").assertIsDisplayed().performClick()

        imported shouldBe 1
    }

    @Test
    fun `S028 RF-1 sin elegirlo en el menu no se abre el asistente`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(ThemeState.DEFAULT, {}, ProjectListUiState(projects = listOf(project)), ProjectListActions())
            }
        }

        compose.onNodeWithText("Importar CSV/TXT").assertDoesNotExist()
    }

    @Test
    fun `S028 RF-1 la navegacion lleva al asistente del proyecto con el fichero elegido`() {
        var navigated: Pair<String, String>? = null
        val navigation = SurveyNavigation(onImportPoints = { projectId, fileUri -> navigated = projectId to fileUri })

        navigation.onImportPoints("p1", "content://docs/file.csv")

        navigated shouldBe ("p1" to "content://docs/file.csv")
    }

    @Test
    fun `S028 RF-1 la pagina del asistente se pinta con su estado`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SurveyPageScreen(
                    SurveyPage.ImportPoints("p1", "content://docs/file.csv"),
                    ThemeState.DEFAULT,
                    {},
                    SurveyUiState(import = PointImportUiState(stage = ImportStage.WIZARD, fileName = "puntos.csv")),
                    SurveyNavigation(),
                )
            }
        }

        compose.onNodeWithText("Fichero: puntos.csv").assertIsDisplayed()
    }

    // --- El fichero elegido: nombre, tamaño y apertura (RF-1, RF-2) ---

    private val resolver get() = ApplicationProvider.getApplicationContext<Context>().contentResolver

    @Test
    fun `S028 RF-1 el fichero elegido se abre cada vez que hace falta, sin copiarlo`() {
        val uri = Uri.parse("content://es.topophone.test/puntos.csv")
        shadowOf(resolver).registerInputStream(uri, ByteArrayInputStream("a,b\n1,2\n".toByteArray()))

        val source = importSourceOf(resolver, uri)

        source.open().shouldNotBeNull().use { it.readBytes().size shouldBe 8 }
    }

    @Test
    fun `S028 RF-2 un fichero que ya no se puede abrir da null y no un error`() {
        val authority = "es.topophone.gone"
        Robolectric.setupContentProvider(GoneProvider::class.java, authority)
        val source = importSourceOf(resolver, Uri.parse("content://$authority/no-existe.csv"))

        source.open().shouldBeNull()
    }

    @Test
    fun `S028 RF-1 sin nombre del proveedor se usa el ultimo tramo de la direccion`() {
        val source = importSourceOf(resolver, Uri.parse("content://es.topophone.test/carpeta/puntos.csv"))

        source.name shouldBe "puntos.csv"
        source.sizeBytes.shouldBeNull()
    }
}

/** Un proveedor de ficheros que ya no tiene el fichero: abrirlo da FileNotFoundException, como un documento borrado. */
private class GoneProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? =
        null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw FileNotFoundException("ya no está")
}
