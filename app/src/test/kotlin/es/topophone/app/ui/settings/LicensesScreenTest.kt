package es.topophone.app.ui.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mikepenz.aboutlibraries.Libs
import es.topophone.app.R
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.core.designsystem.component.NAVIGATE_BACK_TAG
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Licencias y atribuciones" (S001/RF-23, S002/RF-45). */
@RunWith(AndroidJUnit4::class)
class LicensesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val libs: Libs by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val json = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
        Libs.Builder().withJson(json).build()
    }

    @Test
    fun `S001 RF-23 la lista incluye las dependencias directas e indirectas del APK`() {
        val ids = libs.libraries.map { it.uniqueId }

        ids shouldContainAll listOf(
            "com.google.dagger:hilt-android", // directa
            "androidx.navigation:navigation-compose", // directa
            "androidx.collection:collection", // indirecta (Compose)
            "com.squareup.okio:okio", // indirecta (DataStore)
        )
    }

    @Test
    fun `S009 RNF-T14 MapLibre lleva su licencia y los avisos de los componentes de terceros que incluye`() {
        val maplibre = libs.libraries.single { it.uniqueId == "org.maplibre.gl:android-sdk-opengl" }

        maplibre.licenses.map { it.spdxId } shouldContain "BSD-2-Clause"
        val notices = maplibre.licenses.joinToString(" ") { it.licenseContent.orEmpty() }
        notices shouldContain "FreeType"
        notices shouldContain "RapidJSON"
        notices shouldContain "Boost Software License"
    }

    @Test
    fun `S001 RF-23 cada componente tiene su licencia`() {
        libs.libraries.filter { it.licenses.isEmpty() }.map { it.uniqueId } shouldBe emptyList()
        libs.libraries.flatMap { it.licenses }.shouldNotBeEmpty()
    }

    @Test
    fun `S001 RF-23 la pantalla muestra los componentes y se vuelve a Ajustes`() {
        compose.setContent { StatefulTestApp() }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(LICENSES_SETTING_TAG).performScrollTo().performClick()

        // La lista se carga en segundo plano: tantas filas como componentes del APK, ordenadas por nombre.
        val firstName = libs.libraries.minBy { it.name.lowercase() }.name
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(firstName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(LICENSES_LIST_TAG)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher("una fila por componente y la cabecera de datos") {
                    // + 1: la cabecera con las atribuciones de datos (S002/RF-45).
                    it.config.getOrNull(SemanticsProperties.CollectionInfo)?.rowCount ==
                        libs.libraries.size + 1
                },
            )

        compose.onNodeWithTag(NAVIGATE_BACK_TAG).performClick()
        compose.onNodeWithTag(LICENSES_SETTING_TAG).assertIsDisplayed()
    }

    @Test
    fun `S002 RF-45 se muestra la atribucion del geoide EGM08-REDNAP del IGN con su licencia`() {
        var opened: String? = null
        compose.setContent { StatefulTestApp(onOpenUrl = { opened = it }) }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()
        compose.onNodeWithTag(LICENSES_SETTING_TAG).performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithTag(GEOID_ATTRIBUTION_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(GEOID_ATTRIBUTION_TAG)
            .assertIsDisplayed()
            .assert(hasText("EGM08-REDNAP", substring = true))
            .assert(hasText("© Instituto Geográfico Nacional", substring = true))
            .assert(hasText("CC BY 4.0", substring = true))
        compose.onNodeWithTag(CC_BY_LINK_TAG).performClick()

        opened shouldBe CC_BY_4_URL
    }
}
