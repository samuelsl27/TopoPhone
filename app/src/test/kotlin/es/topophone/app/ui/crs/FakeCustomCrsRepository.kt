package es.topophone.app.ui.crs

import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
import es.topophone.data.crs.CustomCrsConflictChoice
import es.topophone.data.crs.CustomCrsImport
import es.topophone.data.crs.CustomCrsRepository
import es.topophone.data.crs.CustomCrsSave
import es.topophone.data.crs.RoomCustomCrsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Mis SC en memoria, con las mismas reglas de nombres que el de Room (para las pruebas de pantalla). */
class FakeCustomCrsRepository(initial: List<CustomCrs> = emptyList()) : CustomCrsRepository {
    private val items = MutableStateFlow(initial)
    private var counter = 0

    override fun observeAll(): Flow<List<CustomCrs>> = items.map { list -> list.sortedBy { RoomCustomCrsRepository.keyOf(it.name) } }

    override suspend fun all(): List<CustomCrs> = items.value

    override suspend fun get(id: String): CustomCrs? = items.value.firstOrNull { it.id == id }

    private fun taken(name: String, exceptId: String? = null) =
        items.value.any { RoomCustomCrsRepository.keyOf(it.name) == RoomCustomCrsRepository.keyOf(name) && it.id != exceptId }

    override suspend fun create(definition: CrsDefinition, height: CustomHeight): CustomCrsSave {
        val name = RoomCustomCrsRepository.clean(definition.name)
        if (name.isEmpty()) return CustomCrsSave.EmptyName
        if (taken(name)) return CustomCrsSave.NameTaken
        val crs = CustomCrs("fake-${counter++}", name, definition.copy(name = name), height, 1, 1)
        items.value = items.value + crs
        return CustomCrsSave.Saved(crs)
    }

    override suspend fun update(id: String, definition: CrsDefinition, height: CustomHeight): CustomCrsSave {
        val current = get(id) ?: return CustomCrsSave.NotFound
        val name = RoomCustomCrsRepository.clean(definition.name)
        if (name.isEmpty()) return CustomCrsSave.EmptyName
        if (taken(name, id)) return CustomCrsSave.NameTaken
        val crs = current.copy(
            name = name,
            definition = definition.copy(name = name),
            height = height,
            modifiedAtMs =
            current.modifiedAtMs + 1,
        )
        items.value = items.value.map { if (it.id == id) crs else it }
        return CustomCrsSave.Saved(crs)
    }

    override suspend fun rename(id: String, name: String): CustomCrsSave {
        val current = get(id) ?: return CustomCrsSave.NotFound
        return update(id, current.definition.copy(name = name), current.height)
    }

    override suspend fun duplicate(id: String): CustomCrsSave {
        val source = get(id) ?: return CustomCrsSave.NotFound
        var name = "${source.name} (copia)"
        var n = 2
        while (taken(name)) name = "${source.name} (copia ${n++})"
        return create(source.definition.copy(name = name), source.height)
    }

    override suspend fun delete(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }

    override suspend fun import(incoming: CustomCrs, choice: CustomCrsConflictChoice?): CustomCrsImport {
        val existing = items.value.firstOrNull { RoomCustomCrsRepository.keyOf(it.name) == RoomCustomCrsRepository.keyOf(incoming.name) }
        if (existing == null) {
            return (create(incoming.definition.copy(name = incoming.name), incoming.height) as CustomCrsSave.Saved).let {
                CustomCrsImport.Added(it.crs)
            }
        }
        if (existing.definition.copy(name = "") == incoming.definition.copy(name = "") && existing.height == incoming.height) {
            return CustomCrsImport.Unchanged(existing)
        }
        return when (choice) {
            null -> CustomCrsImport.Conflict(existing)
            CustomCrsConflictChoice.SKIP -> CustomCrsImport.Skipped
            CustomCrsConflictChoice.REPLACE -> {
                val saved = update(existing.id, incoming.definition.copy(name = existing.name), incoming.height) as CustomCrsSave.Saved
                CustomCrsImport.Replaced(saved.crs)
            }
            CustomCrsConflictChoice.KEEP_BOTH -> {
                var n = 2
                while (taken("${incoming.name} ($n)")) n++
                val saved = create(incoming.definition.copy(name = "${incoming.name} ($n)"), incoming.height) as CustomCrsSave.Saved
                CustomCrsImport.Added(saved.crs)
            }
        }
    }
}
