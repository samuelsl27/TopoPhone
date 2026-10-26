package es.topophone.data.cad

import es.topophone.core.formats.dxf.DxfExtentSink
import es.topophone.core.formats.dxf.DxfReader
import es.topophone.core.formats.dxf.DxfSink
import es.topophone.core.formats.dxf.DxfTables
import es.topophone.core.model.cad.CadChunkCodec
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadImportSummary
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.CadProjection
import es.topophone.core.survey.CadProjector
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Por qué no se importó un DXF (S040/RF-12, RF-13). */
enum class CadImportFailure { NO_ELEMENTS, READ_ERROR, WRITE_ERROR }

/** Cómo acabó una importación. Cancelarla lanza `CancellationException` y no deja nada (RF-12). */
sealed interface CadImportResult {
    data class Imported(val drawingId: String, val summary: CadImportSummary) : CadImportResult

    data class Failed(val reason: CadImportFailure) : CadImportResult
}

/** Lo que se ve de un DXF antes de importarlo: su recuadro en las coordenadas del dibujo y su unidad declarada (S040/RF-7, RF-9). */
data class CadScan(val extent: CadExtent?, val elements: Int, val declaredUnit: LinearUnit?, val complete: Boolean)

/**
 * Importa un DXF a un proyecto (S040/RF-11 a RF-23, RF-33): lo lee por flujo, pasa cada elemento al sistema del proyecto, lo
 * agrupa por capa de diseño (nombre y tipo) y por celdas de 500 m, y escribe trozos de hasta 256 kB por tandas. El dibujo no
 * existe para el resto de la app hasta que está entero: cancelar o fallar lo borra todo.
 */
