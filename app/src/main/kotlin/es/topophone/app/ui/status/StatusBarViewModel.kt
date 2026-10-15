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

/** Resumen de la sesión para la barra de estado, a 5 Hz como máximo (RNF-T13). */
@OptIn(FlowPreview::class)
@HiltViewModel
class StatusBarViewModel @Inject constructor(session: ReceiverSession, @param:Named(ReceiverViewModel.UI_SAMPLE_MS) samplePeriodMs: Long) :
    ViewModel() {
    val state: StateFlow<StatusBarState> = (if (samplePeriodMs > 0) session.state.sample(samplePeriodMs) else session.state)
        .map(::toStatusBarState)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), toStatusBarState(session.state.value))

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Qué resume la barra de cada estado de la sesión (S003/RF-17 a RF-22). */
internal fun toStatusBarState(snapshot: ReceiverSnapshot): StatusBarState {
    val connection = snapshot.connection
    val connected =
        connection is ConnectionState.Connecting || connection is ConnectionState.Receiving || connection is ConnectionState.NoData
    if (!connected) return StatusBarState()
    val gga = snapshot.gga
    return StatusBarState(
        source = if (snapshot.replay != null) StatusSource.REPLAY else StatusSource.RECEIVER, // S003/RF-21
        stale = connection is ConnectionState.NoData || gga == null,
        solution = gga?.status,
        satellites = gga?.satellites,
        correctionAgeS = gga?.correctionAgeS,
        tilt = snapshot.epoch?.tilt?.state,
        recording = snapshot.recording,
    )
}

/** La barra de la app: un único ViewModel para la actividad, compartido por todas las secciones. */
@Composable
fun StatusBarRoute(onOpenReceiver: () -> Unit) {
    val owner = checkNotNull(LocalActivity.current as? ViewModelStoreOwner) { "La barra de estado necesita una actividad" }
    val viewModel: StatusBarViewModel = hiltViewModel(owner)
    val state by viewModel.state.collectAsStateWithLifecycle()
    StatusBar(state, onOpenReceiver)
}
