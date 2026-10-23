package es.topophone.app.ui.collector

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.projects.ProjectViewScreen
import es.topophone.app.ui.projects.ProjectViewUiState
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.TiltGuide
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** El recolector con la inclinación: bloqueos, guía, enlace y altura del jalón (S016/RF-2, RF-12, RF-15, RF-18). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CollectorTiltPanelTest {

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

    private val ready = CollectorUiState(
        loaded = true,
        name = "P12",
        poleHeightText = "1,800",
        live = LiveQuality(SolutionStatus.FIX, 0.010, 0.015),
    )

    private var openedTilt = 0

    private fun show(state: CollectorUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project),
                    onBack = {},
                    map = fakeMap(FakeMapController()),
                    collector = state,
                    collectorActions = CollectorActions(onOpenTilt = { openedTilt++ }),
                )
            }
        }
        compose.onNodeWithTag(COLLECT_BUTTON_TAG).performClick()
    }

    @Test
    fun `S016 RF-2 sin modo lo dice y enlaza con Inclinacion`() {
        show(ready.copy(blocker = MeasureBlocker.TILT_MODE_MISSING))

        compose.onNodeWithTag(COLLECTOR_BLOCKER_TAG).assert(hasText(text(R.string.collector_tilt_mode_missing)))
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(COLLECTOR_OPEN_TILT_TAG).performClick()

        openedTilt shouldBe 1
    }

    @Test
    fun `S016 RF-18 sin estado 30 guia y enlaza con Inclinacion`() {
        show(ready.copy(blocker = MeasureBlocker.TILT_NOT_READY, tiltMode = TiltMode.APP, tiltGuide = TiltGuide.ALIGNMENT))

        compose.onNodeWithTag(TILT_GUIDE_TAG).assert(hasAnyDescendant(hasText(text(R.string.tilt_guide_alignment))))
        compose.onNodeWithTag(COLLECTOR_OPEN_TILT_TAG).assert(hasText(text(R.string.collector_open_tilt)))
        compose.onNodeWithTag(COLLECTOR_MEASURE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S016 RF-12 un aviso del ETC se guia sin bloquear ni enlazar`() {
        show(ready.copy(tiltMode = TiltMode.APP, tiltGuide = TiltGuide.FAST_MOTION))

        compose.onNodeWithTag(TILT_GUIDE_TAG).assert(hasAnyDescendant(hasText(text(R.string.tilt_guide_fast_motion))))
        compose.onNodeWithTag(COLLECTOR_OPEN_TILT_TAG).assertDoesNotExist()
    }

    @Test
    fun `S016 RF-15 en RECEIVER la altura es la del receptor`() {
        show(ready.copy(tiltMode = TiltMode.RECEIVER))

        compose.onNodeWithText(text(R.string.pole_height_receiver_label)).assertExists()
        compose.onNodeWithText(text(R.string.pole_height_receiver_help)).assertExists()
    }
}
