package es.topophone.data.crs

import androidx.room.withTransaction
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
import es.topophone.data.db.TopoPhoneDatabase
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Lo que da guardar un SC en Mis SC (S035/RF-10). */
sealed interface CustomCrsSave {
    data class Saved(val crs: CustomCrs) : CustomCrsSave

    /** Ya hay otro SC con ese nombre (sin distinguir mayúsculas ni espacios de más). */
    data object NameTaken : CustomCrsSave

    /** El nombre está vacío. */
    data object EmptyName : CustomCrsSave

    /** El SC ya no existe. */
    data object NotFound : CustomCrsSave
}

/** Qué hacer al importar un SC cuyo nombre ya existe con otra definición (S035/RF-15). */
enum class CustomCrsConflictChoice { REPLACE, KEEP_BOTH, SKIP }

/** Lo que da importar un SC a Mis SC (S035/RF-13, RF-15). */
sealed interface CustomCrsImport {
    /** Se añadió como SC nuevo (con otro nombre si se conservaron los dos). */
    data class Added(val crs: CustomCrs) : CustomCrsImport

    /** Sustituyó al SC del mismo nombre (conserva su identificador y su fecha de creación). */
    data class Replaced(val crs: CustomCrs) : CustomCrsImport

    /** Ya estaba, con la misma definición y altura: no se hace nada ni se pregunta. */
    data class Unchanged(val existing: CustomCrs) : CustomCrsImport

    /** Se omitió por decisión del usuario. */
    data object Skipped : CustomCrsImport

    /** El nombre existe con otra definición y falta decidir qué hacer: se pide reemplazar, conservar ambos u omitir. */
    data class Conflict(val existing: CustomCrs) : CustomCrsImport
}

/** Mis SC: los SC personalizados guardados en el móvil (S035/RF-9, RF-10), sin nube. */
interface CustomCrsRepository {
    fun observeAll(): Flow<List<CustomCrs>>

    suspend fun all(): List<CustomCrs>

    suspend fun get(id: String): CustomCrs?

    /** Un SC nuevo; el nombre del SC es el de [definition]. */
    suspend fun create(definition: CrsDefinition, height: CustomHeight): CustomCrsSave

    /** Cambia la definición y la altura de [id], con el nombre de [definition] (editar y renombrar a la vez). */
    suspend fun update(id: String, definition: CrsDefinition, height: CustomHeight): CustomCrsSave

    /** Cambia solo el nombre. */
    suspend fun rename(id: String, name: String): CustomCrsSave

    /** Una copia con el nombre «X (copia)», o «X (copia 2)»… si ya existe. */
    suspend fun duplicate(id: String): CustomCrsSave

    /** Borra el SC de Mis SC: los proyectos que lo usan conservan su copia (S035/RF-11). */
    suspend fun delete(id: String)

    /**
     * Añade [incoming], leído de un JSON ya confirmado por el usuario (S035/RF-13). Si su nombre ya existe con otra definición y no se ha elegido
     * qué hacer ([choice] nulo) devuelve [CustomCrsImport.Conflict] sin tocar nada (RF-15).
     */
    suspend fun import(incoming: CustomCrs, choice: CustomCrsConflictChoice? = null): CustomCrsImport
}

