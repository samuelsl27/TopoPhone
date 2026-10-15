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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
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
import es.topophone.app.ui.projects.ProjectListRoute
import es.topophone.app.ui.projects.SurveyNavigation
import es.topophone.app.ui.projects.SurveyPage
import es.topophone.app.ui.receiver.ReceiverNavigation
import es.topophone.app.ui.receiver.ReceiverPage
import es.topophone.app.ui.receiver.ReceiverRoute as ReceiverSectionRoute
import es.topophone.app.ui.sessions.SessionsSection
import es.topophone.app.ui.settings.AboutScreen
import es.topophone.app.ui.settings.BackgroundHelpScreen
import es.topophone.app.ui.settings.LicensesScreen
import es.topophone.app.ui.settings.SettingsScreen
import es.topophone.app.ui.status.StatusIndicatorRoute
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
    receiverSection: @Composable (ReceiverPage, ThemeState, () -> Unit, ReceiverNavigation, ViewModelStoreOwner) -> Unit =
        { page, theme, toggleSun, navigation, owner ->
            ReceiverSectionRoute(page, theme, toggleSun, navigation, viewModel = hiltViewModel(owner))
        },
    sessionsSection: @Composable (ThemeState, () -> Unit, onNavigateBack: () -> Unit) -> Unit = { theme, toggleSun, back ->
        SessionsSection(theme, toggleSun, back)
    },
    statusIndicator: @Composable (onOpenReceiver: () -> Unit) -> Unit = { onOpenReceiver -> StatusIndicatorRoute(onOpenReceiver) },
    surveySection: @Composable (SurveyPage, ThemeState, () -> Unit, SurveyNavigation, ViewModelStoreOwner) -> Unit =
        { page, theme, toggleSun, navigation, owner ->
            when (page) {
                SurveyPage.List -> ProjectListRoute(theme, toggleSun, navigation, viewModel = hiltViewModel(owner))
            }
        },
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
        CompositionLocalProvider(LocalStatusIndicator provides { statusIndicator(openReceiver) }) {
            AppNavHost(navController, uiState, actions, receiverSection, sessionsSection, surveySection, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    uiState: AppUiState,
    actions: AppActions,
    receiverSection: @Composable (ReceiverPage, ThemeState, () -> Unit, ReceiverNavigation, ViewModelStoreOwner) -> Unit,
    sessionsSection: @Composable (ThemeState, () -> Unit, () -> Unit) -> Unit,
    surveySection: @Composable (SurveyPage, ThemeState, () -> Unit, SurveyNavigation, ViewModelStoreOwner) -> Unit,
    modifier: Modifier,
) {
    NavHost(navController = navController, startDestination = SurveyGraph, modifier = modifier) {
        navigation<SurveyGraph>(startDestination = SurveyRoute) {
            val surveyNavigation = SurveyNavigation(onBack = { navController.popBackStack() })
            composable<SurveyRoute> { entry ->
                surveySection(SurveyPage.List, uiState.themeState, actions.onToggleSun, surveyNavigation, entry) // S006/RF-1
            }
        }
        navigation<ReceiverGraph>(startDestination = ReceiverRoute) {
            val receiverNavigation = ReceiverNavigation(
                onOpen = { page -> navController.navigate(page.route()) }, // S005/RF-14
                onOpenSessions = { navController.navigate(SessionsRoute) },
                onBack = { navController.popBackStack() }, // S005/RF-15
            )
            val page: @Composable (NavBackStackEntry, ReceiverPage) -> Unit = { entry, page ->
                // El ReceiverViewModel es del grafo de Receptor: la raíz y sus pantallas comparten el estado.
                val owner = remember(entry) { navController.getBackStackEntry(ReceiverGraph) }
                receiverSection(page, uiState.themeState, actions.onToggleSun, receiverNavigation, owner)
            }
            composable<ReceiverRoute> { page(it, ReceiverPage.HOME) }
            composable<ReceiverStatusRoute> { page(it, ReceiverPage.STATUS) }
            composable<ReceiverCoordinatesRoute> { page(it, ReceiverPage.COORDINATES) }
            composable<ReceiverCorrectionsRoute> { page(it, ReceiverPage.CORRECTIONS) }
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

/** Ruta de cada pantalla de Receptor (S005/RF-14). */
private fun ReceiverPage.route(): Any = when (this) {
    ReceiverPage.HOME -> ReceiverRoute
    ReceiverPage.STATUS -> ReceiverStatusRoute
    ReceiverPage.COORDINATES -> ReceiverCoordinatesRoute
    ReceiverPage.CORRECTIONS -> ReceiverCorrectionsRoute
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
