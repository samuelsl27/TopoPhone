package es.topophone.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.projects.FORM_AUTHOR_TAG
import es.topophone.app.ui.projects.angularOptionTag
import es.topophone.app.ui.projects.crsOptionTag
import es.topophone.app.ui.projects.datumOptionTag
import es.topophone.app.ui.projects.linearOptionTag
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.project.ProjectPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Valores por defecto de los proyectos nuevos en Ajustes (S006/RF-13). Interfaz en español (es-ES). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectDefaultsTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeProjectPreferences : ProjectPreferences {
        override val defaults = MutableStateFlow(ProjectDefaults())

        override suspend fun saveDefaults(defaults: ProjectDefaults) {
            this.defaults.value = defaults
        }
    }

    @Test
    fun `S006 RF-13 Ajustes ofrece los valores por defecto de los proyectos nuevos`() {
        compose.setContent { StatefulTestApp() }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()

        compose.onNodeWithTag(PROJECT_DEFAULTS_SETTING_TAG).performScrollTo().performClick()

        compose.onNodeWithTag(PROJECT_DEFAULTS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FORM_AUTHOR_TAG).assertIsDisplayed()
        compose.onNodeWithTag(crsOptionTag(ProjectCrs.ETRS89_UTM30)).performScrollTo().assertIsSelected()
    }

    @Test
    fun `S006 RF-13 cambiar autor, SC, unidades y datum avisa del valor nuevo`() {
        var changed = ProjectDefaults()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectDefaultsScreen(ThemeState.DEFAULT, {}, ProjectDefaults(), onChange = { changed = it }, onBack = {})
            }
        }

        compose.onNodeWithTag(FORM_AUTHOR_TAG).performTextReplacement("S. S.")
        changed.author shouldBe "S. S."
        compose.onNodeWithTag(crsOptionTag(ProjectCrs.ETRS89_UTM29)).performScrollTo().performClick()
        changed.crs shouldBe ProjectCrs.ETRS89_UTM29
        compose.onNodeWithTag(linearOptionTag(LinearUnit.INTERNATIONAL_FOOT)).performScrollTo().performClick()
        changed.linearUnit shouldBe LinearUnit.INTERNATIONAL_FOOT
        compose.onNodeWithTag(angularOptionTag(AngularUnit.DECIMAL_DEGREES)).performScrollTo().performClick()
        changed.angularUnit shouldBe AngularUnit.DECIMAL_DEGREES
        compose.onNodeWithTag(datumOptionTag(CorrectionsDatum.ITRF_WGS84)).performScrollTo().performClick()
        changed.correctionsDatum shouldBe CorrectionsDatum.ITRF_WGS84
    }

    @Test
    fun `S006 RF-13 el ViewModel guarda los valores elegidos`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val preferences = FakeProjectPreferences()
            val vm = ProjectDefaultsViewModel(preferences)
            val chosen = ProjectDefaults(author = "S. S.", crs = ProjectCrs.ETRS89_UTM31, linearUnit = LinearUnit.US_SURVEY_FOOT)

            vm.update(chosen)

            runBlocking {
                withTimeout(5_000) { preferences.defaults.first { it == chosen } }
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
                try {
                    withTimeout(5_000) { vm.state.first { it == chosen } } shouldBe chosen
                } finally {
                    collector.cancel()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
