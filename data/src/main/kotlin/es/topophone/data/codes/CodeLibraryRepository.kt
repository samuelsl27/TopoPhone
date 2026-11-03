package es.topophone.data.codes

import androidx.room.withTransaction
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.CodeMerge
import es.topophone.core.model.codes.CodeValidator
import es.topophone.core.model.codes.DuplicatePolicy
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

/** Cómo acabó guardar o borrar un código (S023/RF-5 a RF-7, RF-9, RF-10). */
sealed interface CodeSaveResult {
    data class Saved(val id: String) : CodeSaveResult

    data object Deleted : CodeSaveResult

    /** No se guardó nada: el primer motivo (código vacío, largo, repetido, atributos). */
    data class Invalid(val error: CodeError) : CodeSaveResult

    /** La biblioteca es una por defecto o la copia de un proyecto: no se modifica. */
    data object NotEditable : CodeSaveResult

    data object NotFound : CodeSaveResult
}

/** Adónde van los códigos importados (S023/RF-14): una biblioteca nueva con ese nombre base o una del usuario. */
sealed interface ImportTarget {
    data class NewLibrary(val baseName: String) : ImportTarget

    data class Existing(val libraryId: String) : ImportTarget
}

/** Cómo acabó una importación (S023/RF-14 a RF-16): todo o nada. */
sealed interface CodeImportResult {
    /** [added] y [replaced] son los códigos que entraron; [skipped], los del fichero que no. */
    data class Imported(val libraryId: String, val added: Int, val replaced: Int, val skipped: Int) : CodeImportResult {
        val entered: Int get() = added + replaced
    }

    /** Un código no vale y no se importó nada. */
    data class Rejected(val code: String, val error: CodeError) : CodeImportResult

    data object NotEditable : CodeImportResult

    data object NotFound : CodeImportResult
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

    /** Añade el código, o sustituye el que tenga el mismo id (también sus atributos), en una transacción (RF-5, RF-6, RF-7, RF-9). */
    suspend fun saveCode(libraryId: String, code: SurveyCode): CodeSaveResult

    suspend fun deleteCode(libraryId: String, codeId: String): CodeSaveResult

    /** Pone los atributos del código en el orden [order] (posiciones actuales); si no es una permutación no hace nada (RF-10). */
    suspend fun reorderAttributes(libraryId: String, codeId: String, order: List<Int>): CodeSaveResult

    /**
     * Importa [incoming] (ya leídos del CSV) en [target] aplicando [policy] a los repetidos (RF-16). Se hace entera o no se hace (RF-14).
     */
    suspend fun importCodes(target: ImportTarget, incoming: List<SurveyCode>, policy: DuplicatePolicy): CodeImportResult

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

    override suspend fun saveCode(libraryId: String, code: SurveyCode): CodeSaveResult = database.withTransaction {
        editable(libraryId)?.let { return@withTransaction it }
        val id = code.id.ifBlank { newId() }
        val others = loadUserLibrary(libraryId)!!.codes.filter { it.id != id }
        CodeValidator.validate(code.code, code.attributes, others)?.let { return@withTransaction CodeSaveResult.Invalid(it) }
        val rows = code.copy(id = id).toRows(libraryId, newId)
        if (dao.code(id) == null) {
            dao.insertCode(rows.code)
        } else {
            dao.updateCode(rows.code)
            dao.deleteAttributesOf(id)
        }
        rows.attributes.forEach { dao.insertAttribute(it) }
        rows.options.forEach { dao.insertOption(it) }
        log.info(LogCategory.APP, "Guardado 1 código de biblioteca")
        CodeSaveResult.Saved(id)
    }

    override suspend fun deleteCode(libraryId: String, codeId: String): CodeSaveResult = database.withTransaction {
        editable(libraryId)?.let { return@withTransaction it }
        if (dao.code(codeId)?.libraryId != libraryId) return@withTransaction CodeSaveResult.NotFound
        dao.deleteCode(codeId)
        log.info(LogCategory.APP, "Borrado 1 código de biblioteca")
        CodeSaveResult.Deleted
    }

    override suspend fun reorderAttributes(libraryId: String, codeId: String, order: List<Int>): CodeSaveResult = database.withTransaction {
        editable(libraryId)?.let { return@withTransaction it }
        val code = loadUserLibrary(libraryId)!!.codes.firstOrNull { it.id == codeId } ?: return@withTransaction CodeSaveResult.NotFound
        if (order.sorted() != code.attributes.indices.toList()) return@withTransaction CodeSaveResult.Saved(codeId)
        saveCode(libraryId, code.copy(attributes = order.map { code.attributes[it] }))
    }

    override suspend fun importCodes(target: ImportTarget, incoming: List<SurveyCode>, policy: DuplicatePolicy): CodeImportResult =
        database.withTransaction {
            incoming.forEach { code ->
                CodeValidator.validate(code.code, code.attributes, emptyList())?.let {
                    return@withTransaction CodeImportResult.Rejected(code.code, it)
                }
            }
            val libraryId: String
            val existing: List<SurveyCode>
            when (target) {
                is ImportTarget.NewLibrary -> {
                    libraryId = newId()
                    existing = emptyList()
                    val name = LibraryNames.uniqueName(target.baseName, allNames())
                    dao.insertLibrary(CodeLibraryEntity(libraryId, name, CodeKey.of(name), null, null, clock()))
                }
                is ImportTarget.Existing -> {
                    editable(target.libraryId)?.let {
                        return@withTransaction if (it ==
                            CodeSaveResult.NotFound
                        ) {
                            CodeImportResult.NotFound
                        } else {
                            CodeImportResult.NotEditable
                        }
                    }
                    libraryId = target.libraryId
                    existing = loadUserLibrary(libraryId)!!.codes
                }
            }
            val plan = CodeMerge.plan(existing, incoming, policy)
            val rows = (plan.toAdd.map { it.copy(id = newId()) } + plan.toReplace).map { it.toRows(libraryId, newId) }
            plan.toReplace.forEach { replaced ->
                dao.deleteAttributesOf(replaced.id)
                dao.updateCode(rows.first { it.code.id == replaced.id }.code)
            }
            val replacedIds = plan.toReplace.mapTo(HashSet()) { it.id }
            dao.insertCodes(rows.map { it.code }.filter { it.id !in replacedIds })
            dao.insertAttributes(rows.flatMap { it.attributes })
            dao.insertOptions(rows.flatMap { it.options })
            log.info(LogCategory.APP, "Importados ${plan.toAdd.size + plan.toReplace.size} códigos de biblioteca")
            CodeImportResult.Imported(libraryId, plan.toAdd.size, plan.toReplace.size, plan.skipped)
        }

    /** Por qué no se puede editar [libraryId], o null si es una biblioteca del usuario. */
    private suspend fun editable(libraryId: String): CodeSaveResult? {
        if (CodeLibraryRepository.isDefault(libraryId)) return CodeSaveResult.NotEditable
        val library = dao.library(libraryId) ?: return CodeSaveResult.NotFound
        return if (library.projectId != null) CodeSaveResult.NotEditable else null
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
