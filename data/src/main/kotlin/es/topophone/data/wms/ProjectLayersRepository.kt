package es.topophone.data.wms

import androidx.room.withTransaction
import es.topophone.core.model.wms.ProjectMapLayer
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsLimits
import es.topophone.core.model.wms.WmsService
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Una capa del proyecto con el servicio de donde viene y lo que éste dice de ella (S036/RF-18 a RF-29). [info] es null si el servicio ya
 * no la ofrece o ya no se puede pedir (sin nombre o sin Web Mercator): sigue en el proyecto como «no disponible» y no se pide (RF-27).
 *
 * @property showService hay otra capa del proyecto con el mismo título en otro servicio: se muestra junto a cada una el nombre del suyo
 *   (RF-28).
 */
data class ProjectLayerView(val layer: ProjectMapLayer, val service: WmsService, val info: WmsLayerInfo?, val showService: Boolean) {
    val available: Boolean get() = info != null

    /** El título que ve el usuario: el que da el servicio o, si ya no está, el nombre de la capa. */
    val title: String get() = info?.title?.takeIf { it.isNotBlank() } ?: layer.layerName
}

/** Cómo acabó añadir una capa a un proyecto (S036/RF-12, RF-26). */
sealed interface AddLayerResult {
    data class Added(val layer: ProjectMapLayer) : AddLayerResult

    /** La capa ya está en el proyecto. */
    data object AlreadyThere : AddLayerResult

    /** El proyecto ya tiene [WmsLimits.MAX_PROJECT_LAYERS] capas. */
    data object LimitReached : AddLayerResult

    /** La capa existe, pero el servicio no la ofrece en Web Mercator: no se puede pedir (RF-12). */
    data object NoWebMercator : AddLayerResult

    /** No existe el servicio o no tiene ninguna capa con ese nombre. */
    data object NotFound : AddLayerResult
}

/** Las capas WMS de cada proyecto, con su orden, su visibilidad y su opacidad (S036/RF-18 a RF-28). */
interface ProjectLayersRepository {
    /** Las capas del proyecto de arriba abajo; se actualiza sola al cambiarlas o al actualizar un servicio. */
    fun layers(projectId: String): Flow<List<ProjectLayerView>>

    /** Añade la capa [layerName] de [serviceId] arriba del todo, visible y al 100 %. */
    suspend fun add(projectId: String, serviceId: String, layerName: String): AddLayerResult

    /** false si no existe esa capa del proyecto. */
    suspend fun setVisible(id: String, visible: Boolean): Boolean

    /** La opacidad se ajusta a pasos de 5 dentro de 0 a 100 (RF-21); false si no existe. */
    suspend fun setOpacity(id: String, percent: Int): Boolean

    /** Pone la capa en la posición [position] (0 es la de arriba, y se ajusta al rango) y renumera las demás sin huecos (RF-22). */
    suspend fun move(id: String, position: Int): Boolean

    /** Quita la capa del proyecto sin borrar el servicio (RF-23); false si no existe. */
    suspend fun remove(id: String): Boolean
}

class RoomProjectLayersRepository(
    private val database: TopoPhoneDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ProjectLayersRepository {
    private val dao = database.wms()

    override fun layers(projectId: String): Flow<List<ProjectLayerView>> = dao.projectLayerViews(projectId).map { rows ->
        val titles = rows.map { it.title() }
        val ambiguous = rows.indices.filter { i ->
            rows.indices.any { j -> j != i && titles[j] == titles[i] && rows[j].layer.serviceId != rows[i].layer.serviceId }
        }.toSet()
        rows.mapIndexed { i, row ->
            ProjectLayerView(row.layer.toModel(), row.service.toModel(), row.info?.toModel(), i in ambiguous)
        }
    }

    private fun ProjectLayerJoin.title() = info?.title?.takeIf { it.isNotBlank() } ?: layer.layerName

    override suspend fun add(projectId: String, serviceId: String, layerName: String): AddLayerResult = database.withTransaction {
        if (dao.service(serviceId) == null) return@withTransaction AddLayerResult.NotFound
        val named = dao.layersNamed(serviceId, layerName)
        if (named.isEmpty()) return@withTransaction AddLayerResult.NotFound
        if (named.none { it.mercatorCode != null }) return@withTransaction AddLayerResult.NoWebMercator
        if (dao.countProjectLayer(projectId, serviceId, layerName) > 0) return@withTransaction AddLayerResult.AlreadyThere
        if (dao.countProjectLayers(projectId) >= WmsLimits.MAX_PROJECT_LAYERS) return@withTransaction AddLayerResult.LimitReached
        dao.shiftDown(projectId)
        val row = ProjectWmsLayerEntity(
            id = newId(),
            projectId = projectId,
            serviceId = serviceId,
            layerName = layerName,
            position = 0,
            visible = true,
            opacity = FULL_OPACITY,
            addedAt = clock(),
        )
        dao.insertProjectLayer(row)
        AddLayerResult.Added(row.toModel())
    }

    override suspend fun setVisible(id: String, visible: Boolean): Boolean = dao.setVisible(id, visible) > 0

    override suspend fun setOpacity(id: String, percent: Int): Boolean = dao.setOpacity(id, WmsLimits.snapOpacity(percent)) > 0

    override suspend fun move(id: String, position: Int): Boolean = database.withTransaction {
        val moved = dao.projectLayer(id) ?: return@withTransaction false
        val order = dao.projectLayersNow(moved.projectId).map { it.id }.toMutableList()
        order.remove(id)
        order.add(position.coerceIn(0, order.size), id)
        order.forEachIndexed { index, layerId -> dao.setPosition(layerId, index) }
        true
    }

    override suspend fun remove(id: String): Boolean = database.withTransaction {
        val gone = dao.projectLayer(id) ?: return@withTransaction false
        dao.deleteProjectLayer(id)
        dao.projectLayersNow(gone.projectId).forEachIndexed { index, row -> dao.setPosition(row.id, index) }
        true
    }

    private fun ProjectWmsLayerEntity.toModel() = ProjectMapLayer(id, projectId, serviceId, layerName, position, visible, opacity)

    private companion object {
        const val FULL_OPACITY = 100
    }
}
