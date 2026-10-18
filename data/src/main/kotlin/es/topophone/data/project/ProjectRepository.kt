package es.topophone.data.project

import androidx.room.withTransaction
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDraft
import es.topophone.core.model.project.ProjectFolder
import es.topophone.core.model.project.ProjectNames
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Resultado de guardar un proyecto (S006/RF-7, RF-12, RF-14). */
sealed interface SaveResult {
    data class Saved(val id: String) : SaveResult

    data class Invalid(val error: NameError) : SaveResult
}

/** Un proyecto encontrado y la carpeta en la que está (S006/RF-4). */
data class ProjectSearchResult(val project: Project, val folder: ProjectFolder?)

/** Un proyecto de la papelera y los días que le quedan antes de eliminarse (S006/RF-23). */
data class TrashedProject(val project: Project, val daysLeft: Int)

/** Proyectos y carpetas (S006). */
interface ProjectRepository {
    fun projects(folderId: String?): Flow<List<Project>>

    fun observe(id: String): Flow<Project?>

    suspend fun project(id: String): Project?

    suspend fun create(draft: ProjectDraft, folderId: String? = null): SaveResult

    suspend fun update(id: String, draft: ProjectDraft): SaveResult

    suspend fun markOpened(id: String)

    fun search(text: String): Flow<List<ProjectSearchResult>>

    /** [copyLabel] es la palabra de la interfaz: "copia" (S006/RF-16). */
    suspend fun duplicate(id: String, copyLabel: String): SaveResult

    fun folders(): Flow<List<ProjectFolder>>

    suspend fun createFolder(name: String): SaveResult

    suspend fun renameFolder(id: String, name: String): SaveResult

    suspend fun moveToFolder(projectId: String, folderId: String?)

    suspend fun deleteFolder(id: String)

    suspend fun moveToTrash(id: String)

    fun trash(): Flow<List<TrashedProject>>

    /** [restoredLabel] es la palabra de la interfaz: "restaurado" (S006/RF-25). */
    suspend fun restore(id: String, restoredLabel: String)

    suspend fun deleteForever(id: String)

    /** Elimina los que han cumplido los días de la papelera; devuelve cuántos (S006/RF-27). */
    suspend fun purgeTrash(): Int

    companion object {
        /** Días que un proyecto borrado se puede recuperar (S006/RF-22). */
        const val TRASH_DAYS = 30
    }
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

    override fun search(text: String): Flow<List<ProjectSearchResult>> = combine(dao.active(), dao.folders()) { projects, folders ->
        val byId = folders.associate { it.id to ProjectFolder(it.id, it.name) }
        projects
            .filter { ProjectNames.matches(it.name, text) || ProjectNames.matches(it.description, text) }
            .map { ProjectSearchResult(it.toProject(), it.folderId?.let(byId::get)) }
    }

    override suspend fun duplicate(id: String, copyLabel: String): SaveResult = db.withTransaction {
        val original = dao.byId(id) ?: error("No existe el proyecto $id")
        val now = clock()
        val copy = newId()
        val name = ProjectNames.copyName(original.name, dao.activeNames(), copyLabel)
        dao.insert(original.copy(id = copy, name = name, createdAt = now, modifiedAt = now, openedAt = null, deletedAt = null))
        SaveResult.Saved(copy)
    }

    override fun folders(): Flow<List<ProjectFolder>> =
        dao.folders().map { list -> list.map { ProjectFolder(it.id, it.name) }.sortedBy { ProjectNames.key(it.name) } }

    override suspend fun createFolder(name: String): SaveResult = db.withTransaction {
        ProjectNames.validate(name, dao.folderNames())?.let { return@withTransaction SaveResult.Invalid(it) }
        val id = newId()
        dao.insertFolder(ProjectFolderEntity(id, name.trim()))
        SaveResult.Saved(id)
    }

    override suspend fun renameFolder(id: String, name: String): SaveResult = db.withTransaction {
        ProjectNames.validate(name, dao.folderNames(exceptId = id))?.let { return@withTransaction SaveResult.Invalid(it) }
        dao.renameFolder(id, name.trim())
        SaveResult.Saved(id)
    }

    override suspend fun moveToFolder(projectId: String, folderId: String?) = dao.moveToFolder(projectId, folderId, clock())

    override suspend fun deleteFolder(id: String) = dao.deleteFolder(id)

    override suspend fun moveToTrash(id: String) = dao.setDeletedAt(id, clock())

    override fun trash(): Flow<List<TrashedProject>> = dao.trashed().map { list ->
        val now = clock()
        list.map { entity ->
            val daysIn = ((now - (entity.deletedAt ?: now)) / DAY_MS).toInt()
            TrashedProject(entity.toProject(), (ProjectRepository.TRASH_DAYS - daysIn).coerceAtLeast(0))
        }
    }

    override suspend fun restore(id: String, restoredLabel: String) = db.withTransaction {
        val project = dao.byId(id) ?: return@withTransaction
        // Si su carpeta ya no existe, la clave ajena ya dejó folder_id a null: vuelve fuera de las carpetas (S006/RF-24).
        dao.restore(id, ProjectNames.restoredName(project.name, dao.activeNames(exceptId = id), restoredLabel))
    }

    override suspend fun deleteForever(id: String) = dao.delete(id)

    override suspend fun purgeTrash(): Int = dao.purge(limit = clock() - ProjectRepository.TRASH_DAYS * DAY_MS)

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
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
    heightSystem = heightSystem.name,
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
    heightSystem = HeightSystem.entries.firstOrNull { it.name == heightSystem } ?: HeightSystem.ORTHOMETRIC,
)
