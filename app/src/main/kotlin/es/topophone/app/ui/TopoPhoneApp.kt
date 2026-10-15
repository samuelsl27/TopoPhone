package es.topophone.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
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
import es.topophone.app.ui.receiver.ReceiverRoute as ReceiverSectionRoute
import es.topophone.app.ui.sessions.SessionsSection
import es.topophone.app.ui.settings.AboutScreen
import es.topophone.app.ui.settings.BackgroundHelpScreen
import es.topophone.app.ui.settings.LicensesScreen
import es.topophone.app.ui.settings.SettingsScreen
import es.topophone.app.ui.status.StatusBarRoute
import es.topophone.app.ui.survey.SurveyScreen
import es.topophone.core.model.preferences.ThemeState

/**
 * Estructura de la app: barra inferior con las tres secciones y su contenido (S001/RF-2, S001/RF-3).
 * No guarda estado propio salvo el de navegación, que sobrevive al giro y a la muerte del proceso
 * (S001/RF-7, S001/RF-8).
 */
@Composable
fun TopoPhoneApp(
    uiState: AppUiState,
    actions: AppActions,
    navController: NavHostController = rememberNavController(),
    receiverSection: @Composable (ThemeState, () -> Unit, onOpenSessions: () -> Unit) -> Unit = { theme, toggleSun, openSessions ->
        ReceiverSectionRoute(theme, toggleSun, openSessions)
    },
    sessionsSection: @Composable (ThemeState, () -> Unit, onNavigateBack: () -> Unit) -> Unit = { theme, toggleSun, back ->
        SessionsSection(theme, toggleSun, back)
    },
    statusBar: @Composable (onOpenReceiver: () -> Unit) -> Unit = { onOpenReceiver -> StatusBarRoute(onOpenReceiver) },
    openReceiverRequest: Int = 0,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // S004/RF-9: la notificación del receptor abre Receptor, también con la app ya abierta en otra sección.
    LaunchedEffect(openReceiverRequest) {
        if (openReceiverRequest > 0) navController.navigateToTopLevel(TopLevelDestination.RECEIVER)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination.isIn(destination),
                        onClick = { navController.navigateToTopLevel(destination) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = {
                            // Con fuentes grandes "Levantamiento" no cabe en un tercio de 360 dp y es una sola
                            // palabra: se reduce lo justo para no recortarla (RNF de la spec 001).
                            Text(
                                stringResource(destination.label),
                                maxLines = 1,
                                autoSize = TextAutoSize.StepBased(
                                    minFontSize = 8.sp,
                                    maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                                    stepSize = 0.5.sp,
                                ),
                            )
                        },
                        modifier = Modifier.testTag(destination.testTag),
                    )
                }
            }
        },
    ) { padding ->
        val openReceiver = { navController.navigateToTopLevel(TopLevelDestination.RECEIVER) } // S003/RF-23
        CompositionLocalProvider(LocalStatusBar provides { statusBar(openReceiver) }) {
            AppNavHost(navController, uiState, actions, receiverSection, sessionsSection, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    uiState: AppUiState,
    actions: AppActions,
    receiverSection: @Composable (ThemeState, () -> Unit, () -> Unit) -> Unit,
    sessionsSection: @Composable (ThemeState, () -> Unit, () -> Unit) -> Unit,
    modifier: Modifier,
) {
    NavHost(navController = navController, startDestination = SurveyGraph, modifier = modifier) {
        navigation<SurveyGraph>(startDestination = SurveyRoute) {
            composable<SurveyRoute> { SurveyScreen(uiState.themeState, actions.onToggleSun) }
        }
        navigation<ReceiverGraph>(startDestination = ReceiverRoute) {
            composable<ReceiverRoute> {
                receiverSection(uiState.themeState, actions.onToggleSun) { navController.navigate(SessionsRoute) }
            }
            composable<SessionsRoute> {
                sessionsSection(uiState.themeState, actions.onToggleSun) { navController.popBackStack() } // S003/RF-29
            }
        }
        navigation<SettingsGraph>(startDestination = SettingsRoute) {
            composable<SettingsRoute> {
                SettingsScreen(
                    uiState,
                    actions,
                    onOpenAbout = { navController.navigate(AboutRoute) },
                    onOpenLicenses = { navController.navigate(LicensesRoute) },
                    onOpenBackgroundHelp = { navController.navigate(BackgroundHelpRoute) },
                )
            }
            composable<AboutRoute> { AboutScreen(uiState, actions, onNavigateBack = { navController.popBackStack() }) }
            composable<LicensesRoute> { LicensesScreen(uiState, actions, onNavigateBack = { navController.popBackStack() }) }
            composable<BackgroundHelpRoute> {
                BackgroundHelpScreen(uiState, actions, onNavigateBack = { navController.popBackStack() }) // S004/RF-12
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
