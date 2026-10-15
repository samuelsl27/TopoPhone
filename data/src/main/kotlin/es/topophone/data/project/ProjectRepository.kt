package es.topophone.data.project

import androidx.room.withTransaction
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDraft
import es.topophone.core.model.project.ProjectNames
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Resultado de guardar un proyecto (S006/RF-7, RF-12, RF-14). */
sealed interface SaveResult {
    data class Saved(val id: String) : SaveResult

    data class Invalid(val error: NameError) : SaveResult
}

/** Proyectos y carpetas (S006). */
interface ProjectRepository {
    fun projects(folderId: String?): Flow<List<Project>>

    fun observe(id: String): Flow<Project?>

    suspend fun project(id: String): Project?

    suspend fun create(draft: ProjectDraft, folderId: String? = null): SaveResult

    suspend fun update(id: String, draft: ProjectDraft): SaveResult

    suspend fun markOpened(id: String)
}

/**
 * Repositorio sobre Room. La unicidad del nombre se comprueba aquí, dentro de la transacción, porque
 * los proyectos de la papelera conservan el suyo (S006/RF-7, RF-25).
 */
class RoomProjectRepository(
    private val db: TopoPhoneDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ProjectRepository {

    private val dao = db.projects()

    override fun projects(folderId: String?): Flow<List<Project>> = dao.inFolder(folderId).map { list -> list.map { it.toProject() } }

    override fun observe(id: String): Flow<Project?> = dao.observe(id).map { it?.toProject() }

    override suspend fun project(id: String): Project? = dao.byId(id)?.toProject()

    override suspend fun create(draft: ProjectDraft, folderId: String?): SaveResult = db.withTransaction {
        ProjectNames.validate(draft.name, dao.activeNames())?.let { return@withTransaction SaveResult.Invalid(it) }
        val now = clock()
        val id = newId()
        dao.insert(draft.toEntity(id, folderId, createdAt = now, modifiedAt = now))
        SaveResult.Saved(id)
    }

    override suspend fun update(id: String, draft: ProjectDraft): SaveResult = db.withTransaction {
        val current = dao.byId(id) ?: error("No existe el proyecto $id")
        ProjectNames.validate(draft.name, dao.activeNames(exceptId = id))?.let { return@withTransaction SaveResult.Invalid(it) }
        // S006/RF-15: cambiar el SC solo cambia cómo se muestran las coordenadas; las medidas no se tocan.
        dao.update(draft.toEntity(id, current.folderId, current.createdAt, clock()).copy(openedAt = current.openedAt))
        SaveResult.Saved(id)
    }

    override suspend fun markOpened(id: String) = dao.setOpenedAt(id, clock())
}

private fun ProjectDraft.toEntity(id: String, folderId: String?, createdAt: Long, modifiedAt: Long) = ProjectEntity(
    id = id,
    name = name.trim(),
    author = author.trim(),
    description = description.trim(),
    crs = crs.name,
    linearUnit = linearUnit.name,
    angularUnit = angularUnit.name,
    correctionsDatum = correctionsDatum.name,
    folderId = folderId,
    createdAt = createdAt,
    modifiedAt = modifiedAt,
    openedAt = null,
    deletedAt = null,
)

internal fun ProjectEntity.toProject() = Project(
    id = id,
    name = name,
    author = author,
    description = description,
    crs = ProjectCrs.valueOf(crs),
    linearUnit = LinearUnit.valueOf(linearUnit),
    angularUnit = AngularUnit.valueOf(angularUnit),
    correctionsDatum = CorrectionsDatum.valueOf(correctionsDatum),
    folderId = folderId,
    createdAtMs = createdAt,
    modifiedAtMs = modifiedAt,
    openedAtMs = openedAt,
    deletedAtMs = deletedAt,
)
