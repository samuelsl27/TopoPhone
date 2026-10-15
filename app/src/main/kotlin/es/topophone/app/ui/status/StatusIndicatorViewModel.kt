package es.topophone.app.ui.status

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.ui.receiver.ReceiverViewModel
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn

/** Resumen de la sesión para el indicador de estado, a 5 Hz como máximo (RNF-T13). */
@OptIn(FlowPreview::class)
@HiltViewModel
class StatusIndicatorViewModel @Inject constructor(
    session: ReceiverSession,
    @param:Named(ReceiverViewModel.UI_SAMPLE_MS) samplePeriodMs: Long,
) : ViewModel() {
    val state: StateFlow<StatusIndicatorState> = (if (samplePeriodMs > 0) session.state.sample(samplePeriodMs) else session.state)
        .map(::toStatusIndicatorState)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), toStatusIndicatorState(session.state.value))

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Qué resume el indicador de cada estado de la sesión (S003/RF-17 a RF-21, S004/RF-23). La edad de
 * las correcciones, "Grabando" y la inclinación se ven en Receptor (S005/RF-19).
 */
internal fun toStatusIndicatorState(snapshot: ReceiverSnapshot): StatusIndicatorState {
    val connection = snapshot.connection
    val connected =
        connection is ConnectionState.Connecting ||
            connection is ConnectionState.Receiving ||
            connection is ConnectionState.NoData ||
            connection is ConnectionState.Reconnecting
    if (!connected) return StatusIndicatorState()
    val gga = snapshot.gga
    return StatusIndicatorState(
        source = if (snapshot.replay != null) StatusSource.REPLAY else StatusSource.RECEIVER, // S003/RF-21
        stale = connection is ConnectionState.NoData || (gga == null && connection !is ConnectionState.Reconnecting),
        reconnecting = connection is ConnectionState.Reconnecting, // S004/RF-23
        solution = gga?.status,
        satellites = gga?.satellites,
    )
}

/** El indicador de la app: un único ViewModel para la actividad, compartido por todas las secciones. */
@Composable
fun StatusIndicatorRoute(onOpenReceiver: () -> Unit) {
    val owner = checkNotNull(LocalActivity.current as? ViewModelStoreOwner) { "El indicador de estado necesita una actividad" }
    val viewModel: StatusIndicatorViewModel = hiltViewModel(owner)
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatusIndicator(state, onOpenReceiver)
}
