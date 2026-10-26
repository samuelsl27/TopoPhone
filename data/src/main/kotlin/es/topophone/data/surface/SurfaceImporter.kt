package es.topophone.data.surface

import es.topophone.core.formats.landxml.LandXmlException
import es.topophone.core.formats.landxml.LandXmlProblem
import es.topophone.core.formats.landxml.LandXmlReader
import es.topophone.core.formats.landxml.LandXmlScan
import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.core.survey.SurfaceProjection
import es.topophone.core.survey.SurfaceProjector
import es.topophone.core.survey.TinTiler
import es.topophone.data.io.CountingInputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Por qué no se importó o no se pudo leer un LandXML (S042/RF-3, RF-11). */
enum class SurfaceImportFailure {
    NOT_LANDXML,
    UNSUPPORTED_VERSION,
    MALFORMED,
    NO_SURFACE,
    OUTSIDE_CRS_AREA,
    OUTSIDE_ED50_GRID,
    READ_ERROR,
    WRITE_ERROR,
}

/** Lo que se ve de un LandXML antes de importarlo (S042/RF-5, RF-7, RF-8). */
sealed interface SurfaceScanResult {
    data class Found(val scan: LandXmlScan) : SurfaceScanResult

    data class Failed(val reason: SurfaceImportFailure, val version: String? = null) : SurfaceScanResult
}

/** El resumen de una importación terminada (S042/RF-13): lo leído, lo que se descartó y si la superficie quedó activa (RF-17). */
data class SurfaceImportSummary(val name: String, val summary: SurfaceSummary, val droppedFaces: Int, val activated: Boolean)

/** Cómo acabó una importación. Cancelarla lanza `CancellationException` y no deja nada (RF-11). */
sealed interface SurfaceImportResult {
    data class Imported(val surfaceId: String, val summary: SurfaceImportSummary) : SurfaceImportResult

    data class Failed(val reason: SurfaceImportFailure, val version: String? = null) : SurfaceImportResult
}

/**
 * Importa una superficie de un LandXML a un proyecto (S042/RF-5 a RF-15): lo lee por flujo, pasa los vértices al sistema y a los
 * metros del proyecto, reparte los triángulos en teselas y las escribe por tandas. La superficie no existe para el resto de la app
 * hasta que está entera: cancelar o fallar lo borra todo (RF-11).
 */