class RoomCustomCrsRepository(
    private val database: TopoPhoneDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) : CustomCrsRepository {
    private val dao = database.customCrs()

    override fun observeAll(): Flow<List<CustomCrs>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toModel() } }

    override suspend fun all(): List<CustomCrs> = dao.all().mapNotNull { it.toModel() }

    override suspend fun get(id: String): CustomCrs? = dao.byId(id)?.toModel()

    override suspend fun create(definition: CrsDefinition, height: CustomHeight): CustomCrsSave = database.withTransaction {
        val name = clean(definition.name)
        if (name.isEmpty()) return@withTransaction CustomCrsSave.EmptyName
        if (dao.byNameKey(keyOf(name)) != null) return@withTransaction CustomCrsSave.NameTaken
        val now = clock()
        val crs = CustomCrs(ids(), name, definition.copy(name = name), height, now, now)
        dao.insert(crs.toEntity())
        CustomCrsSave.Saved(crs)
    }

    override suspend fun update(id: String, definition: CrsDefinition, height: CustomHeight): CustomCrsSave = database.withTransaction {
        val current = dao.byId(id)?.toModel() ?: return@withTransaction CustomCrsSave.NotFound
        val name = clean(definition.name)
        if (name.isEmpty()) return@withTransaction CustomCrsSave.EmptyName
        val taken = dao.byNameKey(keyOf(name))
        if (taken != null && taken.id != id) return@withTransaction CustomCrsSave.NameTaken
        val crs = current.copy(name = name, definition = definition.copy(name = name), height = height, modifiedAtMs = clock())
        dao.upsert(crs.toEntity())
        CustomCrsSave.Saved(crs)
    }

    override suspend fun rename(id: String, name: String): CustomCrsSave {
        val current = get(id) ?: return CustomCrsSave.NotFound
        return update(id, current.definition.copy(name = name), current.height)
    }

    override suspend fun duplicate(id: String): CustomCrsSave = database.withTransaction {
        val source = dao.byId(id)?.toModel() ?: return@withTransaction CustomCrsSave.NotFound
        var name = "${source.name} (copia)"
        var n = 2
        while (dao.byNameKey(keyOf(name)) != null) name = "${source.name} (copia ${n++})"
        create(source.definition.copy(name = name), source.height)
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun import(incoming: CustomCrs, choice: CustomCrsConflictChoice?): CustomCrsImport = database.withTransaction {
        val name = clean(incoming.name)
        val existing = dao.byNameKey(keyOf(name))?.toModel()
        if (existing == null) {
            return@withTransaction add(incoming, name)
        }
        if (sameDefinition(existing, incoming)) return@withTransaction CustomCrsImport.Unchanged(existing)
        when (choice) {
            null -> CustomCrsImport.Conflict(existing)
            CustomCrsConflictChoice.SKIP -> CustomCrsImport.Skipped
            CustomCrsConflictChoice.REPLACE -> {
                val crs = existing.copy(
                    name = name,
                    definition = incoming.definition.copy(name = name),
                    height = incoming.height,
                    modifiedAtMs = clock(),
                )
                dao.upsert(crs.toEntity())
                CustomCrsImport.Replaced(crs)
            }
            CustomCrsConflictChoice.KEEP_BOTH -> {
                var other = "$name (2)"
                var n = 3
                while (dao.byNameKey(keyOf(other)) != null) other = "$name (${n++})"
                add(incoming, other)
            }
        }
    }

    private suspend fun add(incoming: CustomCrs, name: String): CustomCrsImport {
        val now = clock()
        val crs = CustomCrs(ids(), name, incoming.definition.copy(name = name), incoming.height, now, now)
        dao.insert(crs.toEntity())
        return CustomCrsImport.Added(crs)
    }

    /** La misma definición y altura: el nombre ya es el mismo (sin distinguir mayúsculas), así que no cuenta. */
    private fun sameDefinition(a: CustomCrs, b: CustomCrs): Boolean =
        a.definition.copy(name = "") == b.definition.copy(name = "") && a.height == b.height

    private fun CustomCrs.toEntity(): CustomCrsEntity {
        val (kind, value) = when (val h = height) {
            CustomHeight.Ellipsoidal -> "ELLIPSOIDAL" to null
            is CustomHeight.Geoid -> "GEOID" to h.grid
            is CustomHeight.ConstantGeoid -> "CONSTANT" to h.undulationM.toString()
        }
        return CustomCrsEntity(id, name, keyOf(name), definition.toText(), kind, value, createdAtMs, modifiedAtMs)
    }

    /** `null` si la fila no se puede leer (una definición o una altura que esta versión no entiende): se ignora, no se rompe la lista. */
    private fun CustomCrsEntity.toModel(): CustomCrs? {
        val parsed = CrsDefinition.parseOrNull(definition) ?: return null
        val h = when (heightKind) {
            "ELLIPSOIDAL" -> CustomHeight.Ellipsoidal
            "GEOID" -> CustomHeight.Geoid(heightValue ?: return null)
            "CONSTANT" -> CustomHeight.ConstantGeoid(heightValue?.toDoubleOrNull() ?: return null)
            else -> return null
        }
        return CustomCrs(id, name, parsed, h, createdAt, modifiedAt)
    }

    companion object {
        private val SPACES = Regex("\\s+")

        /** El nombre sin espacios de más: los de los extremos y los repetidos por dentro. */
        fun clean(name: String): String = name.trim().replace(SPACES, " ")

        /** La clave de unicidad del nombre: sin mayúsculas ni espacios de más (S035/RF-10). */
        fun keyOf(name: String): String = clean(name).lowercase(Locale.ROOT)
    }
}
