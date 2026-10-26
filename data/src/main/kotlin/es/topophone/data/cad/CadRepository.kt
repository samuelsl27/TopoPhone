package es.topophone.data.cad

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.withTransaction
import es.topophone.core.model.cad.CadChunkCodec
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.db.TopoPhoneDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Un dibujo terminado con sus capas de diseño, por orden de aparición (S040/RF-26). */
data class CadDrawingWithLayers(val drawing: CadDrawing, val layers: List<DesignLayer>)

/** Un trozo que se va a guardar: los elementos ya codificados con [CadChunkCodec] y su recuadro (S040/RF-19). */
class CadChunkWrite(val layerId: String, val bounds: CadExtent, val entityCount: Int, val bytes: ByteArray)

/** Un trozo leído, con sus elementos ya decodificados (S040/RF-27, RF-29). */
class CadChunkContent(val chunkId: Long, val layerId: String, val bounds: CadExtent, val entities: List<CadEntity>)

/**
 * Los dibujos importados de un DXF (S040). Un dibujo se escribe por tandas y solo existe para el resto de la app cuando
 * [finish] lo pasa a `READY`: [abort] y [cleanUpUnfinished] borran lo que queda a medias (RF-12, RNF-T6).
 */
interface CadRepository {
    /** Abre un dibujo en estado `IMPORTING`; el campo `hasElevations` y el recuadro se fijan en [finish]. */
    suspend fun begin(drawing: CadDrawing)

    /** Añade una capa de diseño al dibujo que se está escribiendo. */
    suspend fun addLayer(layer: DesignLayer)

    /** Guarda una tanda de trozos en una sola transacción. */
    suspend fun addChunks(chunks: List<CadChunkWrite>)

    /** Cierra el dibujo: recuentos de cada capa, cota, si está incompleto y su recuadro; pasa a `READY` en una transacción. */
    suspend fun finish(drawingId: String, layerCounts: Map<String, Int>, hasElevations: Boolean, incomplete: Boolean, extent: CadExtent?)

    /** Borra un dibujo a medias con sus capas y trozos. */
    suspend fun abort(drawingId: String)

    /** Borra todo lo que quedó a medias (la app se cerró en plena importación); devuelve cuántos dibujos. */
    suspend fun cleanUpUnfinished(): Int

    /** Los dibujos terminados del proyecto por fecha de importación, con sus capas (RF-26, RF-28). */
    fun drawings(projectId: String): Flow<List<CadDrawingWithLayers>>

    suspend fun setLayerVisible(layerId: String, visible: Boolean)

    /** Borra un dibujo entero, con sus capas y trozos, sin tocar los puntos del proyecto (RF-30). */
    suspend fun deleteDrawing(drawingId: String)

    /** Los trozos de esas capas cuyo recuadro corta [area] (RF-24, RF-29). */
    suspend fun chunksIn(layerIds: Collection<String>, area: CadExtent): List<CadChunkContent>

    suspend fun chunk(chunkId: Long): CadChunkContent?

    /**
     * Como [chunksIn], pero los trozos se decodifican uno a uno al recorrer la secuencia (y otra vez en cada recorrido), para no tener
     * en memoria a la vez todos los elementos de una vista enorme (S040, RNF).
     */
    suspend fun chunkSequenceIn(layerIds: Collection<String>, area: CadExtent): Sequence<CadChunkContent>
}

@Dao
interface CadDao {
    @Insert
    suspend fun insertDrawing(drawing: CadDrawingEntity)

    @Insert
    suspend fun insertLayer(layer: CadLayerEntity)

    @Insert
    suspend fun insertChunks(chunks: List<CadChunkEntity>)

    @Query("UPDATE cad_layers SET entity_count = :count WHERE id = :layerId")
    suspend fun setLayerCount(layerId: String, count: Int)

    @Query(
        "UPDATE cad_drawings SET status = 'READY', has_elevations = :hasElevations, incomplete = :incomplete, " +
            "min_e = :minE, max_e = :maxE, min_n = :minN, max_n = :maxN WHERE id = :drawingId",
    )
    suspend fun markReady(
        drawingId: String,
        hasElevations: Boolean,
        incomplete: Boolean,
        minE: Double?,
        maxE: Double?,
        minN: Double?,
        maxN: Double?,
    )

    @Query("DELETE FROM cad_drawings WHERE id = :drawingId")
    suspend fun deleteDrawing(drawingId: String)

    @Query("DELETE FROM cad_drawings WHERE status != 'READY'")
    suspend fun deleteUnfinished(): Int

    @Query("SELECT * FROM cad_drawings WHERE project_id = :projectId AND status = 'READY' ORDER BY imported_at_ms, id")
    fun readyDrawings(projectId: String): Flow<List<CadDrawingEntity>>

    @Query(
        "SELECT l.* FROM cad_layers l JOIN cad_drawings d ON d.id = l.drawing_id " +
            "WHERE d.project_id = :projectId AND d.status = 'READY' ORDER BY l.position, l.name",
    )
    fun readyLayers(projectId: String): Flow<List<CadLayerEntity>>

    @Query("UPDATE cad_layers SET visible = :visible WHERE id = :layerId")
    suspend fun setVisible(layerId: String, visible: Boolean)

    @Query(
        "SELECT * FROM cad_chunks WHERE layer_id IN (:layerIds) AND max_e >= :minE AND min_e <= :maxE " +
            "AND max_n >= :minN AND min_n <= :maxN",
    )
    suspend fun chunksIn(layerIds: Collection<String>, minE: Double, maxE: Double, minN: Double, maxN: Double): List<CadChunkEntity>

