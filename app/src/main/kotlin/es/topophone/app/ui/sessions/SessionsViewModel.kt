package es.topophone.app.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.di.IoDispatcher
import es.topophone.receiver.session.ImportResult
import es.topophone.receiver.session.RecordedSession
import es.topophone.receiver.session.SessionLogStore
import es.topophone.receiver.session.SessionSource
import java.io.File
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Una sesión de la lista (S003/RF-29). */
data class SessionRow(
    val id: String,
    val startedAt: Instant,
    val durationS: Long?,
    val sizeBytes: Long,
    val receiver: String,
    val imported: Boolean,
    val inProgress: Boolean,
)

/** Por qué no se pudo importar un fichero (S003/RF-34). */
enum class ImportMessage { NO_NMEA, TOO_LARGE }

data class SessionsUiState(val rows: List<SessionRow> = emptyList(), val message: ImportMessage? = null)

/** Lista de sesiones grabadas e importadas, de la más reciente a la más antigua (S003/RF-29 a RF-31). */
@HiltViewModel
class SessionsViewModel @Inject constructor(private val store: SessionLogStore, @param:IoDispatcher private val io: CoroutineDispatcher) :
    ViewModel() {

    private val mutableState = MutableStateFlow(SessionsUiState(load()))
    val state: StateFlow<SessionsUiState> = mutableState.asStateFlow()

    /** Al volver a la pantalla: la sesión en curso crece y puede haber terminado. */
    fun refresh() {
        mutableState.update { it.copy(rows = load()) }
    }

    /** Importa un fichero NMEA en segundo plano y muestra el motivo si no se puede (S003/RF-33, S003/RF-34). */
    fun import(name: String, sizeHint: Long?, open: () -> InputStream?) {
        viewModelScope.launch(io) {
            val result = open()?.use { store.import(name, it, sizeHint) } ?: ImportResult.NoNmea
            val message = when (result) {
                is ImportResult.Imported -> null
                ImportResult.NoNmea -> ImportMessage.NO_NMEA
                ImportResult.TooLarge -> ImportMessage.TOO_LARGE
            }
            mutableState.value = SessionsUiState(load(), message)
        }
    }

    /** Borra una sesión que no esté en curso (S003/RF-31). */
    fun delete(id: String) {
        store.delete(id)
        mutableState.value = SessionsUiState(load())
    }

    /** Ficheros que se comparten de una sesión: los del receptor y las correcciones (S003/RF-30). */
    fun filesOf(id: String): List<File> = store.sessions().firstOrNull { it.id == id }?.dataFiles.orEmpty()

    private fun load(): List<SessionRow> {
        val active = store.activeSessionId()
        return store.sessions().map { it.toRow(inProgress = it.id == active) }
    }

    private fun RecordedSession.toRow(inProgress: Boolean) = SessionRow(
        id = id,
        startedAt = startedAt,
        durationS = endedAt?.let { Duration.between(startedAt, it).seconds },
        sizeBytes = sizeBytes,
        receiver = receiver,
        imported = source == SessionSource.IMPORTED,
        inProgress = inProgress,
    )
}
