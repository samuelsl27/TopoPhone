package es.topophone.app.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.projects.PROJECT_LIST_TAG
import es.topophone.app.ui.receiver.CONNECTION_STATUS_TAG
import es.topophone.core.designsystem.component.EmptyState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Navegación principal (S001/RF-2, S001/RF-3, S001/RF-4). */
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S001 RF-2 al arrancar se muestra Levantamiento y la navegacion con tres secciones`() {
        compose.setContent { StatefulTestApp() }

        compose.navItem(TopLevelDestination.SURVEY).assertIsDisplayed().assertIsSelected()
        compose.navItem(TopLevelDestination.RECEIVER).assertIsDisplayed().assertIsNotSelected()
        compose.navItem(TopLevelDestination.SETTINGS).assertIsDisplayed().assertIsNotSelected()
        compose.onNodeWithTag(PROJECT_LIST_TAG).assertIsDisplayed()
    }

    @Test
    fun `S001 RF-2 las tres secciones se llaman Levantamiento, Receptor y Ajustes`() {
        compose.setContent { StatefulTestApp() }

        // El texto aparece también en el título de la barra superior, así que se comprueba la etiqueta de cada pestaña.
        mapOf(
            TopLevelDestination.SURVEY to "Levantamiento",
            TopLevelDestination.RECEIVER to "Receptor",
            TopLevelDestination.SETTINGS to "Ajustes",
        ).forEach { (destination, label) ->
            compose.navItem(destination).assert(hasText(label))
        }
    }

    @Test
    fun `S001 RF-3 elegir una seccion la muestra y la marca como activa`() {
        compose.setContent { StatefulTestApp() }

        compose.navItem(TopLevelDestination.RECEIVER).performClick()

        compose.navItem(TopLevelDestination.RECEIVER).assertIsSelected()
        compose.navItem(TopLevelDestination.SURVEY).assertIsNotSelected()
        compose.onNodeWithTag(CONNECTION_STATUS_TAG).assertIsDisplayed()

        compose.navItem(TopLevelDestination.SETTINGS).performClick()

        compose.navItem(TopLevelDestination.SETTINGS).assertIsSelected()
        compose.navItem(TopLevelDestination.RECEIVER).assertIsNotSelected()
    }

    @Test
    fun `S001 RF-4 las secciones sin funciones muestran su nombre y lo que contendran`() {
        // Desde S006 todas las secciones tienen funciones: se comprueba el estado vacío que usaría una nueva.
        compose.setContent { EmptyState(title = "Sección", description = "Lo que contendrá") }

        compose.onNodeWithText("Sección").assertIsDisplayed()
        compose.onNodeWithText("Lo que contendrá").assertIsDisplayed()
    }
}
