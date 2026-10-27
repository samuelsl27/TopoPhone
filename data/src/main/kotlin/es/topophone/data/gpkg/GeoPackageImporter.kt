package es.topophone.data.gpkg

import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.cad.GpkgAttributesCodec
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgImportSummary
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.GpkgLayerSummary
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.ImportAreaFilter
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.cad.CadChunkWriter
import es.topophone.data.cad.CadDrawingImporter
import es.topophone.data.cad.CadRepository
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Lo que el usuario decide de cada capa marcada: su sistema (el propuesto o el elegido) y su campo de nombre (S048/RF-8, RF-9, RF-21). */
data class GpkgLayerChoice(val layer: GpkgLayerInfo, val source: CadSourceCrs, val nameField: String? = null, val wgs84: Boolean = false)

/** Lo que se importa: las capas marcadas, si se usan las cotas (RF-19) y la zona (RF-13). */
data class GpkgImportOptions(val layers: List<GpkgLayerChoice>, val useElevations: Boolean = true, val area: ImportArea = ImportArea.All)

/** Por qué no se importó un GeoPackage (S048/RF-16, RF-30). */
enum class GpkgImportFailure { NO_OBJECTS, TOO_MANY_OBJECTS, READ_ERROR, WRITE_ERROR }

/** Cómo acabó una importación. Cancelarla lanza `CancellationException` y no deja nada (RF-30). */
sealed interface GpkgImportResult {
    class Imported(val drawingId: String, val summary: GpkgImportSummary) : GpkgImportResult

    data class Failed(val reason: GpkgImportFailure) : GpkgImportResult
}

/**
 * Importa las capas marcadas de un GeoPackage abierto como **un** dibujo del proyecto, con una capa de diseño por tabla y tipo
 * (S048/RF-8 a RF-30): cada objeto se lee de uno en uno, se pasa al sistema del proyecto con [GpkgFeatureConverter] (un vértice fuera
 * del ámbito descarta el objeto entero), entra si algún vértice cae en la zona, y sus elementos se escriben por trozos y tandas con el
 * mismo [CadChunkWriter] que el DXF, junto con sus atributos. El dibujo no existe para el resto de la app hasta que está entero:
 * cancelar o fallar lo borra todo (RF-30, RNF-T6).
 */
