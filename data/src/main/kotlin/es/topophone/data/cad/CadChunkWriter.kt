package es.topophone.data.cad

import es.topophone.core.model.cad.CadChunkCodec
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.GpkgAttributesCodec
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Agrupa los elementos de un dibujo por capa de diseño y por celdas de 500 m, y los escribe como trozos de hasta 256 kB por tandas
 * de [BATCH_CHUNKS], sin tener en memoria más de [maxBufferedBytes] (S040/RNF; S048/RF-29). Lo comparten el importador de DXF y el
 * de GeoPackage. [writeBatch] guarda una tanda de trozos y lanza si no puede.
 *
 * Un elemento puede llevar su fila de atributos (ya codificada con [GpkgAttributesCodec.encodeRow]): el trozo guarda el bloque de las
 * filas de sus elementos, en el mismo orden, con los [attributeColumns] de su capa.
 */
internal class CadChunkWriter(
    geographic: Boolean,
    private val maxBufferedBytes: Long,
    private val writeBatch: (List<CadChunkWrite>) -> Unit,
) {
    /** El estado de una capa de diseño mientras se escribe: su id, su posición y cuántos elementos lleva. */
    class LayerState(val id: String, val position: Int, val attributeColumns: List<String> = emptyList(), var count: Int = 0)

    private class Buffer(val state: LayerState) {
        val entities = ArrayList<CadEntity>()
        val rows = ArrayList<ByteArray>()
        var bytes = 0
        var minE = Double.POSITIVE_INFINITY
        var maxE = Double.NEGATIVE_INFINITY
        var minN = Double.POSITIVE_INFINITY
        var maxN = Double.NEGATIVE_INFINITY
    }

    private data class Cell(val layerId: String, val x: Long, val y: Long)

    private val cellSize = if (geographic) CELL_DEGREES else CELL_METRES
    private val buffers = HashMap<Cell, Buffer>()
    private val pending = ArrayList<CadChunkWrite>()
    private var buffered = 0L

    /** Lo máximo que hubo en los buffers, en bytes: nunca pasa de [maxBufferedBytes] más un trozo. */
    var peakBuffered = 0L
        private set

    /** Añade un elemento (una polilínea muy larga ya viene partida) a su capa y su celda. [attributes] es null en un DXF. */
    fun add(state: LayerState, entity: CadEntity, attributes: ByteArray? = null) {
        val box = entity.bounds
        val cell = Cell(state.id, floor(box.minEasting / cellSize).toLong(), floor(box.minNorthing / cellSize).toLong())
        val size = CadChunkCodec.entitySize(entity) + (attributes?.size ?: 0)
        val existing = buffers[cell]
        if (existing != null &&
            existing.entities.isNotEmpty() &&
            existing.bytes + size > CadChunkCodec.MAX_CHUNK_BYTES - CHUNK_HEADER_BYTES
        ) {
            flush(cell)
        }
        val current = buffers.getOrPut(cell) { Buffer(state) }
        current.entities += entity
        attributes?.let { current.rows += it }
        current.bytes += size
        current.minE = min(current.minE, box.minEasting)
        current.maxE = max(current.maxE, box.maxEasting)
        current.minN = min(current.minN, box.minNorthing)
        current.maxN = max(current.maxN, box.maxNorthing)
        buffered += size
        if (buffered > peakBuffered) peakBuffered = buffered
        state.count++
        if (buffered > maxBufferedBytes) flushLargest()
    }

    /** Escribe todo lo que queda en los buffers. */
    fun flushAll() {
        buffers.keys.toList().forEach { flush(it) }
        writePending()
    }

    private fun flush(cell: Cell) {
        val buffer = buffers.remove(cell) ?: return
        buffered -= buffer.bytes
        if (buffer.entities.isEmpty()) return
        val bounds = CadExtent(buffer.minE, buffer.maxE, buffer.minN, buffer.maxN)
        val attributes = if (buffer.rows.isEmpty()) null else GpkgAttributesCodec.encodeBlock(buffer.state.attributeColumns, buffer.rows)
        pending += CadChunkWrite(buffer.state.id, bounds, buffer.entities.size, CadChunkCodec.encode(buffer.entities), attributes)
        if (pending.size >= BATCH_CHUNKS) writePending()
    }

    /** Vacía los buffers más grandes hasta quedar a la mitad del límite. */
    private fun flushLargest() {
        val order = buffers.entries.sortedByDescending { it.value.bytes }
        for ((cell, _) in order) {
            if (buffered <= maxBufferedBytes / 2) break
            flush(cell)
        }
    }

    private fun writePending() {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        writeBatch(batch)
    }

    companion object {
        private const val CELL_METRES = 500.0
        private const val CELL_DEGREES = 0.005
        private const val BATCH_CHUNKS = 64

        /** Lo que el bloque de un trozo gasta en su cabecera (el número de elementos). */
        private const val CHUNK_HEADER_BYTES = 8

        /** Lo que puede pesar un solo elemento: el `CursorWindow` de Android admite filas de hasta 2 MB. */
        const val MAX_ENTITY_BYTES = 1_500_000

        /** Una polilínea con más vértices de los que caben en una fila se parte en trozos que comparten el vértice de unión. */
        fun split(entity: CadEntity): List<CadEntity> {
            if (CadChunkCodec.entitySize(entity) <= MAX_ENTITY_BYTES) return listOf(entity)
            val perVertex = Double.SIZE_BYTES * (if (entity.hasZ) 3 else 2)
            val maxVertices = (MAX_ENTITY_BYTES - 16) / perVertex
            val out = ArrayList<CadEntity>()
            var start = 0
            while (start < entity.vertexCount - 1) {
                val end = min(start + maxVertices, entity.vertexCount)
                out += entity.slice(start, end)
                start = end - 1
            }
            return out
        }
    }
}
