package es.topophone.data.alignment

import androidx.room.withTransaction
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.AlignmentCodec
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.AlignmentSummary
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Un eje que se va a guardar (S049/RF-13): ya en el sistema y en los metros del proyecto, sin redondear. */
class AlignmentToSave(
    val name: String,
    val fileName: String?,
    val source: AlignmentSource,
    val elements: List<AlignmentElement>,
    val profile: VerticalProfile?,
    val extent: AlignmentExtent,
    val approximated: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Un eje tiene nombre" }
        require(elements.isNotEmpty()) { "Un eje tiene al menos un tramo" }
    }
}

/** Qué pasó al guardar ejes: los identificadores en el orden dado y, si el proyecto no tenía eje activo, cuál quedó activo (RF-14). */
data class AlignmentsAdded(val ids: List<String>, val activatedId: String?)

/**
 * Los ejes de un proyecto (S049). Importar, borrar, activar y guardar un punto sobre un eje son transacciones únicas con durabilidad
 * completa (RNF-T6). Cada proyecto tiene un eje activo o ninguno; el primero que se importa lo deja activo (RF-14). Borrar un eje lo quita
 * entero con su rasante, quita su marca de activo si la tenía y no toca ningún punto (RF-15).
 */
interface AlignmentRepository {
    /** Los ejes del proyecto por fecha de importación, sin sus tramos (RF-14, RF-19). */
    fun summaries(projectId: String): Flow<List<AlignmentSummary>>

    /** Los ejes del proyecto con sus tramos y su rasante, para dibujarlos y calcular sobre ellos. */
    fun alignments(projectId: String): Flow<List<Alignment>>

    suspend fun alignment(id: String): Alignment?

    /** Guarda todos los ejes de [items] en una sola transacción (RF-13, RNF-T6). */
    suspend fun add(projectId: String, items: List<AlignmentToSave>, importedAtMs: Long): AlignmentsAdded

    fun activeAlignmentId(projectId: String): Flow<String?>

    /** Hace activo el eje [alignmentId] del proyecto, o ninguno si es null. false si el eje no existe o no es de ese proyecto. */
    suspend fun activate(projectId: String, alignmentId: String?): Boolean

    /** Cambia el nombre. false si el nombre está en blanco o el eje no existe. */
    suspend fun rename(alignmentId: String, name: String): Boolean

    suspend fun setVisible(alignmentId: String, visible: Boolean)

    /** Borra el eje con su rasante; si era el activo el proyecto queda sin ninguno. No toca ningún punto (RF-15). */
    suspend fun delete(alignmentId: String)

    /** Pone, cambia o quita la rasante de un eje (RF-9). false si el eje no existe. */
    suspend fun setProfile(alignmentId: String, profile: VerticalProfile?): Boolean
}

class RoomAlignmentRepository(
    private val database: TopoPhoneDatabase,
    private val log: AppLog = AppLog.NONE,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AlignmentRepository {
    private val dao = database.alignments()

    override fun summaries(projectId: String): Flow<List<AlignmentSummary>> = dao.rows(projectId).map { rows ->
        rows.map { it.toSummary() }
    }

    override fun alignments(projectId: String): Flow<List<Alignment>> = dao.all(projectId).map { rows -> rows.map { it.toModel() } }

    override suspend fun alignment(id: String): Alignment? = dao.byId(id)?.toModel()

    override suspend fun add(projectId: String, items: List<AlignmentToSave>, importedAtMs: Long): AlignmentsAdded =
        database.withTransaction {
            val ids = items.map { item ->
                val id = newId()
                dao.insert(
                    AlignmentEntity(
                        id = id,
                        projectId = projectId,
                        name = item.name.trim(),
                        fileName = item.fileName,
                        importedAtMs = importedAtMs,
                        source = item.source.name,
                        startChainage = item.elements.first().startChainage,
                        lengthM = item.elements.sumOf { it.length },
                        visible = true,
                        minEasting = item.extent.minEasting,
                        maxEasting = item.extent.maxEasting,
                        minNorthing = item.extent.minNorthing,
                        maxNorthing = item.extent.maxNorthing,
                        approximated = item.approximated,
                        geometry = AlignmentCodec.encodeGeometry(item.elements),
                        profile = item.profile?.let { AlignmentCodec.encodeProfile(it) },
                    ),
                )
                id
            }
            val activated = if (ids.isNotEmpty() &&
                dao.activeId(projectId) == null
            ) {
                ids.first().also { dao.setActive(projectId, it) }
            } else {
                null
            }
            // S049/RF-16: el log lleva el recuento, nunca nombres ni coordenadas (S014/RF-5).
            if (ids.isNotEmpty()) log.info(LogCategory.APP, "Importados ${ids.size} ejes")
            AlignmentsAdded(ids, activated)
        }

    override fun activeAlignmentId(projectId: String): Flow<String?> = dao.activeAlignmentId(projectId)

    override suspend fun activate(projectId: String, alignmentId: String?): Boolean = database.withTransaction {
        if (alignmentId != null && dao.projectOf(alignmentId) != projectId) return@withTransaction false
        dao.setActive(projectId, alignmentId)
        true
    }

    override suspend fun rename(alignmentId: String, name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || dao.projectOf(alignmentId) == null) return false
        dao.rename(alignmentId, clean)
        return true
    }

    override suspend fun setVisible(alignmentId: String, visible: Boolean) = dao.setVisible(alignmentId, visible)

    override suspend fun delete(alignmentId: String) = database.withTransaction {
        dao.clearActive(alignmentId)
        dao.delete(alignmentId)
        log.info(LogCategory.APP, "Eje borrado")
    }

    override suspend fun setProfile(alignmentId: String, profile: VerticalProfile?): Boolean = database.withTransaction {
        if (dao.projectOf(alignmentId) == null) return@withTransaction false
        dao.setProfile(alignmentId, profile?.let { AlignmentCodec.encodeProfile(it) })
        true
    }
}

private fun AlignmentRow.toSummary() = AlignmentSummary(
    id = id,
    projectId = projectId,
    name = name,
    fileName = fileName,
    importedAtMs = importedAtMs,
    source = AlignmentSource.valueOf(source),
    startChainage = startChainage,
    lengthM = lengthM,
    visible = visible,
    hasProfile = hasProfile,
    approximated = approximated,
    extent = AlignmentExtent(minEasting, maxEasting, minNorthing, maxNorthing),
)

internal fun AlignmentEntity.toModel() = Alignment(
    id = id,
    projectId = projectId,
    name = name,
    fileName = fileName,
    importedAtMs = importedAtMs,
    source = AlignmentSource.valueOf(source),
    elements = AlignmentCodec.decodeGeometry(geometry),
    profile = profile?.let { AlignmentCodec.decodeProfile(it) },
    visible = visible,
    extent = AlignmentExtent(minEasting, maxEasting, minNorthing, maxNorthing),
    approximated = approximated,
)
