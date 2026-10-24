package es.topophone.app.ui.stakeout

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.export.ExportResult
import es.topophone.app.ui.points.OBJECTS_MENU_TAG
import es.topophone.app.ui.points.ObjectListActions
import es.topophone.app.ui.points.ObjectListScreen
import es.topophone.app.ui.points.ObjectListUiState
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.app.ui.points.PointFixtures.staked
import es.topophone.app.ui.points.SELECTION_STAKEOUT_REPORT_TAG
import es.topophone.app.ui.projects.ProjectListActions
import es.topophone.app.ui.projects.ProjectListScreen
import es.topophone.app.ui.projects.ProjectListUiState
import es.topophone.app.ui.projects.projectMenuTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.formats.csv.ColumnSeparator
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.DecimalSeparator
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.StakeoutReport
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla del informe de replanteo y sus accesos (S019/RF-17, RF-18, RF-20, RF-24, RF-26). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class StakeoutReportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = StakeoutReportActions(onSave = { calls += "save:$it" }, onShare = { calls += "share:$it" })

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

    /** El diseño 12 replanteado dos veces (el último, dentro) y el 13 una, de antes de S019 y con 5 cm y 19,9 cm de desviación. */
    private val points: List<Point> = listOf(
        staked(
            "12_stkd",
            designElevationM = 648.19,
            toleranceHM = 0.025,
            toleranceVM = 0.03,
            withinTolerance = false,
        ).copy(createdAtMs = 1_000),
        staked(
            "12_stkd2",
            designElevationM = 648.19,
            toleranceHM = 0.025,
            toleranceVM = 0.03,
            withinTolerance = true,
        ).copy(createdAtMs = 2_000),
        staked("13_stkd").let { base ->
            base.copy(
                staked = base.staked!!.copy(designId = "id-13", designName = "13", dHorizontalM = 0.05, dHeightM = 0.199),
                createdAtMs = 3_000,
            )
        },
    )

    private fun state(
        report: StakeoutReport = StakeoutReport.of(points),
        options: CsvOptions = CsvOptions(),
        result: ExportResult? = null,
        project: Project = this.project,
    ) = StakeoutReportUiState(
        loaded = true,
        projectName = project.name,
        report = report,
        options = options,
        result = result,
        project = project,
    )

    private fun show(state: StakeoutReportUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { StakeoutReportScreen(ThemeState.DEFAULT, {}, state, actions, onBack = {}) }
        }
    }

    @Test
    fun `S019 RF-17 el resumen cuenta los puntos de diseno y los que cumplen, solo con el ultimo replanteo de cada uno`() {
        show(state())

        compose.onNodeWithTag(STAKEOUT_REPORT_DESIGNS_TAG).assertTextEquals("Puntos de diseño replanteados: 2")
        compose.onNodeWithTag(STAKEOUT_REPORT_WITHIN_TAG).assertTextEquals("Dentro de la tolerancia: 1")
        compose.onNodeWithTag(STAKEOUT_REPORT_OUTSIDE_TAG).assertTextEquals("Fuera de la tolerancia: 0")
    }

    @Test
    fun `S019 RF-17 el resumen da las desviaciones horizontal y vertical maxima y media`() {
        show(state())

        compose.onNodeWithTag(STAKEOUT_REPORT_HORIZONTAL_TAG).assertTextEquals("Desviación horizontal: máxima 0,050 m, media 0,031 m")
        compose.onNodeWithTag(
            STAKEOUT_REPORT_VERTICAL_TAG,
        ).assertTextEquals("Desviación vertical: máxima 0,199 m, media 0,110 m (2 con cota)")
    }

    @Test
    fun `S019 RF-17 las desviaciones van en la unidad del proyecto`() {
        val feet = project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT)

        show(state(project = feet))

        compose.onNodeWithTag(STAKEOUT_REPORT_HORIZONTAL_TAG).assertTextEquals("Desviación horizontal: máxima 0,164 ft, media 0,103 ft")
    }

    @Test
    fun `S019 RF-26 los replanteos de antes salen como sin evaluar`() {
        show(state())

        compose.onNodeWithTag(STAKEOUT_REPORT_NOT_EVALUATED_TAG).assertTextEquals("Sin evaluar: 1")
    }

    @Test
    fun `S019 RF-26 sin replanteos de antes no hay linea de sin evaluar`() {
        show(state(report = StakeoutReport.of(points.take(2))))

        compose.onNodeWithTag(STAKEOUT_REPORT_NOT_EVALUATED_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_REPORT_WITHIN_TAG).assertTextEquals("Dentro de la tolerancia: 1")
    }

    @Test
    fun `S019 RF-17 sin replanteos con cota no hay linea de desviacion vertical`() {
        val noElevation = points.map { it.copy(staked = it.staked!!.copy(dHeightM = null, designElevationM = null)) }

        show(state(report = StakeoutReport.of(noElevation)))

        compose.onNodeWithTag(STAKEOUT_REPORT_VERTICAL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_REPORT_HORIZONTAL_TAG).assertIsDisplayed()
    }

    @Test
    fun `S019 RF-20 sin puntos replanteados lo dice y no se puede guardar ni compartir`() {
        show(state(report = StakeoutReport.of(listOf(measured("1")))))

        compose.onNodeWithTag(STAKEOUT_REPORT_NONE_TAG).assertTextEquals("No hay puntos replanteados")
        compose.onNodeWithTag(STAKEOUT_REPORT_DESIGNS_TAG).assertDoesNotExist()
        compose.onNodeWithTag(STAKEOUT_REPORT_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(STAKEOUT_REPORT_SHARE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S019 RF-24 Guardar y Compartir envian el nombre propuesto con el proyecto y replanteo`() {
        show(state())

        compose.onNodeWithTag(STAKEOUT_REPORT_SAVE_TAG).assertIsEnabled().performClick()
        compose.onNodeWithTag(STAKEOUT_REPORT_SHARE_TAG).assertIsEnabled().performClick()

        calls.size shouldBe 2
        calls[0] shouldContain "save:Calle Mayor 12_replanteo_"
        calls[0] shouldEndWith ".csv"
        calls[1] shouldContain "share:Calle Mayor 12_replanteo_"
    }

    @Test
    fun `S019 RF-24 la pantalla ensena el nombre propuesto`() {
        show(state())

        compose.onNodeWithTag(STAKEOUT_REPORT_FILE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S019 RF-24 tras guardar lo dice y si no se pudo escribir avisa de que no se ha exportado`() {
        show(state(result = ExportResult.SAVED))
        compose.onNodeWithText("Fichero guardado", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S019 RF-24 si no se pudo escribir el fichero lo dice`() {
        show(state(result = ExportResult.FAILED))
        compose.onNodeWithText("No se ha podido", substring = true).assertIsDisplayed()
    }

    @Test
    fun `S019 RF-23 con la coma de separador y de decimal avisa y no deja guardar`() {
        show(state(options = CsvOptions(separator = ColumnSeparator.COMMA, decimal = DecimalSeparator.COMMA)))

        compose.onNodeWithTag(STAKEOUT_REPORT_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(STAKEOUT_REPORT_INVALID_TAG).assertIsDisplayed()
    }

    @Test
    fun `S019 RF-17 el menu de un proyecto ofrece el informe de replanteo`() {
        var opened: String? = null
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectListUiState(projects = listOf(project)),
                    ProjectListActions(onStakeoutReport = { opened = it }),
                )
            }
        }

        compose.onNodeWithTag(projectMenuTag("p1")).performClick()
        compose.onNodeWithText("Informe de replanteo").performClick()

        opened shouldBe "p1"
    }

    @Test
    fun `S019 RF-17 y RF-18 la lista de objetos ofrece el informe de todo desde su menu y de la seleccion desde su barra`() {
        val chosen = mutableListOf<String>()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ObjectListScreen(
                    ThemeState.DEFAULT,
                    {},
                    ObjectListUiState(loaded = true, points = listOf(measured("1").summary()), total = 1, selected = setOf("id-1")),
                    ObjectListActions(
                        onStakeoutReport = { chosen += "all" },
                        onStakeoutReportSelected = { chosen += "selected" },
                    ),
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag(SELECTION_STAKEOUT_REPORT_TAG).performClick()
        compose.onNodeWithTag(OBJECTS_MENU_TAG).performClick()
        // La barra de la selección también tiene un botón con ese texto: aquí va el del menú.
        compose.onNode(hasText("Informe de replanteo").and(hasTestTag(SELECTION_STAKEOUT_REPORT_TAG).not())).performClick()

        chosen shouldBe listOf("selected", "all")
    }
}