class SurfaceImporter(
    private val repository: SurfaceRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** La exploración que da las superficies del fichero y su unidad antes de importar: una pasada que no guarda nada (RF-5, RF-8). */
    suspend fun scan(openStream: () -> InputStream, onProgress: (Long) -> Unit = {}): SurfaceScanResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        try {
            SurfaceScanResult.Found(CountingInputStream(openStream(), onProgress, job::ensureActive).use { LandXmlReader.scan(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: LandXmlException) {
            SurfaceScanResult.Failed(e.problem.toFailure(), e.version)
        } catch (e: XmlFormatException) {
            SurfaceScanResult.Failed(SurfaceImportFailure.MALFORMED)
        } catch (e: Exception) {
            SurfaceScanResult.Failed(SurfaceImportFailure.READ_ERROR)
        }
    }

    /**
     * Importa la superficie número [surfaceIndex] del fichero que da [openStream] (la [es.topophone.core.formats.landxml.LandXmlSurfaceInfo.index]
     * de [scan]) como una superficie nueva del proyecto, con las coordenadas en [source] y la [unit] elegidas. [onProgress] recibe los
     * bytes leídos. Lanza `CancellationException` si se cancela; en ese caso, y si falla algo, no queda nada guardado.
     */
    suspend fun import(
        projectId: String,
        crs: ProjectCrs,
        fileName: String,
        surfaceIndex: Int,
        source: CadSourceCrs,
        unit: LinearUnit,
        openStream: () -> InputStream,
        onProgress: (Long) -> Unit = {},
    ): SurfaceImportResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val surfaceId = newId()
        var begun = false
        try {
            val read = CountingInputStream(openStream(), onProgress, job::ensureActive).use { LandXmlReader.read(it, surfaceIndex) }
            job.ensureActive()
            if (read == null || read.triangleCount == 0) return@withContext SurfaceImportResult.Failed(SurfaceImportFailure.NO_SURFACE)
            val converted = when (val projection = SurfaceProjector(crs, source, unit).convert(read.easting, read.northing, read.height)) {
                is SurfaceProjection.Rejected -> return@withContext SurfaceImportResult.Failed(projection.reason.toFailure())
                is SurfaceProjection.Converted -> projection
            }
            job.ensureActive()
            val cells = TinTiler.tile(converted.easting, converted.northing, converted.height, read.triangles)
            if (cells.isEmpty()) return@withContext SurfaceImportResult.Failed(SurfaceImportFailure.NO_SURFACE)
            job.ensureActive()

            writing { repository.begin(SurfaceDraft(surfaceId, projectId, read.name, fileName, clock(), source, unit)) }
            begun = true
            for (batch in cells.chunked(TILES_PER_BATCH)) {
                job.ensureActive()
                writing {
                    repository.addTiles(
                        surfaceId,
                        batch.map { SurfaceTileWrite(it.region, it.tile.triangleCount, SurfaceTileCodec.encode(it.tile)) },
                    )
                }
            }
            val used = usedExtent(converted.easting, converted.northing, converted.height, read.triangles)
            val summary = SurfaceSummary(
                extent = used.extent,
                minElevationM = used.minHeight,
                maxElevationM = used.maxHeight,
                vertexCount = read.pointCount,
                triangleCount = read.triangleCount,
                holeCount = read.hiddenFaceCount,
            )
            job.ensureActive()
            val activated = writing { repository.finish(surfaceId, summary) }
            SurfaceImportResult.Imported(surfaceId, SurfaceImportSummary(read.name, summary, read.droppedFaceCount, activated))
        } catch (e: CancellationException) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(surfaceId) } }
            throw e
        } catch (e: WriteFailure) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(surfaceId) } }
            SurfaceImportResult.Failed(SurfaceImportFailure.WRITE_ERROR)
        } catch (e: LandXmlException) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(surfaceId) } }
            SurfaceImportResult.Failed(e.problem.toFailure(), e.version)
        } catch (e: XmlFormatException) {
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(surfaceId) } }
            SurfaceImportResult.Failed(SurfaceImportFailure.MALFORMED)
        } catch (e: Exception) {
            // Un fallo al leer el fichero o al repartir los triángulos, distinto de uno al escribir.
            if (begun) withContext(NonCancellable) { runCatching { repository.abort(surfaceId) } }
            SurfaceImportResult.Failed(SurfaceImportFailure.READ_ERROR)
        }
    }

    /** Un fallo al escribir en la base de datos: la superficie no se guarda (RF-11). */
    private class WriteFailure(cause: Throwable) : RuntimeException(cause)

    private suspend fun <T> writing(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw WriteFailure(e)
    }

    private class Used(val extent: SurfaceExtent, val minHeight: Double, val maxHeight: Double)

    /** El recuadro y las cotas de los vértices que algún triángulo cita: la extensión exacta del resumen (RF-13). */
    private fun usedExtent(easting: DoubleArray, northing: DoubleArray, height: DoubleArray, triangles: IntArray): Used {
        var minE = Double.POSITIVE_INFINITY
        var maxE = Double.NEGATIVE_INFINITY
        var minN = Double.POSITIVE_INFINITY
        var maxN = Double.NEGATIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY
        var maxZ = Double.NEGATIVE_INFINITY
        for (v in triangles) {
            minE = minOf(minE, easting[v])
            maxE = maxOf(maxE, easting[v])
            minN = minOf(minN, northing[v])
            maxN = maxOf(maxN, northing[v])
            minZ = minOf(minZ, height[v])
            maxZ = maxOf(maxZ, height[v])
        }
        return Used(SurfaceExtent(minE, maxE, minN, maxN), minZ, maxZ)
    }

    private fun LandXmlProblem.toFailure() = when (this) {
        LandXmlProblem.NOT_LANDXML -> SurfaceImportFailure.NOT_LANDXML
        LandXmlProblem.UNSUPPORTED_VERSION -> SurfaceImportFailure.UNSUPPORTED_VERSION
    }

    private fun CadIgnoredReason.toFailure() = when (this) {
        CadIgnoredReason.OUTSIDE_ED50_GRID -> SurfaceImportFailure.OUTSIDE_ED50_GRID
        else -> SurfaceImportFailure.OUTSIDE_CRS_AREA
    }

    private companion object {
        /** Las teselas que se escriben en una sola transacción: a lo sumo 8 × 256 kB. */
        const val TILES_PER_BATCH = 8
    }
}
