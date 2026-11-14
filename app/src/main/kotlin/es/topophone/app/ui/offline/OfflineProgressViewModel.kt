package es.topophone.app.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** La pantalla de la descarga de una zona: el estado del descargador y sus tres órdenes (S037/RF-15, RF-16). */
@HiltViewModel
class OfflineProgressViewModel @Inject constructor(private val downloader: OfflineDownloader) : ViewModel() {
    val state: StateFlow<DownloadState> = downloader.state

    fun pause() {
        viewModelScope.launch { downloader.pause() }
    }

    /** Sigue con la zona que se estaba bajando; es también el «Reintentar» de las fuentes incompletas (RF-19). */
    fun resume() {
        val zoneId = state.value.zoneId ?: return
        viewModelScope.launch { downloader.resume(zoneId) }
    }

    /** Detiene la descarga y borra la zona entera (RF-16); la pantalla pide antes confirmación. */
    fun cancel() {
        viewModelScope.launch { downloader.cancel() }
    }
}
