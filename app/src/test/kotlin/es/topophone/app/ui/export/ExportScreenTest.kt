package es.topophone.app.ui.export

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.OBJECTS_MENU_TAG
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.points.ObjectListViewModel
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.points.SELECTION_EXPORT_TAG
import es.topophone.app.ui.projects.ProjectListActions
import es.topophone.app.ui.projects.ProjectListScreen
import es.topophone.app.ui.projects.ProjectListUiState
import es.topophone.app.ui.projects.projectMenuTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.csv.ColumnSeparator
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.DecimalSeparator
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.csv.ExportFormat
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Pantalla "Exportar CSV" y sus accesos (S012/RF-1, RF-2, RF-4, RF-5, RF-8, RF-10, RF-13, RF-14, RF-19). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ExportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = ExportActions(
        onFormat = { calls += "format:$it" },
        onSeparator = { calls += "separator:$it" },
        onDecimal = { calls += "decimal:$it" },
        onToggleColumn = { calls += "column:$it" },
        onSave = { calls += "save:$it" },
        onShare = { calls += "share:$it" },
    )

    private val base = ExportUiState(loaded = true, projectName = "Calle Mayor 12", pointCount = 12)

    private fun show(state: ExportUiState) {
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { ExportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) } }
    }

    @Test
    fun `S012 RF-5 y RF-13 se ve cuantos puntos van y se eligen formato, separador y signo decimal`() {
        show(base)

        compose.onNodeWithText("Se exportarán 12 puntos").assertIsDisplayed()
        compose.onNodeWithTag(formatTag(ExportFormat.PENZD)).performClick()
        compose.onNodeWithTag(separatorTag(ColumnSeparator.SEMICOLON)).performScrollTo().performClick()
        compose.onNodeWithTag(decimalTag(DecimalSeparator.COMMA)).performScrollTo().performClick()

        calls shouldBe listOf("format:PENZD", "separator:SEMICOLON", "decimal:COMMA")
    }

    @Test
    fun `S012 RF-10 sin PENZD ni PNEZD si el proyecto no los admite`() {
        show(base.copy(formats = listOf(ExportFormat.ALL, ExportFormat.CUSTOM)))

        compose.onNodeWithTag(formatTag(ExportFormat.PENZD)).assertDoesNotExist()
        compose.onNodeWithTag(formatTag(ExportFormat.PNEZD)).assertDoesNotExist()
    }

    @Test
    fun `S012 RF-8 con el personalizado se marcan las columnas`() {
        show(base.copy(options = CsvOptions(format = ExportFormat.CUSTOM, customColumns = setOf(ExportColumn.NAME))))

        compose.onNodeWithTag(columnTag(ExportColumn.LATITUDE)).performScrollTo().performClick()

        calls shouldBe listOf("column:LATITUDE")
        compose.onNodeWithText("Latitud").assertIsDisplayed()
    }

    @Test
    fun `S012 RF-14 coma con coma se avisa y no se puede guardar ni compartir`() {
        show(base.copy(options = CsvOptions(separator = ColumnSeparator.COMMA, decimal = DecimalSeparator.COMMA)))

        compose.onNodeWithText("El separador y el signo decimal no pueden ser los dos la coma.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(EXPORT_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(EXPORT_SHARE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S012 RF-4 sin puntos se dice y no se puede exportar`() {
        show(base.copy(pointCount = 0))

        compose.onNodeWithText("No hay puntos que exportar.").assertIsDisplayed()
        compose.onNodeWithTag(EXPORT_SAVE_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S012 RF-19 guardar y compartir llevan el nombre propuesto con el proyecto y el formato`() {
        show(base.copy(options = CsvOptions(format = ExportFormat.PENZD)))

        compose.onNodeWithTag(EXPORT_SAVE_TAG).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag(EXPORT_SHARE_TAG).performScrollTo().performClick()

        calls.size shouldBe 2
        calls[0] shouldStartWith "save:Calle Mayor 12_PENZD_"
        calls[1] shouldStartWith "share:Calle Mayor 12_PENZD_"
    }

    @Test
    fun `S012 RF-1 el menu de un proyecto ofrece Exportar CSV`() {
        var exported: String? = null
        val project = Project(
            id = "p1", name = "Calle Mayor 12", author = "", description = "", crs = ProjectCrs.ETRS89_UTM30, linearUnit = LinearUnit.METRE,
            angularUnit = AngularUnit.DMS, correctionsDatum = CorrectionsDatum.ETRS89, folderId = null, createdAtMs = 0, modifiedAtMs = 0,
            openedAtMs = null, deletedAtMs = null,
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onExport = {
                        exported =
                            it
                    }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Exportar CSV").performClick()

        exported shouldBe "p1"
    }

    @Test
    fun `S012 RF-1 y RF-2 la lista de objetos exporta todo desde su menu y la seleccion desde su barra`() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true, points = listOf(measured("1").summary()), total = 1, selected = setOf("id-1")),
                    ObjectListActions(onExport = { chosen += "all" }, onExportSelected = { chosen += "selected" }),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(SELECTION_EXPORT_TAG).performClick()
        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        compose.onNodeWithText("Exportar CSV").performClick()

        chosen shouldBe listOf("selected", "all")
    }

    @Test
    fun `S012 RF-2 exportar la seleccion la deja lista para la exportacion y la quita de la lista`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val selection = ExportSelection()
            val db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                TopoPhoneDatabase::class.java,
            ).build()
            val vm = ObjectListViewModel(FakePointRepository(listOf(measured("1"), measured("2"))), RoomProjectRepository(db), selection) {
                ProjectPositionCalculator()
            }
            vm.toggleSelection("id-1")
            vm.toggleSelection("id-2")

            vm.exportSelected()

            selection.ids shouldBe setOf("id-1", "id-2")
            vm.state.value.selected shouldBe emptySet()
            db.close()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
