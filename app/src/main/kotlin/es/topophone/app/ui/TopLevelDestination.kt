package es.topophone.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import es.topophone.app.R
import kotlinx.serialization.Serializable

// Rutas tipadas (ADR-19). Cada sección es un grafo anidado para que conserve su propia pila.
@Serializable data object SurveyGraph

@Serializable data object SurveyRoute

@Serializable data object ReceiverGraph

@Serializable data object ReceiverRoute

@Serializable data object SessionsRoute

@Serializable data object SettingsGraph

@Serializable data object SettingsRoute

@Serializable data object AboutRoute

@Serializable data object LicensesRoute

@Serializable data object BackgroundHelpRoute

/** Las tres secciones de la navegación principal, en este orden (S001/RF-2). */
enum class TopLevelDestination(val graph: Any, @param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    SURVEY(SurveyGraph, R.string.nav_survey, R.drawable.ic_nav_survey),
    RECEIVER(ReceiverGraph, R.string.nav_receiver, R.drawable.ic_nav_receiver),
    SETTINGS(SettingsGraph, R.string.nav_settings, R.drawable.ic_nav_settings),
    ;

    val testTag: String get() = "nav_$name"
}
