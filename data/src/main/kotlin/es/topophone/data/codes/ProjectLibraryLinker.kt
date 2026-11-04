package es.topophone.data.codes

import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.codes.LibraryChoice
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID

/**
 * La biblioteca vinculada a un proyecto (S023/RF-19 a RF-21, plan D1 y D4): una copia propia del proyecto, como otra biblioteca con
 * `project_id`. Todo se hace dentro de la transacción de quien llama (guardar o duplicar el proyecto), así que el proyecto y su copia
 * cambian juntos o no cambian. Nunca toca un punto.
 */
class ProjectLibraryLinker(
    database: TopoPhoneDatabase,
    private val defaults: DefaultLibrarySource = DefaultLibrarySource.NONE,
    private val log: AppLog = AppLog.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.codeLibraries()

    /** Aplica [choice] al proyecto [projectId]: mantener no toca nada; una biblioteca que ya no existe tampoco (RF-21). */
    suspend fun apply(projectId: String, choice: LibraryChoice) {
        when (choice) {
            LibraryChoice.Keep -> Unit
            LibraryChoice.None -> remove(projectId)
            is LibraryChoice.Library -> link(projectId, choice.id)
        }
    }

    /** Da a [toProjectId] una copia de la copia de [fromProjectId], si tiene (al duplicar un proyecto). */
    suspend fun copyBetweenProjects(fromProjectId: String, toProjectId: String) {
        dao.copyOf(fromProjectId)?.let { copy ->
            dao.loadLibrary(copy.id)?.let { insertCopy(toProjectId, copy.sourceName ?: copy.name, it.codes, copy.createdAtMs) }
        }
        // S024: los codigos creados en el proyecto tambien viajan con el duplicado.
        dao.projectCodesOf(fromProjectId)?.let { own ->
            val codes = dao.loadLibrary(own.id)?.codes.orEmpty()
            val library = own.copy(id = newId(), projectCodesOf = toProjectId, createdAtMs = own.createdAtMs)
            dao.insertTree(library, codes, newId)
        }
    }

    private suspend fun remove(projectId: String) {
        val copy = dao.copyOf(projectId) ?: return
        dao.deleteLibrary(copy.id)
        log.info(LogCategory.APP, "Quitada 1 biblioteca vinculada")
    }

    private suspend fun link(projectId: String, libraryId: String) {
        val source = if (CodeLibraryRepository.isDefault(libraryId)) {
            defaults.libraries().firstOrNull { it.id == libraryId }
        } else {
            dao.library(libraryId)?.takeIf { it.projectId == null }?.let { dao.loadLibrary(it.id) }
        } ?: return
        dao.copyOf(projectId)?.let { dao.deleteLibrary(it.id) }
        insertCopy(projectId, source.name, source.codes, clock())
        log.info(LogCategory.APP, "Vinculada 1 biblioteca a un proyecto")
    }

    private suspend fun insertCopy(projectId: String, sourceName: String, codes: List<es.topophone.core.model.codes.SurveyCode>, at: Long) {
        val library = CodeLibraryEntity(newId(), sourceName, CodeKey.of(sourceName), projectId, sourceName, at)
        dao.insertTree(library, codes, newId)
    }
}
