package es.topophone.app.ui.collector

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La línea de estado y las pastillas informativas del recolector plegado (S060/RF-14, RF-17b, RF-18, RF-19). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES-w411dp-h640dp")
class CollectorStatusLineTest {

    @get:Rule
    val compose = createComposeRule()

    private val floatAllowed = AdmittedSolutions.of(setOf(SolutionType.FIX, SolutionType.FLOAT))
    private val clicked = mutableListOf<InfoPillKind>()

    // ---- Qué pastillas informativas salen (RF-14, RF-19) ----

    @Test
    fun `S060 RF-19 con los ajustes por defecto no hay ninguna pastilla informativa`() {
        InfoPills.kinds(PrecisionControl(), baseShiftName = null, localized = false) shouldBe emptyList()
    }

    @Test
    fun `S060 RF-14 admitir mas que FIX o poner un limite de precision muestra el control de precision`() {
        InfoPills.kinds(PrecisionControl(admitted = floatAllowed), null, false) shouldBe listOf(InfoPillKind.PRECISION)
        InfoPills.kinds(PrecisionControl(horizontalLimitM = 0.03), null, false) shouldBe listOf(InfoPillKind.PRECISION)
        InfoPills.kinds(PrecisionControl(verticalLimitM = 0.05), null, false) shouldBe listOf(InfoPillKind.PRECISION)
    }

    @Test
    fun `S060 RF-14 el base shift y la localizacion activos salen en ese orden tras el control de precision`() {
        InfoPills.kinds(PrecisionControl(admitted = floatAllowed), "Vértice 4", true) shouldBe
            listOf(InfoPillKind.PRECISION, InfoPillKind.BASE_SHIFT, InfoPillKind.LOCALIZATION)
        InfoPills.kinds(PrecisionControl(), "Vértice 4", false) shouldBe listOf(InfoPillKind.BASE_SHIFT)
        InfoPills.kinds(PrecisionControl(), null, true) shouldBe listOf(InfoPillKind.LOCALIZATION)
    }

    // ---- La línea de estado sin color de error (RF-17b) ----

    @Test
    fun `S060 RF-17b una solucion no admitida da la nota Esperando y no un bloqueo`() {
        val live = LiveQuality(SolutionStatus.FLOAT, 0.4, 0.6)

        StatusNotes.of(live, PrecisionControl()) shouldBe listOf(StatusNote.NotAdmitted(setOf(SolutionType.FIX)))
    }

    @Test
    fun `S060 RF-17b con la precision peor que el limite da la nota de precision con su limite`() {
        val live = LiveQuality(SolutionStatus.FIX, 0.045, 0.01)

        StatusNotes.of(live, PrecisionControl(horizontalLimitM = 0.03)) shouldBe
            listOf(StatusNote.PrecisionOver(vertical = false, valueM = 0.045, limitM = 0.03))
    }

    @Test
    fun `S060 RF-17b dentro de los limites o sin posicion no hay nota`() {
        StatusNotes.of(LiveQuality(SolutionStatus.FIX, 0.02, 0.02), PrecisionControl(horizontalLimitM = 0.03)) shouldBe emptyList()
        StatusNotes.of(LiveQuality(SolutionStatus.NONE, null, null), PrecisionControl()) shouldBe emptyList()
        StatusNotes.of(null, PrecisionControl()) shouldBe emptyList()
    }

    // ---- Lo que se ve ----

    private fun show(live: LiveQuality?, precision: PrecisionControl, baseShift: String? = null, localized: Boolean = false) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                androidx.compose.foundation.layout.Column {
                    StatusLine(live, LinearUnit.METRE, precision)
                    InfoPills(precision, baseShift, localized, LinearUnit.METRE) { clicked += it }
                }
            }
        }
    }

    @Test
    fun `S060 RF-8 la linea de estado dice la solucion y las precisiones en texto`() {
        show(LiveQuality(SolutionStatus.FIX, 0.012, 0.02), PrecisionControl())

        compose.onNodeWithTag(COLLECTOR_LIVE_TAG).assertTextContains("Fija", substring = true)
        compose.onNodeWithTag(COLLECTOR_LIVE_TAG).assertTextContains("H", substring = true)
    }

    @Test
    fun `S060 RF-19 con los ajustes por defecto no se dibuja ni una pastilla informativa ni la nota`() {
        show(LiveQuality(SolutionStatus.FIX, 0.012, 0.02), PrecisionControl())

        InfoPillKind.entries.forEach { compose.onAllNodesWithTag(infoPillTag(it)).fetchSemanticsNodes().size shouldBe 0 }
        compose.onAllNodesWithTag(STATUS_NOTE_TAG).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S060 RF-14 cada pastilla activa se ve con su texto y al tocarla avisa de a donde ir`() {
        show(LiveQuality(SolutionStatus.FIX, 0.012, 0.02), PrecisionControl(admitted = floatAllowed), "Vértice 4", true)

        InfoPillKind.entries.forEach { compose.onNodeWithTag(infoPillTag(it)).assertIsDisplayed() }
        compose.onNodeWithTag(infoPillTag(InfoPillKind.BASE_SHIFT)).assertTextContains("Vértice 4", substring = true)
        InfoPillKind.entries.forEach { compose.onNodeWithTag(infoPillTag(it)).performClick() }
        clicked shouldBe InfoPillKind.entries.toList()
    }

    @Test
    fun `S060 RF-17b la nota se ve como texto de la linea de estado`() {
        show(LiveQuality(SolutionStatus.FLOAT, 0.4, 0.6), PrecisionControl())

        compose.onNodeWithTag(STATUS_NOTE_TAG).assertIsDisplayed().assertTextContains("FIX", substring = true)
    }
}
