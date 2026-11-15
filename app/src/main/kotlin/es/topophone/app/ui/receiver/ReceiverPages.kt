package es.topophone.app.ui.receiver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.model.preferences.ThemeState

const val RECEIVER_STATUS_SCREEN_TAG = "receiver_status_screen"
const val RECEIVER_COORDINATES_SCREEN_TAG = "receiver_coordinates_screen"
const val RECEIVER_CORRECTIONS_SCREEN_TAG = "receiver_corrections_screen"
const val RECEIVER_TILT_SCREEN_TAG = "receiver_tilt_screen_page"
const val RECEIVER_GEAR_SCREEN_TAG = "receiver_gear_screen"
const val NO_RECEIVER_TAG = "receiver_no_receiver"
const val TILT_UNAVAILABLE_TAG = "receiver_tilt_unavailable"

/** Pantallas de la sección Receptor: la raíz y las del menú (S005/RF-14; S016/RF-1). Las sesiones grabadas son de S003. */
enum class ReceiverPage { HOME, STATUS, COORDINATES, GEAR, TILT, CORRECTIONS }

/** Lo que la sección Receptor necesita de la navegación de la app (S005/RF-14, RF-15; perfiles NTRIP, S007). */
class ReceiverNavigation(
    val onOpen: (ReceiverPage) -> Unit = {},
    val onOpenSessions: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onNewProfile: () -> Unit = {},
    val onEditProfile: (String) -> Unit = {},
    /** S021/RF-1, RF-4: el formulario de un receptor Wi-Fi. */
    val onNewWifiReceiver: () -> Unit = {},
    val onEditWifiReceiver: (String) -> Unit = {},
)

/** Pinta una pantalla de Receptor sin estado propio; las acciones de navegación ya vienen en [actions]. */
@Composable
fun ReceiverPageScreen(
    page: ReceiverPage,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    state: ReceiverUiState,
    actions: ReceiverActions,
    onBack: () -> Unit,
    corrections: CorrectionsUiState = CorrectionsUiState(),
    correctionsActions: CorrectionsActions = CorrectionsActions(),
    tilt: TiltUiState = TiltUiState(),
    tiltActions: TiltActions = TiltActions(),
    gear: GearUiState = GearUiState(),
    gearActions: GearActions = GearActions(),
) {
    when (page) {
        ReceiverPage.HOME -> ReceiverScreen(themeState, onToggleSun, state, actions)
        ReceiverPage.STATUS -> ChildScreen(
            R.string.receiver_menu_status,
            RECEIVER_STATUS_SCREEN_TAG,
            state,
            themeState,
            onToggleSun,
            onBack,
        ) {
            StatusCard(state.snapshot) // S002/RF-12 a RF-17, S003/RF-14, RF-15
            PositionCard(state) // S015/RF-1
            TiltStatusCard(state) // S016/RF-11
            SatellitesCard(state) // S015/RF-2, RF-3
            SkyplotCard(state) // S015/RF-4 a RF-6
            BaseCard(state, actions) // S015/RF-13 a RF-17
            SnrCard(state) // S015/RF-7 a RF-10
        }
        ReceiverPage.COORDINATES -> ChildScreen(
            R.string.receiver_menu_coordinates,
            RECEIVER_COORDINATES_SCREEN_TAG,
            state,
            themeState,
            onToggleSun,
            onBack,
        ) {
            CoordinatesCard(state, onSelectPole = actions.onSelectPole, onPoleHeightChange = actions.onPoleHeightChange)
        } // S002/RF-29 a RF-41; S046/RF-14
        ReceiverPage.GEAR -> ChildScreen(
            R.string.receiver_menu_gear,
            RECEIVER_GEAR_SCREEN_TAG,
            state,
            themeState,
            onToggleSun,
            onBack,
        ) { GearCard(gear, gearActions) } // S046
        ReceiverPage.TILT -> ChildScreen(
            R.string.receiver_menu_tilt,
            RECEIVER_TILT_SCREEN_TAG,
            state,
            themeState,
            onToggleSun,
            onBack,
        ) {
            if (state.snapshot.source.supportsTilt()) {
                TiltCard(tilt, tiltActions) // S016/RF-1 a RF-4
            } else {
                // S022/RF-9
                Text(stringResource(R.string.source_phone_no_tilt_explained), modifier = Modifier.testTag(TILT_UNAVAILABLE_TAG))
            }
        }
        ReceiverPage.CORRECTIONS -> CorrectionsScreen(themeState, onToggleSun, onBack, state, corrections, correctionsActions) // S007
    }
}

/**
 * Pantalla del menú del receptor con la flecha para volver (S005/RF-14, RF-15). Si la conexión
 * termina, sigue abierta y lo dice arriba (S005/RF-16).
 */
@Composable
private fun ChildScreen(
    title: Int,
    tag: String,
    state: ReceiverUiState,
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    SectionScaffold(title = stringResource(title), themeState = themeState, onToggleSun = onToggleSun, onNavigateBack = onBack) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).testTag(tag),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (receiverHomeMode(state.snapshot) == ReceiverHomeMode.LIST) {
                Text(
                    stringResource(R.string.receiver_no_receiver_connected),
                    modifier = Modifier.testTag(NO_RECEIVER_TAG),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            } // S005/RF-16
            content()
        }
    }
}