class CadDrawingImporter(
    private val repository: CadRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxBufferedBytes: Long = MAX_BUFFERED_BYTES,
) {
    /** La exploración que da la extensión antes de importar: una pasada completa del lector que no guarda nada (RF-9). */
    suspend fun scan(openStream: () -> InputStream, unit: LinearUnit = LinearUnit.METRE, onProgress: (Long) -> Unit = {}): CadScan =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            val sink = DxfExtentSink()
            val result = Counting(openStream(), onProgress, job::ensureActive).use { DxfReader.read(it, unit, sink) }
            CadScan(sink.extent, sink.count, result.header.declaredUnit, result.complete)
        }

    /**
     * Importa el fichero que da [openStream] como un dibujo nuevo del proyecto. [onProgress] recibe los bytes leídos. Lanza
     * `CancellationException` si se cancela; en ese caso, y si falla algo, no queda nada guardado.
     */
    suspend fun import(
        projectId: String,
        crs: ProjectCrs,
        fileName: String,
        options: CadImportOptions,
        openStream: () -> InputStream,
        onProgress: (Long) -> Unit = {},
    ): CadImportResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val drawingId = newId()
        val drawing = CadDrawing(
            id = drawingId,
            projectId = projectId,
            fileName = fileName,
            importedAtMs = clock(),
            source = options.source,
            unit = options.unit,
            useElevations = options.useElevations,
            hasElevations = options.useElevations,
        )
        var begun = false
        try {
            writing { repository.begin(drawing) }
            begun = true
            val sink = ImportSink(drawingId, CadProjector(crs, options), geographic = crs.utmZone == null, onCheck = job::ensureActive)
            val read = Counting(openStream(), onProgress, job::ensureActive).use { DxfReader.read(it, options.unit, sink) }
            job.ensureActive()
            sink.flushAll()
            if (sink.importedTotal == 0) {
                writing { repository.abort(drawingId) }
                return@withContext CadImportResult.Failed(CadImportFailure.NO_ELEMENTS)
            }
            val hasElevations = options.useElevations && sink.anyHeight && sink.anyNonZeroHeight
            writing { repository.finish(drawingId, sink.layerCounts(), hasElevations, incomplete = !read.complete, extent = sink.extent()) }
            CadImportResult.Imported(drawingId, sink.summary(incomplete = !read.complete))
        } catch (e: CancellationException) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(drawingId) } }
            throw e
        } catch (e: WriteFailure) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(drawingId) } }
            CadImportResult.Failed(CadImportFailure.WRITE_ERROR)
        } catch (e: Exception) {
            // Un fallo al leer el fichero o al convertir sus elementos, distinto de uno al escribir.
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(drawingId) } }
            CadImportResult.Failed(CadImportFailure.READ_ERROR)
        }
    }

    /** Un fallo al escribir en la base de datos: el dibujo no se guarda (RF-12). */
    private class WriteFailure(cause: Throwable) : RuntimeException(cause)

    private suspend fun <T> writing(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw WriteFailure(e)
    }

    /** Cuenta los bytes leídos, los avisa y comprueba la cancelación cada vez que se rellena el búfer. */
    private class Counting(input: InputStream, private val onProgress: (Long) -> Unit, private val check: () -> Unit) :
        FilterInputStream(input) {
        private var total = 0L
        private var lastReported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        private fun advance(n: Long) {
            total += n
            if (total - lastReported >= REPORT_EVERY || n < 0) {
                lastReported = total
                check()
                onProgress(total)
            }
        }

        override fun close() {
            onProgress(total) // el último aviso es siempre el tamaño leído entero
            super.close()
        }
    }

    private class LayerState(val id: String, val position: Int, var count: Int = 0)

    private class Buffer(val layerId: String) {
        val entities = ArrayList<CadEntity>()
        var bytes = 0
        var minE = Double.POSITIVE_INFINITY
        var maxE = Double.NEGATIVE_INFINITY
        var minN = Double.POSITIVE_INFINITY
        var maxN = Double.NEGATIVE_INFINITY
    }

    private data class LayerKey(val name: String, val kind: DesignLayerKind)

    private data class Cell(val layerId: String, val x: Long, val y: Long)

    /** El receptor de los elementos del lector: los convierte, los agrupa y los escribe por tandas. */
    private inner class ImportSink(
        private val drawingId: String,
        private val projector: CadProjector,
        geographic: Boolean,
        private val onCheck: () -> Unit,
    ) : DxfSink {
        private val cellSize = if (geographic) CELL_DEGREES else CELL_METRES
        private var tables = DxfTables(emptyMap())
        private val layers = LinkedHashMap<LayerKey, LayerState>()
        private val buffers = HashMap<Cell, Buffer>()
        private val pending = ArrayList<CadChunkWrite>()
        private var buffered = 0L
        private val imported = HashMap<CadEntityKind, Int>()
        private val ignored = HashMap<CadIgnored, Int>()
        private var minE = Double.POSITIVE_INFINITY
        private var maxE = Double.NEGATIVE_INFINITY
        private var minN = Double.POSITIVE_INFINITY
        private var maxN = Double.NEGATIVE_INFINITY

        var anyHeight = false
            private set
        var anyNonZeroHeight = false
            private set
        var importedTotal = 0
            private set

        override fun tables(tables: DxfTables) {
            this.tables = tables
        }

        override fun ignored(ignored: CadIgnored) {
            this.ignored.merge(ignored, 1, Int::plus)
        }

        override fun element(layer: String, entity: CadEntity) {
            onCheck()
            val converted = when (val projection = projector.convert(entity)) {
                is CadProjection.Converted -> projection.entity
                is CadProjection.Rejected -> {
                    ignored(CadIgnored(projection.reason))
                    return
                }
            }
            store(layer, converted)
        }

        private fun store(layerName: String, entity: CadEntity) {
            val kind = if (entity.kind == CadEntityKind.POINT ||
                entity.kind == CadEntityKind.BLOCK_INSERT
            ) {
                DesignLayerKind.POINTS
            } else {
                DesignLayerKind.LINES
            }
            val state = layers.getOrPut(LayerKey(layerName, kind)) { createLayer(layerName, kind) }
            imported.merge(entity.kind, 1, Int::plus)
            importedTotal++
            entity.height?.let { heights ->
                anyHeight = true
                if (heights.any { it != 0.0 }) anyNonZeroHeight = true
            }
            val box = entity.bounds
            minE = min(minE, box.minEasting)
            maxE = max(maxE, box.maxEasting)
            minN = min(minN, box.minNorthing)
            maxN = max(maxN, box.maxNorthing)
            for (piece in split(entity)) add(state, piece)
        }

        private fun createLayer(name: String, kind: DesignLayerKind): LayerState {
            val state = LayerState(newId(), layers.size)
            val layer = DesignLayer(
                id = state.id,
                drawingId = drawingId,
                name = name,
                kind = kind,
                color = tables.colorOf(name),
                visible = tables.layer(name)?.hidden != true,
                position = state.position,
            )
            write { repository.addLayer(layer) }
            return state
        }

        private fun add(state: LayerState, entity: CadEntity) {
            val box = entity.bounds
            val cell = Cell(state.id, floor(box.minEasting / cellSize).toLong(), floor(box.minNorthing / cellSize).toLong())
            val buffer = buffers.getOrPut(cell) { Buffer(state.id) }
            val size = CadChunkCodec.entitySize(entity)
            if (buffer.entities.isNotEmpty() && buffer.bytes + size > CadChunkCodec.MAX_CHUNK_BYTES - CHUNK_HEADER_BYTES) flush(cell)
            val current = buffers.getOrPut(cell) { Buffer(state.id) }
            current.entities += entity
            current.bytes += size
            current.minE = min(current.minE, box.minEasting)
            current.maxE = max(current.maxE, box.maxEasting)
            current.minN = min(current.minN, box.minNorthing)
            current.maxN = max(current.maxN, box.maxNorthing)
            buffered += size
            state.count++
            if (buffered > maxBufferedBytes) flushLargest()
        }

        private fun flush(cell: Cell) {
            val buffer = buffers.remove(cell) ?: return
            buffered -= buffer.bytes
            if (buffer.entities.isEmpty()) return
            val bounds = CadExtent(buffer.minE, buffer.maxE, buffer.minN, buffer.maxN)
            pending += CadChunkWrite(buffer.layerId, bounds, buffer.entities.size, CadChunkCodec.encode(buffer.entities))
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

        fun flushAll() {
            buffers.keys.toList().forEach { flush(it) }
            writePending()
        }

        private fun writePending() {
            if (pending.isEmpty()) return
            val batch = pending.toList()
            pending.clear()
            write { repository.addChunks(batch) }
        }

        /** La escritura es suspendida y el lector no: se espera en el hilo de entrada y salida, que no es el principal. */
        private fun write(block: suspend () -> Unit) = runBlocking { writing(block) }

        fun layerCounts(): Map<String, Int> = layers.values.associate { it.id to it.count }

        fun extent(): CadExtent? = if (importedTotal == 0) null else CadExtent(minE, maxE, minN, maxN)

        fun summary(incomplete: Boolean) = CadImportSummary(imported.toMap(), ignored.toMap(), layers.size, incomplete)
    }

    /** Una polilínea con más vértices de los que caben en una fila se parte en trozos que comparten el vértice de unión. */
    private fun split(entity: CadEntity): List<CadEntity> {
        if (CadChunkCodec.entitySize(entity) <= MAX_ENTITY_BYTES) return listOf(entity)
        val perVertex = Double.SIZE_BYTES * (if (entity.hasZ) 3 else 2)
        val maxVertices = (MAX_ENTITY_BYTES - 16) / perVertex
        val out = ArrayList<CadEntity>()
        var start = 0
        while (start < entity.vertexCount - 1) {
            val end = min(start + maxVertices, entity.vertexCount)
            out += CadEntity(
                entity.kind,
                entity.easting.copyOfRange(start, end),
                entity.northing.copyOfRange(start, end),
                entity.height?.copyOfRange(start, end),
                entity.color,
            )
            start = end - 1
        }
        return out
    }

    companion object {
        /** Lo que se guarda en memoria antes de escribir los buffers más grandes. */
        const val MAX_BUFFERED_BYTES = 32L * 1024 * 1024

        /** Lo que puede pesar un solo elemento: el `CursorWindow` de Android admite filas de hasta 2 MB. */
        const val MAX_ENTITY_BYTES = 1_500_000

        private const val CELL_METRES = 500.0
        private const val CELL_DEGREES = 0.005
        private const val BATCH_CHUNKS = 64

        /** Lo que el bloque de un trozo gasta en su cabecera (el número de elementos). */
        private const val CHUNK_HEADER_BYTES = 8
        private const val REPORT_EVERY = 64 * 1024
    }
}
