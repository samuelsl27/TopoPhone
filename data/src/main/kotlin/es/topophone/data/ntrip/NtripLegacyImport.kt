package es.topophone.data.ntrip

import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.data.receiver.ReceiverPreferences

/**
 * Pasa los datos de caster que guardaba S002 a un perfil con el servidor como nombre, una sola vez
 * (S007/RF-10). Primero se crea el perfil y después se borran los datos antiguos: si la app se cierra
 * entre medias, la segunda vez el nombre ya existe y no se duplica.
 */
class NtripLegacyImport(private val preferences: ReceiverPreferences, private val repository: NtripProfileRepository) {

    suspend fun run() {
        val legacy = preferences.legacyNtrip()
        if (legacy != null) {
            val draft = NtripProfileDraft(
                name = legacy.host,
                host = legacy.host,
                port = legacy.port,
                username = legacy.username,
                mountpoint = legacy.mountpoint,
                mountpointNeedsGga = null,
            )
            repository.create(draft, password = "") // S002 nunca guardó la contraseña
        }
        preferences.markLegacyNtripImported()
    }
}
