package es.topophone.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import es.topophone.app.ui.receiver.ReceiverScreen
import es.topophone.app.ui.settings.AboutScreen
import es.topophone.app.ui.settings.LicensesScreen
import es.topophone.app.ui.settings.SettingsScreen
import es.topophone.app.ui.survey.SurveyScreen

/**
 * Estructura de la app: barra inferior con las tres secciones y su contenido (S001/RF-2, S001/RF-3).
 * No guarda estado propio salvo el de navegación, que sobrevive al giro y a la muerte del proceso
 * (S001/RF-7, S001/RF-8).
 */
@Composable
fun TopoPhoneApp(uiState: AppUiState, actions: AppActions, navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination.isIn(destination),
                        onClick = { navController.navigateToTopLevel(destination) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                        modifier = Modifier.testTag(destination.testTag),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController = navController, startDestination = SurveyGraph, modifier = Modifier.padding(padding)) {
            navigation<SurveyGraph>(startDestination = SurveyRoute) {
                composable<SurveyRoute> { SurveyScreen(uiState.themeState, actions.onToggleSun) }
            }
            navigation<ReceiverGraph>(startDestination = ReceiverRoute) {
                composable<ReceiverRoute> { ReceiverScreen(uiState.themeState, actions.onToggleSun) }
            }
            navigation<SettingsGraph>(startDestination = SettingsRoute) {
                composable<SettingsRoute> {
                    SettingsScreen(
                        uiState,
                        actions,
                        onOpenAbout = { navController.navigate(AboutRoute) },
                        onOpenLicenses = { navController.navigate(LicensesRoute) },
                    )
                }
                composable<AboutRoute> { AboutScreen(uiState, actions, onNavigateBack = { navController.popBackStack() }) }
                composable<LicensesRoute> { LicensesScreen(uiState, actions, onNavigateBack = { navController.popBackStack() }) }
            }
        }
    }
}

private fun NavDestination?.isIn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.graph::class) } == true

/**
 * Patrón de varias pilas: volver a una sección restaura su pila, y "Atrás" desde Receptor
 * o Ajustes vuelve a Levantamiento (S001/RF-5).
 */
private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.graph) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