class GeoPackageImporter(
    private val repository: CadRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxBufferedBytes: Long = CadDrawingImporter.MAX_BUFFERED_BYTES,
    private val maxObjects: Int = ImportArea.MAX_OBJECTS,
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
) {
    /** Lo máximo que hubo en los buffers de la última importación, en bytes. */
    @Volatile
    var peakBufferedBytes: Long = 0
        private set

    /**
     * Importa [options] de [session] al proyecto [projectId]. [onProgress] recibe los objetos leídos y los que hay en total en las capas
     * marcadas.
     */
    suspend fun import(
        projectId: String,
        crs: ProjectCrs,
        fileName: String,
        options: GpkgImportOptions,
        session: GpkgSession,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): GpkgImportResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val drawingId = newId()
        val drawing = CadDrawing(
            id = drawingId,
            projectId = projectId,
            fileName = fileName,
            importedAtMs = clock(),
            source = CadSourceCrs.Project, // cada capa trae el suyo; lo guardado ya está en el sistema del proyecto
            unit = LinearUnit.METRE,
            useElevations = options.useElevations,
            hasElevations = options.useElevations,
            origin = CadOrigin.GEOPACKAGE,
        )
        var begun = false
        try {
            writing { repository.begin(drawing) }
            begun = true
            val run = Run(drawingId, crs, options, session, onProgress) { job.ensureActive() }
            run.execute()
            peakBufferedBytes = run.peakBuffered
            if (run.importedTotal == 0) {
                writing { repository.abort(drawingId) }
                return@withContext GpkgImportResult.Failed(GpkgImportFailure.NO_OBJECTS)
            }
            job.ensureActive()
            val hasElevations = options.useElevations && run.anyHeight && run.anyNonZeroHeight
            writing { repository.finish(drawingId, run.layerCounts(), hasElevations, incomplete = false, extent = run.extent()) }
            GpkgImportResult.Imported(
                drawingId,
                run.summary(
                    session.leftOutTables,
                    options.layers.filter {
                        it.wgs84
                    }.map { it.layer.table },
                ),
            )
        } catch (e: CancellationException) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(drawingId) } }
            throw e
        } catch (e: TooManyObjects) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(drawingId) } }
            GpkgImportResult.Failed(GpkgImportFailure.TOO_MANY_OBJECTS)
        } catch (e: WriteFailure) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(drawingId) } }
            GpkgImportResult.Failed(GpkgImportFailure.WRITE_ERROR)
        } catch (e: Exception) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(drawingId) } }
            GpkgImportResult.Failed(GpkgImportFailure.READ_ERROR)
        }
    }

    private class WriteFailure(cause: Throwable) : RuntimeException(cause)

    private class TooManyObjects : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private suspend fun <T> writing(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw WriteFailure(e)
    }

    /** La escritura es suspendida y la lectura de SQLite no: se espera en el hilo de entrada y salida, que no es el principal. */
    private fun write(block: suspend () -> Unit) = runBlocking { writing(block) }

    private data class LayerKey(val table: String, val kind: DesignLayerKind)

    /** Una importación en marcha: lee las capas una a una y reparte sus elementos. */
    private inner class Run(
        private val drawingId: String,
        private val crs: ProjectCrs,
        private val options: GpkgImportOptions,
        private val session: GpkgSession,
        private val onProgress: (Long, Long) -> Unit,
        private val check: () -> Unit,
    ) {
        private val writer = CadChunkWriter(crs.utmZone == null, maxBufferedBytes) { batch -> write { repository.addChunks(batch) } }
        private val states = LinkedHashMap<LayerKey, CadChunkWriter.LayerState>()
        private val summaries = ArrayList<GpkgLayerSummary>()
        private var minE = Double.POSITIVE_INFINITY
        private var maxE = Double.NEGATIVE_INFINITY
        private var minN = Double.POSITIVE_INFINITY
        private var maxN = Double.NEGATIVE_INFINITY

        var importedTotal = 0
            private set
        var anyHeight = false
            private set
        var anyNonZeroHeight = false
            private set

        val peakBuffered: Long get() = writer.peakBuffered

        fun execute() {
            val filter = (options.area as? ImportArea.Circle)?.let { ImportAreaFilter.of(crs, it, calculator) }
            val total = options.layers.sumOf { it.layer.featureCount }
            var done = 0L
            onProgress(0, total)
            for (choice in options.layers) {
                val layer = choice.layer
                val converter = GpkgFeatureConverter(choice.source, crs, options.useElevations, calculator)
                val nameIndex = choice.nameField?.let { field -> layer.columns.indexOfFirst { it.name == field } }?.takeIf { it >= 0 }
                val columns = layer.columns.map { it.name }
                val ignored = HashMap<GpkgIgnoredReason, Int>()
                var imported = 0
                var omitted = 0
                session.forEachFeature(layer, filter?.candidateBox(choice.source)) { feature ->
                    check()
                    if (++done % PROGRESS_EVERY == 0L) onProgress(done, total)
                    val name = nameOf(feature.values.getOrNull(nameIndex ?: -1), feature.fid)
                    when (val conversion = converter.convert(feature.geometry, name)) {
                        is GpkgConversion.Ignored -> ignored.merge(conversion.reason, 1, Int::plus)
                        is GpkgConversion.Converted -> {
                            val inside = filter == null || conversion.parts.any { filter.anyInside(it.easting, it.northing) }
                            if (inside) {
                                if (++importedTotal > maxObjects) throw TooManyObjects()
                                imported++
                                omitted += feature.values.count { it is GpkgValue.Omitted }
                                store(choice, columns, conversion.parts, GpkgAttributesCodec.encodeRow(feature.values))
                            }
                        }
                    }
                }
                val ignoredTotal = ignored.values.sum()
                // Los objetos que el índice espacial dejó fuera ni se han mirado: salen como «fuera de la zona».
                summaries +=
                    GpkgLayerSummary(layer.table, imported, max(0L, layer.featureCount - imported - ignoredTotal).toInt(), ignored, omitted)
            }
            onProgress(done, total)
            writer.flushAll()
        }

        private fun nameOf(value: GpkgValue?, fid: Long): String {
            val text = when (value) {
                is GpkgValue.Text -> value.value
                is GpkgValue.Date -> value.value
                is GpkgValue.Integer -> value.value.toString()
                is GpkgValue.Real -> value.value.toString()
                is GpkgValue.Bool -> value.value.toString()
                else -> null
            }
            return text?.takeIf { it.isNotBlank() } ?: fid.toString()
        }

        private fun store(choice: GpkgLayerChoice, columns: List<String>, parts: List<CadEntity>, row: ByteArray) {
            for (part in parts) {
                val kind = if (part.kind == CadEntityKind.POINT) DesignLayerKind.POINTS else DesignLayerKind.LINES
                val state = states.getOrPut(LayerKey(choice.layer.table, kind)) { createLayer(choice, kind, columns) }
                part.height?.let { heights ->
                    anyHeight = true
                    if (heights.any { it != 0.0 }) anyNonZeroHeight = true
                }
                val box = part.bounds
                minE = min(minE, box.minEasting)
                maxE = max(maxE, box.maxEasting)
                minN = min(minN, box.minNorthing)
                maxN = max(maxN, box.maxNorthing)
                for (piece in CadChunkWriter.split(part)) writer.add(state, piece, row)
            }
        }

        private fun createLayer(choice: GpkgLayerChoice, kind: DesignLayerKind, columns: List<String>): CadChunkWriter.LayerState {
            val state = CadChunkWriter.LayerState(newId(), states.size, columns)
            val layer = DesignLayer(
                id = state.id,
                drawingId = drawingId,
                name = choice.layer.table,
                kind = kind,
                color = CadColor.rgb(PALETTE[state.position % PALETTE.size]),
                position = state.position,
                sourceTable = choice.layer.table,
                nameField = choice.nameField,
            )
            write { repository.addLayer(layer) }
            return state
        }

        fun layerCounts(): Map<String, Int> = states.values.associate { it.id to it.count }

        fun extent(): CadExtent? = if (importedTotal == 0) null else CadExtent(minE, maxE, minN, maxN)

        fun summary(leftOut: List<String>, wgs84: List<String>) = GpkgImportSummary(summaries.toList(), leftOut, wgs84)
    }

    companion object {
        private const val PROGRESS_EVERY = 256L

        /** Los colores de las capas importadas, por orden de aparición y con vuelta (S048/RF-23). */
        val PALETTE = intArrayOf(
            0xE6194B, 0x3CB44B, 0x4363D8, 0xF58231, 0x911EB4, 0x42D4F4, 0xF032E6, 0xBFEF45, 0x469990, 0x9A6324, 0x800000, 0x808000,
        )
    }
}
