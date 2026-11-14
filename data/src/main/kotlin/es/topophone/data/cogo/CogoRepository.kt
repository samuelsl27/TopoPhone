package es.topophone.data.cogo

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.withTransaction
import es.topophone.core.formats.cogo.TraverseDraftJson
import es.topophone.core.model.cogo.TraverseDraft
import es.topophone.data.db.TopoPhoneDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface TraverseDraftDao {
    @Query("SELECT * FROM traverse_drafts WHERE project_id = :projectId")
    fun observe(projectId: String): Flow<TraverseDraftEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: TraverseDraftEntity)

    @Query("DELETE FROM traverse_drafts WHERE project_id = :projectId")
    suspend fun delete(projectId: String)
}

/** COGO en el proyecto: el borrador de la poligonal (S038/RF-26 a RF-30). */
interface CogoRepository {
    /** El borrador del proyecto, o null si no hay (o no se puede leer); se actualiza con cada cambio. */
    fun draft(projectId: String): Flow<TraverseDraft?>

    /** Guarda el borrador, sustituyendo el del proyecto, en una transacción; uno vacío borra el del proyecto (S038/RF-26). */
    suspend fun saveDraft(draft: TraverseDraft)

    /** Descarta el borrador del proyecto (S038/RF-27). */
    suspend fun clearDraft(projectId: String)
}

class RoomCogoRepository(private val database: TopoPhoneDatabase, private val clock: () -> Long = System::currentTimeMillis) :
    CogoRepository {
    private val drafts = database.traverseDrafts()

    override fun draft(projectId: String): Flow<TraverseDraft?> =
        drafts.observe(projectId).map { row -> row?.let { TraverseDraftJson.decode(it.data, it.projectId, it.updatedAt) } }

    override suspend fun saveDraft(draft: TraverseDraft) = database.withTransaction {
        if (draft.isEmpty) {
            drafts.delete(draft.projectId)
        } else {
            drafts.upsert(TraverseDraftEntity(draft.projectId, TraverseDraftJson.encode(draft), clock()))
        }
    }

    override suspend fun clearDraft(projectId: String) = drafts.delete(projectId)
}
