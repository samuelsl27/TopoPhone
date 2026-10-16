package es.topophone.data.ntrip

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import es.topophone.core.model.ntrip.NtripProfile
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.model.ntrip.NtripProfileError
import es.topophone.core.model.ntrip.NtripProfileRules
import es.topophone.core.model.project.ProjectNames
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.secret.SealedSecret
import es.topophone.data.secret.SecretCipher
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Resultado de guardar un perfil NTRIP (S007/RF-3). */
sealed interface NtripSaveResult {
    data class Saved(val id: String) : NtripSaveResult

    data class Invalid(val errors: Set<NtripProfileError>) : NtripSaveResult
}

/** La contraseña guardada de un perfil: la hay, no la hay, o no se puede descifrar (S007/RF-6). */
sealed interface PasswordResult {
    data class Ok(val password: String) : PasswordResult {
        override fun toString(): String = "Ok(password=***)"
    }

    data object None : PasswordResult

    data object Undecryptable : PasswordResult
}

/** Perfiles NTRIP (S007/RF-1 a RF-10). La contraseña nunca sale de aquí salvo para conectar. */
interface NtripProfileRepository {
    fun profiles(): Flow<List<NtripProfile>>

    fun observe(id: String): Flow<NtripProfile?>

    suspend fun profile(id: String): NtripProfile?

    /** Contraseña vacía = sin contraseña. */
    suspend fun create(draft: NtripProfileDraft, password: String): NtripSaveResult

    /** [password] `null` deja la que hay; vacía la quita. */
    suspend fun update(id: String, draft: NtripProfileDraft, password: String?): NtripSaveResult

    suspend fun delete(id: String)

    suspend fun password(id: String): PasswordResult
}

@Dao
interface NtripProfileDao {
    @Insert
    suspend fun insert(profile: NtripProfileEntity)

    @Update
    suspend fun update(profile: NtripProfileEntity)

    @Query("DELETE FROM ntrip_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM ntrip_profiles")
    fun all(): Flow<List<NtripProfileEntity>>

    @Query("SELECT * FROM ntrip_profiles WHERE id = :id")
    fun observe(id: String): Flow<NtripProfileEntity?>

    @Query("SELECT * FROM ntrip_profiles WHERE id = :id")
    suspend fun byId(id: String): NtripProfileEntity?

    @Query("SELECT name FROM ntrip_profiles WHERE id != :exceptId")
    suspend fun names(exceptId: String = ""): List<String>
}

/**
 * Repositorio sobre Room. La contraseña se cifra antes de guardarla (RNF-T11); si el Keystore falla al
 * cifrar, el perfil se guarda sin ella y se pedirá al conectar, como cuando no se puede descifrar (S007/RF-6).
 */
class RoomNtripProfileRepository(
    private val db: TopoPhoneDatabase,
    private val cipher: SecretCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : NtripProfileRepository {

    private val dao = db.ntripProfiles()

    override fun profiles(): Flow<List<NtripProfile>> =
        dao.all().map { list -> list.map { it.toProfile() }.sortedBy { ProjectNames.key(it.draft.name) } }

    override fun observe(id: String): Flow<NtripProfile?> = dao.observe(id).map { it?.toProfile() }

    override suspend fun profile(id: String): NtripProfile? = dao.byId(id)?.toProfile()

    override suspend fun create(draft: NtripProfileDraft, password: String): NtripSaveResult {
        val sealed = seal(password)
        return db.withTransaction {
            val errors = NtripProfileRules.validate(draft, dao.names())
            if (errors.isNotEmpty()) return@withTransaction NtripSaveResult.Invalid(errors)
            val now = clock()
            val id = newId()
            dao.insert(draft.toEntity(id, sealed, createdAt = now, modifiedAt = now))
            NtripSaveResult.Saved(id)
        }
    }

    override suspend fun update(id: String, draft: NtripProfileDraft, password: String?): NtripSaveResult {
        val sealed = password?.let(::seal)
        return db.withTransaction {
            val current = dao.byId(id) ?: error("No existe el perfil $id")
            val errors = NtripProfileRules.validate(draft, dao.names(exceptId = id))
            if (errors.isNotEmpty()) return@withTransaction NtripSaveResult.Invalid(errors)
            val keep = current.passwordIv?.let { iv -> current.passwordCiphertext?.let { SealedSecret(iv, it) } }
            dao.update(draft.toEntity(id, if (password == null) keep else sealed, current.createdAt, clock()))
            NtripSaveResult.Saved(id)
        }
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun password(id: String): PasswordResult {
        val entity = dao.byId(id) ?: return PasswordResult.None
        val iv = entity.passwordIv ?: return PasswordResult.None
        val ciphertext = entity.passwordCiphertext ?: return PasswordResult.None
        return cipher.decrypt(SealedSecret(iv, ciphertext))?.let(PasswordResult::Ok) ?: PasswordResult.Undecryptable
    }

    private fun seal(password: String): SealedSecret? = if (password.isEmpty()) {
        null
    } else {
        try {
            cipher.encrypt(password)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: ProviderException) {
            null
        }
    }
}

private fun NtripProfileDraft.toEntity(id: String, password: SealedSecret?, createdAt: Long, modifiedAt: Long) = NtripProfileEntity(
    id = id,
    name = name.trim(),
    host = host.trim(),
    port = port,
    useTls = useTls,
    username = username,
    mountpoint = mountpoint.trim(),
    mountpointNeedsGga = mountpointNeedsGga,
    passwordIv = password?.iv,
    passwordCiphertext = password?.ciphertext,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
)

private fun NtripProfileEntity.toProfile() = NtripProfile(
    id = id,
    draft = NtripProfileDraft(name, host, port, useTls, username, mountpoint, mountpointNeedsGga),
    hasPassword = passwordCiphertext != null,
    createdAtMs = createdAt,
    modifiedAtMs = modifiedAt,
)
