package es.topophone.data.wms

import androidx.room.withTransaction
import es.topophone.core.formats.wms.WmsCapabilities
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Un proyecto con capas de un servicio y cuántas (S036/RF-7). */
data class WmsServiceUse(val projectId: String, val projectName: String, val layers: Int)

/** Una capa de un proyecto que el servicio ya no ofrece tras actualizarlo (S036/RF-6, RF-27). */
data class MissingProjectLayer(val projectId: String, val projectName: String, val layerName: String)

/** Cómo acabó guardar un servicio nuevo (S036/RF-4, RF-15). */
sealed interface WmsAddResult {
    data class Added(val service: WmsService) : WmsAddResult

    /** Ya había un servicio con esa dirección: no se duplica y se abre el que hay (D2 del plan). */
    data class AlreadyThere(val service: WmsService) : WmsAddResult
}

/**
 * La biblioteca de servicios WMS del móvil, común a todos los proyectos (S036/RF-3 a RF-7, RF-15): cada servicio con las capas que leyó su
 * GetCapabilities, para verlas de nuevo sin conexión.
 */
interface WmsLibraryRepository {
    /** Los servicios por nombre, sin distinguir mayúsculas. */
    fun services(): Flow<List<WmsService>>

    suspend fun service(id: String): WmsService?

    /** El servicio con la dirección equivalente a [address], si ya está (RF-15). */
    suspend fun findByAddress(address: WmsAddress): WmsService?

    /** Las capas que leyó el servicio, en el orden del documento (RF-3, RF-4). */
    suspend fun layers(serviceId: String): List<WmsLayerInfo>

    /**
     * Guarda el servicio de [address] con lo que leyó [capabilities], en una transacción; si ya hay uno con esa dirección, no guarda nada y
     * devuelve el que hay. [name] es el nombre que ve el usuario (el título del servicio si es null o está en blanco).
     */
    suspend fun add(
        address: WmsAddress,
        capabilities: WmsCapabilities,
        requestMode: WmsRequestMode,
        presetKey: String? = null,
        name: String? = null,
    ): WmsAddResult

    /** Cambia el nombre; false si el nombre está en blanco o el servicio no existe (RF-7). */
    suspend fun rename(id: String, name: String): Boolean

    /** «Por teselas» o «una imagen» (RF-17); false si el servicio no existe. */
    suspend fun setRequestMode(id: String, mode: WmsRequestMode): Boolean

    /** Los proyectos activos con capas del servicio, para avisar antes de borrarlo (RF-7). */
    suspend fun projectsUsing(serviceId: String): List<WmsServiceUse>

    /** Borra el servicio, sus capas leídas y las que los proyectos tomaron de él (RF-7). */
    suspend fun delete(id: String)

    /**
     * Sustituye lo leído del servicio por [capabilities] (RF-6) y devuelve las capas de proyectos activos que ya no están (o que ya no se
     * pueden pedir): siguen en su proyecto como «no disponibles» (RF-27). Null si el servicio no existe.
     */
    suspend fun refresh(id: String, capabilities: WmsCapabilities): List<MissingProjectLayer>?
}

class RoomWmsLibraryRepository(
    private val database: TopoPhoneDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : WmsLibraryRepository {
    private val dao = database.wms()

    override fun services(): Flow<List<WmsService>> = dao.services().map { rows -> rows.map { it.toModel() } }

    override suspend fun service(id: String): WmsService? = dao.service(id)?.toModel()

    override suspend fun findByAddress(address: WmsAddress): WmsService? = dao.serviceByKey(address.key)?.toModel()

    override suspend fun layers(serviceId: String): List<WmsLayerInfo> = dao.layersOf(serviceId).map { it.toModel() }

    override suspend fun add(
        address: WmsAddress,
        capabilities: WmsCapabilities,
        requestMode: WmsRequestMode,
        presetKey: String?,
        name: String?,
    ): WmsAddResult = database.withTransaction {
        dao.serviceByKey(address.key)?.let { return@withTransaction WmsAddResult.AlreadyThere(it.toModel()) }
        val now = clock()
        val id = newId()
        val entity = WmsServiceEntity(
            id = id,
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: capabilities.title.ifBlank { address.base },
            address = address.text,
            addressKey = address.key,
            title = capabilities.title,
            version = capabilities.version,
            attribution = capabilities.attribution,
            presetKey = presetKey,
            requestMode = requestMode.name,
            mapFormats = capabilities.mapFormats.joinToString(WMS_LIST_SEPARATOR),
            infoFormats = capabilities.infoFormats.joinToString(WMS_LIST_SEPARATOR),
            createdAt = now,
            updatedAt = now,
        )
        dao.insertService(entity)
        insertLayers(id, capabilities.layers)
        WmsAddResult.Added(entity.toModel())
    }

    override suspend fun rename(id: String, name: String): Boolean {
        val clean = name.trim()
        return clean.isNotEmpty() && dao.rename(id, clean) > 0
    }

    override suspend fun setRequestMode(id: String, mode: WmsRequestMode): Boolean = dao.setRequestMode(id, mode.name) > 0

    override suspend fun projectsUsing(serviceId: String): List<WmsServiceUse> =
        dao.uses(serviceId).map { WmsServiceUse(it.projectId, it.projectName, it.layers) }

    override suspend fun delete(id: String) = dao.deleteService(id)

    override suspend fun refresh(id: String, capabilities: WmsCapabilities): List<MissingProjectLayer>? = database.withTransaction {
        if (dao.service(id) == null) return@withTransaction null
        dao.deleteLayers(id)
        insertLayers(id, capabilities.layers)
        dao.updateRead(
            id,
            capabilities.title,
            capabilities.version,
            capabilities.attribution,
            capabilities.mapFormats.joinToString(WMS_LIST_SEPARATOR),
            capabilities.infoFormats.joinToString(WMS_LIST_SEPARATOR),
            clock(),
        )
        dao.missing(id).map { MissingProjectLayer(it.projectId, it.projectName, it.layerName) }
    }

    private suspend fun insertLayers(serviceId: String, layers: List<WmsLayerInfo>) {
        layers.chunked(LAYER_BATCH).forEach { batch ->
            dao.insertLayers(
                batch.map { layer ->
                    WmsLayerEntity(
                        serviceId,
                        layer.ordinal,
                        layer.depth,
                        layer.name,
                        layer.title,
                        layer.abstract,
                        layer.transparent,
                        layer.queryable,
                        layer.mercatorCode,
                    )
                },
            )
        }
    }

    private companion object {
        const val LAYER_BATCH = 1_000
    }
}
