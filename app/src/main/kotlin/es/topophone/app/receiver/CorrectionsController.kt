package es.topophone.app.receiver

import es.topophone.core.model.ntrip.NtripProfile
import es.topophone.core.ntrip.NtripConfig
import es.topophone.data.ntrip.NtripProfileRepository
import es.topophone.data.ntrip.PasswordResult
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Qué pasó al pulsar un perfil (S007/RF-22). */
sealed interface ConnectResult {
    data object Started : ConnectResult

    /** La contraseña guardada no se puede descifrar: hay que escribirla de nuevo (S007/RF-6). */
    data object PasswordNeeded : ConnectResult

    data object ProfileNotFound : ConnectResult
}

/** Perfil de la conexión NTRIP en curso y, si falta su contraseña, el perfil que la necesita (S007/RF-6). */
data class CorrectionsStatus(val activeProfileId: String? = null, val passwordNeededFor: String? = null)

/**
 * Conexión NTRIP con perfiles: conectar con la contraseña guardada (S007/RF-22), detener, abrirla sola
 * al conectar el receptor si estaba abierta (S007/RF-26), volver a conectar al editar el perfil en uso
 * (S007/RF-9) y cerrarla al borrarlo (S007/RF-8). La reconexión tras un corte es de la sesión (S007/RF-23).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CorrectionsController(
    private val session: ReceiverSession,
    private val profiles: NtripProfileRepository,
    private val preferences: ReceiverPreferences,
    private val scope: CoroutineScope,
) {
    private val mutableStatus = MutableStateFlow(CorrectionsStatus())
    val status: StateFlow<CorrectionsStatus> = mutableStatus.asStateFlow()

    /** Un solo cambio de conexión a la vez: pulsar, editar y arrancar solo no se pisan. */
    private val mutex = Mutex()

    /** Fecha de modificación del perfil con el que se conectó, para saber si se ha editado (S007/RF-9). */
    @Volatile private var connectedVersion: Long? = null

    init {
        scope.launch { watchReceiver() }
        scope.launch { watchRejections() }
        scope.launch { watchActiveProfile() }
        scope.launch { preferences.correctionsAlarmS.collect(session::setCorrectionsAlarmThreshold) } // S007/RF-31
    }

    /** Conecta con el perfil, cerrando antes la conexión en curso; con [password] la guarda de nuevo (S007/RF-6). */
    suspend fun connect(profileId: String, password: String? = null): ConnectResult = mutex.withLock { connectLocked(profileId, password) }

    /** El usuario no quiere escribir la contraseña ahora (S007/RF-6). */
    fun dismissPasswordPrompt() {
        mutableStatus.update { it.copy(passwordNeededFor = null) }
    }

    /** El usuario detiene el NTRIP: no se volverá a abrir solo (S007/RF-26). */
    fun stop() {
        session.stopNtrip()
        val id = mutableStatus.value.activeProfileId
        connectedVersion = null
        mutableStatus.value = CorrectionsStatus()
        scope.launch { preferences.saveNtripSession(id, active = false) }
    }

    private suspend fun connectLocked(profileId: String, password: String?): ConnectResult {
        val saved = profiles.profile(profileId) ?: return ConnectResult.ProfileNotFound
        val secret = if (password != null) {
            profiles.update(profileId, saved.draft, password)
            password
        } else {
            when (val result = profiles.password(profileId)) {
                is PasswordResult.Ok -> result.password
                PasswordResult.None -> ""
                PasswordResult.Undecryptable -> {
                    mutableStatus.update { it.copy(passwordNeededFor = profileId) }
                    return ConnectResult.PasswordNeeded
                }
            }
        }
        val profile = profiles.profile(profileId) ?: return ConnectResult.ProfileNotFound
        connectedVersion = profile.modifiedAtMs
        mutableStatus.value = CorrectionsStatus(activeProfileId = profileId)
        session.startNtrip(profile.toConfig(secret)) // la sesión cierra antes la conexión en curso (S007/RF-22)
        preferences.saveNtripSession(profileId, active = true)
        return ConnectResult.Started
    }

    /** S007/RF-26: al pasar a recibir de un receptor real sin NTRIP, se abre si estaba abierta. */
    private suspend fun watchReceiver() {
        var linked = false
        session.state.map { it.connection.linked() && it.replay == null }.distinctUntilChanged().collect { now ->
            val justLinked = now && !linked
            linked = now
            if (justLinked && session.state.value.ntrip == NtripState.Idle) autostart()
        }
    }

    private suspend fun autostart() {
        if (!preferences.ntripWasActive.first()) return
        val id = preferences.lastNtripProfileId.first() ?: return
        mutex.withLock {
            // Si entretanto el usuario ya ha conectado un perfil, no se abre otra vez.
            val busy = mutableStatus.value.activeProfileId != null || session.state.value.ntrip != NtripState.Idle
            if (!busy && profiles.profile(id) != null) connectLocked(id, password = null)
        }
    }

    /** S007/RF-24, RF-28: lo que no se arregla reintentando tampoco se reintenta al conectar otra vez el receptor. */
    private suspend fun watchRejections() {
        session.state.map { it.ntrip }.distinctUntilChanged().collect { ntrip ->
            if (ntrip is NtripState.Stopped) preferences.saveNtripSession(mutableStatus.value.activeProfileId, active = false)
        }
    }

    /** S007/RF-8, RF-9: el perfil en uso se borra o se edita. */
    private suspend fun watchActiveProfile() {
        mutableStatus.map { it.activeProfileId }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) emptyFlow() else profiles.observe(id).map { id to it } }
            .collect { (id, profile) ->
                mutex.withLock {
                    if (mutableStatus.value.activeProfileId != id) return@withLock
                    if (profile == null) {
                        if (session.state.value.ntrip.open()) stop() else mutableStatus.value = CorrectionsStatus()
                    } else if (profile.modifiedAtMs != connectedVersion && session.state.value.ntrip.open()) {
                        connectLocked(id, password = null)
                    }
                }
            }
    }

    private fun ConnectionState.linked() = this is ConnectionState.Receiving || this is ConnectionState.NoData

    private fun NtripState.open() =
        this is NtripState.Connecting || this is NtripState.Streaming || this is NtripState.Reconnecting || this == NtripState.Paused

    private fun NtripProfile.toConfig(password: String) = NtripConfig(
        host = draft.host,
        port = draft.port,
        mountpoint = draft.mountpoint,
        username = draft.username,
        password = password,
        useTls = draft.useTls,
        sendGga = draft.mountpointNeedsGga ?: true, // S007/RF-21: si no se sabe, se envía
    )
}
