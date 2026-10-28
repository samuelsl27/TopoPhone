package es.topophone.data.surface

import es.topophone.core.formats.raster.FileRasterSource
import es.topophone.core.formats.raster.RasterFormatException
import es.topophone.core.formats.raster.RasterHeader
import es.topophone.core.formats.raster.RasterLimits
import es.topophone.core.formats.raster.RasterReadStats
import es.topophone.core.formats.raster.RasterReader
import es.topophone.core.formats.raster.RasterScan
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.RasterInfo
import es.topophone.core.model.surface.RasterTileCodec
import es.topophone.core.survey.RasterFootprint
import es.topophone.core.survey.RasterTiler
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Por qué no se importó o no se pudo leer un ráster (S045/RF-3 a RF-5, RF-13, plan D6). */
enum class RasterImportFailure {
    NOT_A_RASTER,
    EMPTY,
    NOT_GEOREFERENCED,
    BIGTIFF,
    UNSUPPORTED_COMPRESSION,
    ROTATED,
    COLOUR,
    UNSUPPORTED_DATA_TYPE,
    TOO_LARGE,
    TOO_WIDE,
    NO_DATA_CELLS,
    OUTSIDE_CRS_AREA,
    OUTSIDE_ED50_GRID,
    READ_ERROR,
    WRITE_ERROR,
}

/** El fichero elegido, copiado a la caché para poder leerlo a saltos (S045, plan D4); [close] lo borra. */
class StagedRaster internal constructor(val file: File) : Closeable {
    override fun close() {
        file.delete()
    }
}

/** Cómo acabó la copia del fichero elegido a la caché (S045/RF-5, RF-13). */
sealed interface RasterStageResult {
    data class Staged(val staged: StagedRaster) : RasterStageResult

    data class Failed(val reason: RasterImportFailure) : RasterStageResult
}

/** Lo que se ve de un ráster antes de importarlo (S045/RF-3, RF-4, RF-6 a RF-9, RF-11). */
sealed interface RasterScanResult {
    data class Found(val header: RasterHeader) : RasterScanResult

    data class Failed(val reason: RasterImportFailure) : RasterScanResult
}

/** Lo que el usuario elige al importar: el sistema de las coordenadas, la unidad y el sistema de alturas de las cotas (S045/RF-8 a RF-10). */
data class RasterImportOptions(val source: CadSourceCrs, val unit: LinearUnit, val heightSystem: HeightSystem)

/** El resumen de una importación terminada (S045/RF-16): lo guardado y si el ráster quedó activo (RF-17). */
data class RasterImportSummary(
    val name: String,
    val summary: RasterSummary,
    val columns: Int,
    val rows: Int,
    val stepX: Double,
    val stepY: Double,
    val activated: Boolean,
)

/** Lo que da una pasada por las celdas del fichero (S045/RF-11): los recuentos y las cotas mínima y máxima, sin guardar nada. */
sealed interface RasterMeasureResult {
    data class Measured(val stats: RasterReadStats) : RasterMeasureResult

    data class Failed(val reason: RasterImportFailure) : RasterMeasureResult
}

/** Cómo acabó una importación. Cancelarla lanza `CancellationException` y no deja nada (RF-13). */
sealed interface RasterImportResult {
    data class Imported(val surfaceId: String, val summary: RasterImportSummary) : RasterImportResult

    data class Failed(val reason: RasterImportFailure) : RasterImportResult
}

/**
 * Importa un ráster de cotas a un proyecto (S045/RF-3 a RF-16, plan A2): copia el fichero a [tempDirectory] (un GeoTIFF necesita acceso
 * directo y el selector solo da un flujo), lo lee por bandas de 128 filas sin cargarlo entero, lo corta en bloques y los escribe por
 * tandas. El ráster no existe para el resto de la app hasta que está entero: cancelar o fallar lo borra todo (RF-13).
 * El temporal lo cierra quien lo pidió ([StagedRaster.close]), para poder reintentar con otras opciones.
 */
