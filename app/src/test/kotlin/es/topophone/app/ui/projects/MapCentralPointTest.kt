package es.topophone.app.ui.projects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.map.FakeMapController
import es.topophone.app.ui.map.fakeMap
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** El punto central en el mapa del proyecto, con un símbolo propio y su texto (S006/RF-36, RNF-T8). */
@RunWith(AndroidJUnit4::class)
class MapCentralPointTest {

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
    private val map = FakeMapController()

    @Test
    fun `S006 RF-36 el punto central se pinta en el mapa con su texto y desaparece al quitarlo`() {
        var current by mutableStateOf(project.copy(centralPoint = CentralPoint(40.4168123456, -3.7038123456)))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(ThemeState.DEFAULT, {}, ProjectViewUiState(current), onBack = {}, map = fakeMap(map))
            }
        }
        compose.waitForIdle()
        map.centralPoint shouldBe GeoPoint(40.4168123456, -3.7038123456)
        map.centralLabel shouldBe text(R.string.central_point_map_label)

        current = current.copy(centralPoint = null)
        compose.waitForIdle()
        map.centralPoint.shouldBeNull()
    }

    @Test
    fun `S006 RF-36 sin punto central no se pinta nada y la base sigue siendo otro simbolo`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project, base = GeoPoint(40.0, -3.0)),
                    onBack = {},
                    map = fakeMap(map),
                )
            }
        }
        compose.waitForIdle()

        map.centralPoint.shouldBeNull()
        map.base shouldBe GeoPoint(40.0, -3.0)
    }

    @Test
    fun `S006 RF-36 el punto central no se confunde con la base cuando estan los dos`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ProjectViewScreen(
                    ThemeState.DEFAULT,
                    {},
                    ProjectViewUiState(project.copy(centralPoint = CentralPoint(41.0, -2.0)), base = GeoPoint(40.0, -3.0)),
                    onBack = {},
                    map = fakeMap(map),
                )
            }
        }
        compose.waitForIdle()

        map.centralPoint shouldBe GeoPoint(41.0, -2.0)
        map.base shouldBe GeoPoint(40.0, -3.0)
    }
}
