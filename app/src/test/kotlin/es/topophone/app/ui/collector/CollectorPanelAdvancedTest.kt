package es.topophone.app.ui.collector

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.AverageStats
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El panel del recolector con el promediado largo: tiempo, estadísticas, gráfico, "Terminar" y los cortes (S017/RF-1 a RF-10, RF-30). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorPanelAdvancedTest {

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
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private val averaged = CollectorSettings(MeasurementMode.AVERAGED, averagingSeconds = 600)

    private val ready = CollectorUiState(
        loaded = true,
        name = "P12",
        poleHeightText = "1,800",
        settings = averaged,
        averagingSecondsText = "10:00",
        live = LiveQuality(SolutionStatus.FIX, 0.010, 0.015),
    )

    private val measuring = ready.copy(measuring = true, progress = MeasureProgress(elapsedS = 125.0, requiredS = 600.0, 125, 3))

    private val calls = mutableListOf<String>()
    private val actions = CollectorActions(
        onSeconds = { calls += "seconds:$it" },
        onMeasure = { calls += "measure" },
        onCancel = { calls += "cancel" },
        onFinish = { calls += "finish" },
        onKeepWaiting = { calls += "wait" },
    )

    /** Las estadísticas de 250 épocas a 1 Hz en torno a una media, con una deriva lenta. */
    private fun stats(epochs: Int = 250): AverageStatsUi {
        val stats = AverageStats(epochPeriodS = 1.0)
        repeat(epochs) { stats.add(440_000.0 + (it % 5) * 0.002 + it * 1e-5, 4_474_000.0 + (it % 3) * 0.003, 650.0 + (it % 7) * 0.004) }
        return AverageStatsUi(checkNotNull(stats.snapshot()), stats.dispersion())
    }

    private fun show(state: CollectorUiState, collectorStats: AverageStatsUi? = null) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(FakeMapController()),
                    collector = state,
                    collectorActions = actions,
                    collectorStats = collectorStats,
                )
            }
        }
        compose.onNodeWithTag(COLLECT_BUTTON_TAG).performClick()
    }

    @Test
    fun `S017 RF-1 el tiempo se escribe en minutos y segundos y fuera de rango se dice el rango`() {
        show(ready.copy(averagingSecondsText = "1:30"))

        compose.onNodeWithTag(COLLECTOR_SECONDS_TAG).performScrollTo().assert(hasText("1:30")).performTextReplacement("2:00")
        calls shouldContainExactly listOf("seconds:2:00")
    }

    @Test
    fun `S017 RF-1 un tiempo fuera de 0 01 a 60 00 se rechaza y no se mide`() {
        show(ready.copy(averagingSecondsText = "60:01", averagingSecondsValid = false, blocker = MeasureBlocker.INVALID_SECONDS))

        compose.onNodeWithText("De 0:01 a 60:00.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S017 RF-2 durante el promediado se ve el tiempo reunido y las epocas usadas y descartadas`() {
        show(measuring)

        compose.onNodeWithText("Tiempo 2:05 de 10:00").assertIsDisplayed()
        compose.onNodeWithText("Épocas: 125 usadas, 3 descartadas").assertIsDisplayed()
    }

    @Test
    fun `S017 RF-3 y RF-4 las desviaciones tipicas y la estabilidad salen en la unidad del proyecto`() {
        show(measuring, stats())

        compose.onNodeWithTag(COLLECTOR_STATS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_DEVIATION_TAG).assert(hasText("Desviación típica: H ", substring = true))
        compose.onNodeWithTag(COLLECTOR_STABILITY_TAG).assert(hasText("Estabilidad (último minuto): planta ", substring = true))
    }

    @Test
    fun `S017 RF-3 y RF-4 sin datos todavia se dice Sin dato`() {
        show(measuring, stats(epochs = 1))

        compose.onNodeWithTag(COLLECTOR_DEVIATION_TAG).assert(hasText("Desviación típica: H Sin dato · V Sin dato"))
        compose.onNodeWithTag(COLLECTOR_STABILITY_TAG).assert(hasText("Estabilidad (último minuto): planta Sin dato · vertical Sin dato"))
    }

    @Test
    fun `S017 RF-5 el grafico de dispersion lleva su descripcion para TalkBack`() {
        show(measuring, stats())

        val description = compose.onNodeWithTag(
            COLLECTOR_PLOT_TAG,
        ).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        description.shouldBeDescribing(250)
    }

    private fun List<String>?.shouldBeDescribing(epochs: Int) {
        val text = this?.joinToString(" ").orEmpty()
        text shouldContain "Dispersión de $epochs épocas"
        text shouldContain "desviación típica horizontal"
    }

    @Test
    @Config(qualifiers = "+w360dp-h640dp")
    fun `S017 RF-6 en un movil pequeno Terminar y Cancelar se ven sin desplazar el panel y llaman a lo suyo`() {
        show(measuring, stats())

        compose.onNodeWithTag(COLLECTOR_FINISH_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(COLLECTOR_CANCEL_TAG).assertIsDisplayed().performClick()

        calls shouldContainExactly listOf("finish", "cancel")
    }

    @Test
    fun `S017 RF-6 en el modo instantaneo no hay Terminar`() {
        show(ready.copy(settings = CollectorSettings(MeasurementMode.INSTANT), measuring = true))

        compose.onNodeWithTag(COLLECTOR_FINISH_TAG).assertDoesNotExist()
        compose.onNodeWithTag(COLLECTOR_CANCEL_TAG).assertIsDisplayed()
    }

    @Test
    fun `S017 RF-7 Terminar sin epocas dice que aun no hay`() {
        show(measuring.copy(noEpochsYet = true))

        compose.onNodeWithText("Aún no hay épocas con las que terminar.").assertIsDisplayed()
    }

    @Test
    fun `S017 RF-8 una medida en pausa lo dice y sin corte no hay aviso`() {
        show(measuring.copy(paused = PauseState.PAUSED))

        compose.onNodeWithTag(COLLECTOR_PAUSED_TAG).assert(hasText("Medida en pausa", substring = true))
    }

    @Test
    fun `S017 RF-8 sin corte no hay aviso de pausa`() {
        show(measuring)

        compose.onNodeWithTag(COLLECTOR_PAUSED_TAG).assertDoesNotExist()
    }

    @Test
    fun `S017 RF-10 a los 5 min de corte se pregunta con tres opciones`() {
        show(measuring.copy(paused = PauseState.LONG_PAUSE))

        compose.onNodeWithText("Corte de más de 5 minutos").assertIsDisplayed()
        compose.onNodeWithTag(COLLECTOR_LONG_PAUSE_SAVE_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithTag(COLLECTOR_LONG_PAUSE_DISCARD_TAG).performClick()
        compose.onNodeWithTag(COLLECTOR_LONG_PAUSE_WAIT_TAG).performClick()

        calls shouldContainExactly listOf("finish", "cancel", "wait")
    }

    @Test
    fun `S017 RF-30 el reinicio por la base dice cuanto tiempo promediado se ha descartado`() {
        show(measuring.copy(restarted = true, restartedDiscardedS = 754.0))

        compose.onNodeWithTag(COLLECTOR_RESTARTED_TAG)
            .assert(hasText("La medida ha empezado de nuevo con la base nueva: se han descartado 12:34 de promediado."))
    }

    @Test
    fun `S017 RF-30 sin tiempo descartado el reinicio sigue diciendo lo de siempre`() {
        show(measuring.copy(restarted = true, restartedDiscardedS = null))

        compose.onNodeWithTag(COLLECTOR_RESTARTED_TAG).assert(hasText("La medida ha empezado de nuevo con la base nueva."))
    }

    @Test
    fun `S017 RNF-T8 TalkBack solo anuncia el cambio de minuto de un promediado de mas de un minuto`() {
        show(measuring)

        val announcement = compose.onNodeWithTag(COLLECTOR_ANNOUNCE_TAG).fetchSemanticsNode().config
        announcement.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") shouldBe "Promediando: 2 de 10 minutos"
        announcement.getOrNull(SemanticsProperties.LiveRegion).shouldNotBeNull()
    }

    @Test
    fun `S017 RNF-T8 un promediado de menos de un minuto no anuncia los minutos`() {
        show(ready.copy(measuring = true, progress = MeasureProgress(2.0, 30.0, 2, 0)))

        compose.onNodeWithTag(COLLECTOR_ANNOUNCE_TAG).assertDoesNotExist()
    }
}