    @Query("SELECT * FROM cad_chunks WHERE id = :chunkId")
    suspend fun chunk(chunkId: Long): CadChunkEntity?
}

class RoomCadRepository(private val database: TopoPhoneDatabase) : CadRepository {
    private val dao = database.cad()

    override suspend fun begin(drawing: CadDrawing) = dao.insertDrawing(drawing.toEntity(status = IMPORTING))

    override suspend fun addLayer(layer: DesignLayer) = dao.insertLayer(layer.toEntity())

    override suspend fun addChunks(chunks: List<CadChunkWrite>) {
        if (chunks.isEmpty()) return
        dao.insertChunks(
            chunks.map {
                CadChunkEntity(
                    layerId = it.layerId,
                    minEasting = it.bounds.minEasting,
                    maxEasting = it.bounds.maxEasting,
                    minNorthing = it.bounds.minNorthing,
                    maxNorthing = it.bounds.maxNorthing,
                    entityCount = it.entityCount,
                    data = it.bytes,
                )
            },
        )
    }

    override suspend fun finish(
        drawingId: String,
        layerCounts: Map<String, Int>,
        hasElevations: Boolean,
        incomplete: Boolean,
        extent: CadExtent?,
    ) {
        database.withTransaction {
            layerCounts.forEach { (layerId, count) -> dao.setLayerCount(layerId, count) }
            dao.markReady(
                drawingId,
                hasElevations,
                incomplete,
                extent?.minEasting,
                extent?.maxEasting,
                extent?.minNorthing,
                extent?.maxNorthing,
            )
        }
    }

    override suspend fun abort(drawingId: String) = dao.deleteDrawing(drawingId)

    override suspend fun cleanUpUnfinished(): Int = dao.deleteUnfinished()

    override fun drawings(projectId: String): Flow<List<CadDrawingWithLayers>> =
        combine(dao.readyDrawings(projectId), dao.readyLayers(projectId)) { drawings, layers ->
            val byDrawing = layers.groupBy { it.drawingId }
            drawings.map { d -> CadDrawingWithLayers(d.toModel(), byDrawing[d.id].orEmpty().map { it.toModel() }) }
        }

    override suspend fun setLayerVisible(layerId: String, visible: Boolean) = dao.setVisible(layerId, visible)

    override suspend fun deleteDrawing(drawingId: String) = dao.deleteDrawing(drawingId)

    override suspend fun chunksIn(layerIds: Collection<String>, area: CadExtent): List<CadChunkContent> {
        if (layerIds.isEmpty()) return emptyList()
        return dao.chunksIn(layerIds, area.minEasting, area.maxEasting, area.minNorthing, area.maxNorthing).map { it.toContent() }
    }

    override suspend fun chunk(chunkId: Long): CadChunkContent? = dao.chunk(chunkId)?.toContent()

    override suspend fun chunkSequenceIn(layerIds: Collection<String>, area: CadExtent): Sequence<CadChunkContent> {
        if (layerIds.isEmpty()) return emptySequence()
        val rows = dao.chunksIn(layerIds, area.minEasting, area.maxEasting, area.minNorthing, area.maxNorthing)
        return rows.asSequence().map { it.toContent() }
    }

    private companion object {
        const val IMPORTING = "IMPORTING"
    }
}

private fun CadDrawing.toEntity(status: String) = CadDrawingEntity(
    id = id,
    projectId = projectId,
    fileName = fileName,
    importedAtMs = importedAtMs,
    sourceCrs = source.encode(),
    sourceUnit = unit.name,
    useElevations = useElevations,
    hasElevations = hasElevations,
    incomplete = incomplete,
    status = status,
    minEasting = extent?.minEasting,
    maxEasting = extent?.maxEasting,
    minNorthing = extent?.minNorthing,
    maxNorthing = extent?.maxNorthing,
)

private fun CadDrawingEntity.toModel() = CadDrawing(
    id = id,
    projectId = projectId,
    fileName = fileName,
    importedAtMs = importedAtMs,
    source = decodeSource(sourceCrs),
    unit = LinearUnit.valueOf(sourceUnit),
    useElevations = useElevations,
    hasElevations = hasElevations,
    incomplete = incomplete,
    extent = if (minEasting != null && maxEasting != null && minNorthing != null && maxNorthing != null) {
        CadExtent(minEasting, maxEasting, minNorthing, maxNorthing)
    } else {
        null
    },
)

private fun DesignLayer.toEntity() = CadLayerEntity(
    id = id,
    drawingId = drawingId,
    name = name,
    kind = kind.name,
    colorArgb = color.argb,
    autoContrast = color.autoContrast,
    visible = visible,
    entityCount = entityCount,
    position = position,
)

private fun CadLayerEntity.toModel() = DesignLayer(
    id = id,
    drawingId = drawingId,
    name = name,
    kind = DesignLayerKind.valueOf(kind),
    color = CadColor(colorArgb, autoContrast),
    visible = visible,
    entityCount = entityCount,
    position = position,
)

private fun CadChunkEntity.toContent() =
    CadChunkContent(id, layerId, CadExtent(minEasting, maxEasting, minNorthing, maxNorthing), CadChunkCodec.decode(data))

private fun CadSourceCrs.encode(): String = when (this) {
    CadSourceCrs.Project -> "PROJECT"
    is CadSourceCrs.Utm -> "UTM:${datum.name}:$zone"
}

private fun decodeSource(text: String): CadSourceCrs {
    if (text == "PROJECT") return CadSourceCrs.Project
    val (_, datum, zone) = text.split(":")
    return CadSourceCrs.Utm(GeodeticDatum.valueOf(datum), zone.toInt())
}
