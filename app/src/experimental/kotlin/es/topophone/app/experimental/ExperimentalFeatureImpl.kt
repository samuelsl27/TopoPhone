package es.topophone.app.experimental

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import es.topophone.app.R
import es.topophone.app.ui.receiver.ListRow
import es.topophone.core.designsystem.component.StatusChip
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.model.preferences.ThemeState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch

const val EXPERIMENTAL_MARK_TAG = "experimental_mark"
const val EXPERIMENTAL_MENU_TAG = "experimental_menu"

/** Implementación real de las ranuras (S044/RF-1, RF-6, ADR-21); las demás pantallas llegan en T16 a T20. */
@Singleton
class ExperimentalFeatureImpl(
    private val preferences: ExperimentalPreferences,
    private val session: ExperimentalSession,
    private val now: () -> Long,
) : ExperimentalFeature {

    @Inject
    constructor(preferences: ExperimentalPreferences, session: ExperimentalSession) : this(preferences, session, System::currentTimeMillis)

    override val available: Boolean = true

    /** El módulo está activo ⇔ hay aceptación del aviso legal guardada (S044/RF-3). */
    @Composable
    private fun isActive(): Boolean = preferences.acceptedAtMs.collectAsState(initial = null).value != null

    @Composable
    override fun SettingsRow() {
        val acceptedAt = preferences.acceptedAtMs.collectAsState(initial = null).value
        val scope = rememberCoroutineScope()
        ExperimentalSettingsRow(
            active = acceptedAt != null,
            onAccept = { scope.launch { preferences.accept(now()) } },
            onDeactivate = { scope.launch { preferences.clear() } },
        )
    }

    /**
     * La marca «Experimental» y, con un receptor al que se pueda preguntar, la batería y los logs en directo o «Sin estado del receptor»,
     * mientras el módulo esté activo y la cabecera a la vista (S044/RF-6, RF-13, RF-17).
     */
    @Composable
    override fun ReceiverHeaderSlot() {
        if (!isActive()) return
        val source = session.source.collectAsState().value
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.Start, modifier = Modifier.fillMaxWidth()) {
                StatusChip(
                    stringResource(R.string.experimental_mark),
                    StatusTone.WARNING,
                    modifier = Modifier.testTag(EXPERIMENTAL_MARK_TAG),
                )
            }
            if (source is ReachSource.Ready) {
                DisposableEffect(session) {
                    session.startLive()
                    onDispose { session.stopLive() }
                }
                LiveStatusLines(session.live.collectAsState().value)
            }
        }
    }

    /** La entrada «Módulo experimental» del menú del receptor, mientras el módulo esté activo (S044/RF-6). */
    @Composable
    override fun ReceiverMenuEntry() {
        if (!isActive()) return
        val navigate = LocalExperimentalNavigation.current
        HorizontalDivider()
        ListRow(
            stringResource(R.string.experimental_settings_title),
            { navigate(ExperimentalPanelDestination) },
            Modifier.testTag(EXPERIMENTAL_MENU_TAG),
        ) { Text(stringResource(R.string.experimental_menu_summary)) }
    }

    /** «Buscar receptores» en la lista de receptores, mientras el módulo esté activo (S044/RF-7). */
    @Composable
    override fun ReceiverListSlot() {
        if (!isActive()) return
        val navigate = LocalExperimentalNavigation.current
        Card(modifier = Modifier.fillMaxWidth()) {
            ListRow(
                stringResource(R.string.experimental_discovery_title),
                { navigate(ExperimentalDiscoveryDestination) },
                Modifier.testTag(EXPERIMENTAL_SEARCH_TAG),
            ) { Text(stringResource(R.string.experimental_discovery_summary)) }
        }
    }

    override fun routes(builder: NavGraphBuilder, navController: NavController, themeState: ThemeState, onToggleSun: () -> Unit) {
        builder.composable<ExperimentalPanelDestination> {
            ExperimentalPanelRoute(
                themeState,
                onToggleSun,
                onBack = { navController.popBackStack() },
                onOpenConfig = { navController.navigate(ExperimentalConfigDestination) },
                onOpenAntenna = { navController.navigate(ExperimentalAntennaDestination) },
                onOpenLogs = { navController.navigate(ExperimentalLogsDestination) },
            )
        }
        builder.composable<ExperimentalDiscoveryDestination> {
            DiscoveryRoute(themeState, onToggleSun) { navController.popBackStack() }
        }
        builder.composable<ExperimentalLogsDestination> {
            LogsRoute(themeState, onToggleSun) { navController.popBackStack() }
        }
        builder.composable<ExperimentalAntennaDestination> {
            AntennaHeightRoute(themeState, onToggleSun) { navController.popBackStack() }
        }
        builder.composable<ExperimentalConfigDestination> {
            ReceiverConfigRoute(themeState, onToggleSun) { navController.popBackStack() }
        }
    }
}
