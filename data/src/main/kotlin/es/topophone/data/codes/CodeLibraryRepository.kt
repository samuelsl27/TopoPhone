package es.topophone.data.codes

import androidx.room.withTransaction
import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.LibraryNames
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.ProjectNames
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** Las bibliotecas que vienen con la app (S023/RF-3, plan D3); las pone `app`, que tiene los textos. */
fun interface DefaultLibrarySource {
    fun libraries(): List<CodeLibrary>

    companion object {
        val NONE = DefaultLibrarySource { emptyList() }
    }
}

/** Una fila de la lista de bibliotecas (S023/RF-1). */
data class LibrarySummary(val id: String, val name: String, val isDefault: Boolean, val codeCount: Int)

/** Cómo acabó una operación sobre una biblioteca (S023/RF-2, RF-3). */
sealed interface LibraryResult {
    data class Saved(val id: String) : LibraryResult

    data object Deleted : LibraryResult

    data class Invalid(val error: NameError) : LibraryResult

    /** Es una biblioteca por defecto: no se modifica ni se borra. */
    data object NotEditable : LibraryResult

    data object NotFound : LibraryResult
}

/**
 * Las bibliotecas de códigos (S023). Cada alta, edición, borrado o importación es una transacción única con durabilidad completa
 * (RNF-T6). Borrar una biblioteca nunca toca un proyecto ni un punto (RF-4): la copia de un proyecto es otra fila. El log lleva el hecho,
 * nunca nombres ni códigos.
 */
interface CodeLibraryRepository {
    /** Las por defecto primero, en su orden, y luego las del usuario por nombre (RF-1). */
    fun libraries(): Flow<List<LibrarySummary>>

    /** Una biblioteca con sus códigos, por defecto o del usuario; null si no existe. */
    fun library(id: String): Flow<CodeLibrary?>

    suspend fun create(name: String): LibraryResult

    suspend fun rename(id: String, name: String): LibraryResult

    /** Una copia editable con el nombre «N (copia)» y ids nuevos; vale para las por defecto (RF-3). */
    suspend fun duplicate(id: String, copyLabel: String): LibraryResult

    suspend fun delete(id: String): LibraryResult

    companion object {
        const val DEFAULT_PREFIX = "default:"

        fun isDefault(id: String) = id.startsWith(DEFAULT_PREFIX)
    }
}

class RoomCodeLibraryRepository(
    private val database: TopoPhoneDatabase,
    private val defaults: DefaultLibrarySource = DefaultLibrarySource.NONE,
    private val log: AppLog = AppLog.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : CodeLibraryRepository {
    private val dao = database.codeLibraries()

    private fun defaultLibrary(id: String): CodeLibrary? = defaults.libraries().firstOrNull { it.id == id }

    override fun libraries(): Flow<List<LibrarySummary>> = combine(dao.userLibraries(), dao.codeCounts()) { user, counts ->
        val byLibrary = counts.associate { it.libraryId to it.count }
        defaults.libraries().map { LibrarySummary(it.id, it.name, true, it.codes.size) } +
            user.map { LibrarySummary(it.id, it.name, false, byLibrary[it.id] ?: 0) }
    }

    override fun library(id: String): Flow<CodeLibrary?> {
        if (CodeLibraryRepository.isDefault(id)) return flowOf(defaultLibrary(id))
        val rows = combine(dao.observeCodes(id), dao.observeAttributes(id), dao.observeOptions(id), ::Triple)
        return combine(dao.observeLibrary(id), rows) { library, (codes, attributes, options) ->
            library?.let { assembleLibrary(it, codes, attributes, options) }
        }
    }

    override suspend fun create(name: String): LibraryResult = database.withTransaction {
        LibraryNames.validate(name, allNames())?.let { return@withTransaction LibraryResult.Invalid(it) }
        val id = newId()
        dao.insertLibrary(CodeLibraryEntity(id, name.trim(), CodeKey.of(name), null, null, clock()))
        log.info(LogCategory.APP, "Creada 1 biblioteca de códigos")
        LibraryResult.Saved(id)
    }

    override suspend fun rename(id: String, name: String): LibraryResult = database.withTransaction {
        if (CodeLibraryRepository.isDefault(id)) return@withTransaction LibraryResult.NotEditable
        val current = dao.library(id)
        if (current == null || current.projectId != null) return@withTransaction LibraryResult.NotFound
        LibraryNames.validate(name, allNames(exceptId = id))?.let { return@withTransaction LibraryResult.Invalid(it) }
        dao.rename(id, name.trim(), CodeKey.of(name))
        log.info(LogCategory.APP, "Renombrada 1 biblioteca de códigos")
        LibraryResult.Saved(id)
    }

    override suspend fun duplicate(id: String, copyLabel: String): LibraryResult = database.withTransaction {
        val source = if (CodeLibraryRepository.isDefault(id)) defaultLibrary(id) else loadUserLibrary(id)
        if (source == null) return@withTransaction LibraryResult.NotFound
        val copyId = newId()
        val name = ProjectNames.copyName(source.name, allNames(), copyLabel)
        insertTree(CodeLibraryEntity(copyId, name, CodeKey.of(name), null, null, clock()), source.codes)
        log.info(LogCategory.APP, "Duplicada 1 biblioteca de códigos")
        LibraryResult.Saved(copyId)
    }

    override suspend fun delete(id: String): LibraryResult = database.withTransaction {
        if (CodeLibraryRepository.isDefault(id)) return@withTransaction LibraryResult.NotEditable
        val current = dao.library(id)
        if (current == null || current.projectId != null) return@withTransaction LibraryResult.NotFound
        dao.deleteLibrary(id)
        log.info(LogCategory.APP, "Borrada 1 biblioteca de códigos")
        LibraryResult.Deleted
    }

    /** Los nombres que ya no se pueden usar: los de las por defecto y los del usuario, salvo la biblioteca [exceptId]. */
    private suspend fun allNames(exceptId: String = ""): List<String> =
        defaults.libraries().map { it.name } + dao.userLibraryNames(exceptId)

    internal suspend fun loadUserLibrary(id: String): CodeLibrary? {
        val library = dao.library(id) ?: return null
        return assembleLibrary(library, dao.codes(id), dao.attributes(id), dao.options(id))
    }

    /** Inserta [library] con [codes], cada uno con un id nuevo, dentro de la transacción que llama. */
    internal suspend fun insertTree(library: CodeLibraryEntity, codes: List<SurveyCode>) {
        dao.insertLibrary(library)
        for (code in codes) {
            val rows = code.copy(id = newId()).toRows(library.id, newId)
            dao.insertCode(rows.code)
            rows.attributes.forEach { dao.insertAttribute(it) }
            rows.options.forEach { dao.insertOption(it) }
        }
    }
}
