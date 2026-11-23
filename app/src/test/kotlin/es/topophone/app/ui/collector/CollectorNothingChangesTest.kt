package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.field.FIELD_SHEET_HANDLE_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.PoleHeight
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Lo que no cambia con la hoja de campo: los valores por defecto, los rangos y los textos de cada acción del recolector, y que no queda
 * ningún aviso permanente de configuración (S060/RF-19, RF-32).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-w411dp-h900dp")
class CollectorNothingChangesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test
    fun `S060 RF-32 los valores por defecto del recolector son los de siempre`() {
        val defaults = CollectorSettings()

        defaults.mode shouldBe MeasurementMode.INSTANT // S010/RF-8
        defaults.averagingSeconds shouldBe 5 // S010/RF-9
        defaults.autoTrigger shouldBe AutoTrigger.TIME // S017/RF-15
        defaults.autoIntervalS shouldBe 5 // S017/RF-16
        defaults.autoDistanceM shouldBe 1.0 // S017/RF-17
        defaults.precision.admitted shouldBe AdmittedSolutions.FIX_ONLY // S047/RF-2
        defaults.precision.horizontalLimitM shouldBe null // S010/RF-13
        defaults.precision.verticalLimitM shouldBe null
        defaults.precision.maxTiltRad shouldBe Math.toRadians(60.0) // S016/RF-20
        PoleHeight.DEFAULT_M shouldBe 1.8 // S010/RF-5
    }

    @Test
    fun `S060 RF-32 los rangos del recolector son los de siempre`() {
        CollectorSettings.AVERAGING_SECONDS shouldBe 1..3600 // S017/RF-1
        CollectorSettings.AUTO_INTERVAL_SECONDS shouldBe 1..3600 // S017/RF-16
        CollectorSettings.AUTO_DISTANCE_M shouldBe 0.1..1000.0 // S017/RF-17
        PoleHeight.MAX_M shouldBe 10.0 // S010/RF-5
        PoleHeight.parse("10") shouldBe 10.0
        PoleHeight.parse("10,01") shouldBe null
    }

    @Test
    fun `S060 RF-32 los textos de las acciones del recolector son los de siempre`() {
        text(R.string.collector_measure) shouldBe "Medir"
        text(R.string.collector_cancel) shouldBe "Cancelar"
        text(R.string.collector_finish) shouldBe "Terminar"
        text(R.string.collector_auto_start) shouldBe "Iniciar"
        text(R.string.collector_auto_stop) shouldBe "Parar"
        text(R.string.pole_height_invalid) shouldBe "Escribe una altura entre 0 y 10 m."
        text(R.string.collector_seconds_invalid) shouldBe "De 0:01 a 60:00."
        text(R.string.collector_solution_lost) shouldBe "Solución degradada: no cuentan las épocas de un tipo peor o no admitido."
        text(R.string.collector_no_data) shouldBe "Sin datos del receptor: la medida se ha cancelado."
        text(R.string.collector_auto_interval_too_short) shouldBe "El intervalo no puede ser menor que el tiempo de promediado."
    }

    private val ready = CollectorUiState(
        loaded = true,
        name = "P1",
        poleHeightText = "1,800",
        live = LiveQuality(SolutionStatus.FIX, 0.012, 0.02),
    )

    private fun show(state: CollectorUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                Box(Modifier.fillMaxSize()) {
                    CollectorSheet(state, LinearUnit.METRE, CollectorActions(), onClose = {
                    }, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    @Test
    fun `S060 RF-19 estando en FIX con solo FIX no hay ningun aviso de configuracion ni pastilla`() {
        show(ready)

        compose.onAllNodesWithTag(ADMITTED_BADGE_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onAllNodesWithTag(COLLECTOR_BLOCKER_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onAllNodesWithTag(STATUS_NOTE_TAG).fetchSemanticsNodes().size shouldBe 0
        InfoPillKind.entries.forEach { compose.onAllNodesWithTag(infoPillTag(it)).fetchSemanticsNodes().size shouldBe 0 }
        // Ni plegado ni expandido sale el aviso permanente «No solo FIX» de S047.
        compose.onAllNodesWithTag(FIELD_SHEET_HANDLE_TAG).fetchSemanticsNodes().size shouldBe 1
        compose.onAllNodesWithTag(FIELD_SHEET_HANDLE_TAG)[0].performClick()
        compose.onAllNodesWithTag(ADMITTED_BADGE_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-19 con FLOAT admitido y en FIX la configuracion se ve como pastilla y no como aviso en rojo`() {
        show(
            ready.copy(
                settings = CollectorSettings(
                    precision = PrecisionControl(admitted = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT))),
                ),
            ),
        )

        compose.onAllNodesWithTag(infoPillTag(InfoPillKind.PRECISION)).fetchSemanticsNodes().size shouldBe 1
        compose.onAllNodesWithTag(ADMITTED_BADGE_TAG).fetchSemanticsNodes().size shouldBe 0
        compose.onAllNodesWithTag(STATUS_NOTE_TAG).fetchSemanticsNodes().size shouldBe 0
    }
}
