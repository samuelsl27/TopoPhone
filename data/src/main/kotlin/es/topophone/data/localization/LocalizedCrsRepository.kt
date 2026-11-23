package es.topophone.data.localization

import androidx.room.withTransaction
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.LocalizationStatus
import es.topophone.core.model.localization.LocalizedCrs
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrsText
import es.topophone.data.crs.CrsChangeResult
import es.topophone.data.crs.ProjectCrsHistoryRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.toProject
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Qué pasó al guardar la localización activa como SC (S039/RF-24). */
sealed interface LocalizedCrsSave {
    data class Saved(val crs: LocalizedCrs) : LocalizedCrsSave

    /** El nombre está vacío. */
    data object InvalidName : LocalizedCrsSave

    /** Otro SC de Mis SC, con o sin localización, tiene ese nombre (sin distinguir mayúsculas). */
    data object NameTaken : LocalizedCrsSave

    /** El proyecto no tiene una localización activa de pares que guardar. */
    data object NothingToSave : LocalizedCrsSave
}

/** Qué pasó al elegir un SC guardado con localización en un proyecto (S039/RF-28). */
sealed interface LocalizedCrsUse {
    /** El proyecto quedó con el SC base y la localización activa, sin pares; [pointsChanged] puntos GNSS cambiaron sus coordenadas. */
    data class Applied(val pointsChanged: Int) : LocalizedCrsUse

    data object NotFound : LocalizedCrsUse

    /** El proyecto está en coordenadas de terreno (RF-22). */
    data object TerrainActive : LocalizedCrsUse

    /** El SC base es de otro datum que el del proyecto: no se cambia (S035/RF-17). */
    data object OtherDatum : LocalizedCrsUse
}

/**
 * Los SC de «Mis SC» guardados con una localización (S039/RF-24, RF-28; plan D8): el SC base del proyecto, sus alturas y los parámetros de la
 * localización. Al elegirlos en un proyecto, este queda con el SC base y la localización como activa, sin pares.
 */
interface LocalizedCrsRepository {
    fun observeAll(): Flow<List<LocalizedCrs>>

    /** Guarda la localización activa de [projectId] como un SC llamado [name] (RF-24). */
    suspend fun saveFromProject(projectId: String, name: String): LocalizedCrsSave

    suspend fun delete(id: String)

    /** Elige el SC [id] en [projectId]: el SC base y la localización activa sin pares, en una transacción (RF-28). */
    suspend fun use(projectId: String, id: String, nowMs: Long = System.currentTimeMillis()): LocalizedCrsUse
}

class RoomLocalizedCrsRepository(
    private val database: TopoPhoneDatabase,
    private val localizations: LocalizationRepository,
    private val history: ProjectCrsHistoryRepository,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
) : LocalizedCrsRepository {
    private val dao = database.localizedCrs()

    override fun observeAll(): Flow<List<LocalizedCrs>> = dao.all().map { rows -> rows.mapNotNull { it.toModel() } }

    override suspend fun saveFromProject(projectId: String, name: String): LocalizedCrsSave = database.withTransaction {
        val clean = name.trim()
        if (clean.isEmpty()) return@withTransaction LocalizedCrsSave.InvalidName
        val active = database.localizations().withStatusNow(projectId, LocalizationStatus.ACTIVE.name)
        val params = active?.takeIf { it.origin == LocalizationOrigin.PAIRS.name }?.let { LocalizationParams.parseOrNull(it.params) }
            ?: return@withTransaction LocalizedCrsSave.NothingToSave
        if (dao.byName(clean) != null || database.customCrs().all().any { it.name.equals(clean, ignoreCase = true) }) {
            return@withTransaction LocalizedCrsSave.NameTaken
        }
        val entity = LocalizedCrsEntity(ids(), clean, active.crsText, active.heightSystem, params.toText(), clock())
        dao.insert(entity)
        LocalizedCrsSave.Saved(entity.toModel() ?: error("El SC recién guardado no se puede leer"))
    }

    override suspend fun delete(id: String) = dao.delete(id)

    override suspend fun use(projectId: String, id: String, nowMs: Long): LocalizedCrsUse = try {
        database.withTransaction { useInTransaction(projectId, id, nowMs) }
    } catch (_: OtherDatumAbort) {
        // La transacción entera se canceló: el proyecto, sus puntos y su localización siguen como estaban.
        LocalizedCrsUse.OtherDatum
    }

    private suspend fun useInTransaction(projectId: String, id: String, nowMs: Long): LocalizedCrsUse {
        val saved = dao.byId(id)?.toModel() ?: return LocalizedCrsUse.NotFound
        val project = database.projects().byId(projectId)?.toProject() ?: return LocalizedCrsUse.NotFound
        if (project.terrain != null) return LocalizedCrsUse.TerrainActive
        // El SC y las alturas del proyecto pasan a los del SC guardado: primero se quita la localización activa, que lo impediría (RF-27).
        localizations.remove(projectId)
        if (project.crs != saved.crs || project.heightSystem != saved.heightSystem) {
            if (history.apply(projectId, saved.crs, saved.heightSystem) == CrsChangeResult.OtherDatum) throw OtherDatumAbort()
        }
        return when (val result = localizations.applyParams(projectId, saved.name, saved.params, nowMs)) {
            is ApplyResult.Applied -> LocalizedCrsUse.Applied(result.pointsChanged)
            ApplyResult.TerrainActive -> LocalizedCrsUse.TerrainActive
            else -> LocalizedCrsUse.NotFound
        }
    }

    private class OtherDatumAbort : RuntimeException()
}

private fun LocalizedCrsEntity.toModel(): LocalizedCrs? {
    val crs = ProjectCrsText.decodeOrNull(crsText) ?: return null
    val height = HeightSystem.entries.firstOrNull { it.name == heightSystem } ?: return null
    val parsed = LocalizationParams.parseOrNull(params) ?: return null
    return LocalizedCrs(id, name, crs, height, parsed, createdAtMs)
}
