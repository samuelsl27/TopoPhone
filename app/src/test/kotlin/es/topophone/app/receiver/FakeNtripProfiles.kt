package es.topophone.app.receiver

import es.topophone.core.model.ntrip.NtripProfile
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.model.ntrip.NtripProfileRules
import es.topophone.data.ntrip.NtripProfileRepository
import es.topophone.data.ntrip.NtripSaveResult
import es.topophone.data.ntrip.PasswordResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Perfiles NTRIP en memoria con las mismas reglas que el repositorio de verdad; [undecryptable] simula
 * la clave perdida (S007/RF-6) y [updatePasswords] anota la contraseña de cada edición (null = sin cambio).
 */
internal class FakeNtripProfiles : NtripProfileRepository {
    val stored = MutableStateFlow<Map<String, NtripProfile>>(emptyMap())
    val passwords = mutableMapOf<String, String>()
    val undecryptable = mutableSetOf<String>()
    val updatePasswords = mutableListOf<String?>()
    private var now = 1_000L
    private var nextId = 0

    fun add(id: String, draft: NtripProfileDraft, password: String) {
        stored.value += id to NtripProfile(id, draft, password.isNotEmpty(), now, now)
        if (password.isNotEmpty()) passwords[id] = password
    }

    override fun profiles(): Flow<List<NtripProfile>> = stored.map { it.values.toList() }

    override fun observe(id: String): Flow<NtripProfile?> = stored.map { it[id] }

    override suspend fun profile(id: String): NtripProfile? = stored.value[id]

    override suspend fun create(draft: NtripProfileDraft, password: String): NtripSaveResult {
        val errors = NtripProfileRules.validate(draft, stored.value.values.map { it.draft.name })
        if (errors.isNotEmpty()) return NtripSaveResult.Invalid(errors)
        val id = "nuevo-${++nextId}"
        add(id, draft, password)
        return NtripSaveResult.Saved(id)
    }

    override suspend fun update(id: String, draft: NtripProfileDraft, password: String?): NtripSaveResult {
        val errors = NtripProfileRules.validate(draft, stored.value.values.filter { it.id != id }.map { it.draft.name })
        if (errors.isNotEmpty()) return NtripSaveResult.Invalid(errors)
        updatePasswords += password
        now += 1_000
        when {
            password == null -> Unit
            password.isEmpty() -> passwords -= id
            else -> {
                passwords[id] = password
                undecryptable -= id
            }
        }
        stored.value += id to NtripProfile(id, draft, passwords.containsKey(id), 1_000L, now)
        return NtripSaveResult.Saved(id)
    }

    override suspend fun delete(id: String) {
        stored.value -= id
        passwords -= id
    }

    override suspend fun password(id: String): PasswordResult = when {
        id in undecryptable -> PasswordResult.Undecryptable
        id in passwords -> PasswordResult.Ok(passwords.getValue(id))
        else -> PasswordResult.None
    }
}