class RasterImporter(
    private val repository: SurfaceRepository,
    val tempDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxFileBytes: Long = RasterLimits.MAX_FILE_BYTES,
) {
    private class TooLarge : RuntimeException()

    private class ReadFailure(cause: Throwable) : RuntimeException(cause)

    private class WriteFailure(cause: Throwable) : RuntimeException(cause)

    /**
     * Copia el contenido que da [openStream] a un fichero temporal con progreso (bytes copiados) y comprobando la cancelación. Un fichero
     * de más de [maxFileBytes] se deja de copiar en cuanto los pasa (RF-5); si no se puede leer o escribir, no queda ningún temporal.
     */
    suspend fun stage(openStream: () -> InputStream, onProgress: (Long) -> Unit = {}): RasterStageResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        tempDirectory.mkdirs()
        val file = File(tempDirectory, "raster-${newId()}.tmp")
        try {
            var total = 0L
            openStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var sinceReport = 0L
                    while (true) {
                        job.ensureActive()
                        val n = try {
                            input.read(buffer)
                        } catch (e: IOException) {
                            throw ReadFailure(e)
                        }
                        if (n < 0) break
                        total += n
                        if (total > maxFileBytes) throw TooLarge()
                        try {
                            output.write(buffer, 0, n)
                        } catch (e: IOException) {
                            throw WriteFailure(e)
                        }
                        sinceReport += n
                        if (sinceReport >= REPORT_EVERY) {
                            sinceReport = 0
                            onProgress(total)
                        }
                    }
                }
            }
            onProgress(total)
            RasterStageResult.Staged(StagedRaster(file))
        } catch (e: CancellationException) {
            file.delete()
            throw e
        } catch (e: TooLarge) {
            file.delete()
            RasterStageResult.Failed(RasterImportFailure.TOO_LARGE)
        } catch (e: WriteFailure) {
            file.delete()
            RasterStageResult.Failed(RasterImportFailure.WRITE_ERROR)
        } catch (e: Exception) {
            file.delete()
            RasterStageResult.Failed(RasterImportFailure.READ_ERROR)
        }
    }

    /** La cabecera del fichero copiado, o por qué no se lee (RF-3 a RF-9): una pasada que no lee ninguna celda ni guarda nada. */
    fun scan(staged: StagedRaster): RasterScanResult = try {
        FileRasterSource(staged.file).use { source ->
            when (val scan = RasterReader.scan(source)) {
                is RasterScan.Found -> RasterScanResult.Found(scan.header)
                is RasterScan.Rejected -> RasterScanResult.Failed(RasterImportFailure.valueOf(scan.reason.name))
            }
        }
    } catch (e: IOException) {
        RasterScanResult.Failed(RasterImportFailure.READ_ERROR)
    }

    /**
     * Lee todas las celdas del fichero copiado sin guardar nada y da el recuento de celdas con y sin dato y las cotas mínima y máxima
     * (RF-3, RF-11): es lo que enseña la pantalla de importación. [onProgress] recibe las filas leídas y las totales. Lanza
     * `CancellationException` si se cancela.
     */
    suspend fun measure(staged: StagedRaster, onProgress: (Long, Long) -> Unit = { _, _ -> }): RasterMeasureResult =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()
            try {
                FileRasterSource(staged.file).use { source ->
                    val header = when (val scan = RasterReader.scan(source)) {
                        is RasterScan.Found -> scan.header
                        is RasterScan.Rejected -> return@withContext RasterMeasureResult.Failed(
                            RasterImportFailure.valueOf(scan.reason.name),
                        )
                    }
                    val stats = RasterReader.readBands(source, { first, rows, _ ->
                        job.ensureActive()
                        onProgress((first + rows).toLong(), header.rows.toLong())
                    })
                    RasterMeasureResult.Measured(stats)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RasterMeasureResult.Failed(RasterImportFailure.READ_ERROR)
            }
        }

    /**
     * Importa el ráster de [staged] al proyecto [projectId] con las [options] elegidas. [onProgress] recibe las filas leídas y las filas
     * totales. Lanza `CancellationException` si se cancela; en ese caso, y si algo falla, no queda nada guardado.
     */
    suspend fun import(
        projectId: String,
        crs: ProjectCrs,
        staged: StagedRaster,
        fileName: String,
        options: RasterImportOptions,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): RasterImportResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val surfaceId = newId()
        var begun = false
        try {
            FileRasterSource(staged.file).use { source ->
                val header = when (val scan = RasterReader.scan(source)) {
                    is RasterScan.Found -> scan.header
                    is RasterScan.Rejected -> return@withContext RasterImportResult.Failed(RasterImportFailure.valueOf(scan.reason.name))
                }
                val extent = when (
                    val footprint = RasterFootprint.of(
                        crs,
                        options.source,
                        header.originX,
                        header.originY,
                        header.stepX,
                        header.stepY,
                        header.columns,
                        header.rows,
                    )
                ) {
                    is RasterFootprint.Outside -> return@withContext RasterImportResult.Failed(footprint.reason.toFailure())
                    is RasterFootprint.Inside -> footprint.extent
                }
                job.ensureActive()

                val name = fileName.substringBeforeLast('.', fileName)
                val grid = RasterInfo(
                    columns = header.columns,
                    rows = header.rows,
                    originX = header.originX,
                    originY = header.originY,
                    stepX = header.stepX,
                    stepY = header.stepY,
                    valueType = header.valueType,
                    heightSystem = options.heightSystem,
                    dataCells = 0,
                    noDataCells = 0,
                )
                writing {
                    repository.beginRaster(RasterDraft(surfaceId, projectId, name, fileName, clock(), options.source, options.unit, grid))
                }
                begun = true

                val stats = RasterReader.readBands(source, { first, rows, band ->
                    job.ensureActive()
                    val writes = RasterTiler.cut(first, rows, header.columns, band, header.valueType)
                        .map { RasterTileWrite(it.tileColumn, it.tileRow, RasterTileCodec.encode(it.tile)) }
                    runBlocking { writing { repository.addRasterTiles(surfaceId, writes) } }
                    onProgress((first + rows).toLong(), header.rows.toLong())
                })
                job.ensureActive()
                if (stats.dataCells == 0L) {
                    withContext(NonCancellable) { runCatching { repository.abort(surfaceId) } }
                    return@withContext RasterImportResult.Failed(RasterImportFailure.NO_DATA_CELLS)
                }

                val summary = RasterSummary(
                    extent = extent,
                    minElevationM = stats.minValue * options.unit.metres,
                    maxElevationM = stats.maxValue * options.unit.metres,
                    dataCells = stats.dataCells,
                    noDataCells = stats.noDataCells,
                )
                val activated = writing { repository.finishRaster(surfaceId, summary) }
                RasterImportResult.Imported(
                    surfaceId,
                    RasterImportSummary(name, summary, header.columns, header.rows, header.stepX, header.stepY, activated),
                )
            }
        } catch (e: CancellationException) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(surfaceId) } }
            throw e
        } catch (e: WriteFailure) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(surfaceId) } }
            RasterImportResult.Failed(RasterImportFailure.WRITE_ERROR)
        } catch (e: RasterFormatException) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(surfaceId) } }
            RasterImportResult.Failed(RasterImportFailure.READ_ERROR)
        } catch (e: Exception) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(surfaceId) } }
            RasterImportResult.Failed(RasterImportFailure.READ_ERROR)
        }
    }

    private suspend fun <T> writing(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw WriteFailure(e)
    }

    private fun CadIgnoredReason.toFailure() = when (this) {
        CadIgnoredReason.OUTSIDE_ED50_GRID -> RasterImportFailure.OUTSIDE_ED50_GRID
        else -> RasterImportFailure.OUTSIDE_CRS_AREA
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
        const val REPORT_EVERY = 256 * 1024L
    }
}
