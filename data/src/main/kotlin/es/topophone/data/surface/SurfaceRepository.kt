package es.topophone.data.surface

import androidx.room.withTransaction
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.surface.RasterInfo
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.RasterTileCodec
import es.topophone.core.model.surface.RasterValueType
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceKind
import es.topophone.core.model.surface.SurfaceNames
import es.topophone.core.model.surface.SurfaceOutline
import es.topophone.core.model.surface.SurfaceOutlineCodec
import es.topophone.core.model.surface.SurfaceTile
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.core.survey.TileMerger
import es.topophone.core.survey.TinOutline
import es.topophone.data.db.TopoPhoneDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Una superficie que se empieza a importar: lo que se sabe antes de leer sus triángulos (S042/RF-6, RF-7, RF-14). */
data class SurfaceDraft(
    val id: String,
    val projectId: String,
    val name: String,
    val fileName: String,
    val importedAtMs: Long,
    val source: CadSourceCrs,
    val unit: LinearUnit,
)

/** Una tesela que se va a guardar: su celda y su bloque ya codificado con [SurfaceTileCodec] (S042, plan A3). */
class SurfaceTileWrite(val region: SurfaceExtent, val triangleCount: Int, val bytes: ByteArray)

/** Lo que se sabe de una superficie al terminar de importarla (S042/RF-13). */
data class SurfaceSummary(
    val extent: SurfaceExtent,
    val minElevationM: Double,
    val maxElevationM: Double,
    val vertexCount: Int,
    val triangleCount: Int,
    val holeCount: Int,
)

/**
 * Un ráster de cotas que se empieza a importar (S045/RF-14): lo que se sabe antes de leer sus celdas. [grid] da la malla en el sistema
 * [source] (los recuentos de celdas se fijan al terminar) y [unit] es la unidad de las cotas.
 */
data class RasterDraft(
    val id: String,
    val projectId: String,
    val name: String,
    val fileName: String,
    val importedAtMs: Long,
    val source: CadSourceCrs,
    val unit: LinearUnit,
    val grid: RasterInfo,
)

/** Un bloque que se va a guardar: su posición en la cuadrícula de bloques y su contenido ya codificado con `RasterTileCodec`. */
class RasterTileWrite(val tileColumn: Int, val tileRow: Int, val bytes: ByteArray)

/**
 * Lo que se sabe de un ráster al terminar de importarlo (S045/RF-16): [extent] en el sistema del proyecto, y las cotas mínima y máxima en
 * metros tal como vienen, con las celdas con y sin dato.
 */
data class RasterSummary(
    val extent: SurfaceExtent,
    val minElevationM: Double,
    val maxElevationM: Double,
    val dataCells: Long,
    val noDataCells: Long,
)

/** La celda de una tesela guardada, sin su bloque (S042, plan A4). */
class SurfaceCell(val tileId: Long, val region: SurfaceExtent, val triangleCount: Int)

/**
 * Las superficies de un proyecto (S042). Una superficie se escribe por tandas y solo existe para el resto de la app cuando [finish] la
 * pasa a `READY`: [abort] y [cleanUpUnfinished] borran lo que queda a medias (RF-11, RNF-T6). Cada proyecto tiene una superficie activa
 * o ninguna y un desplazamiento vertical (RF-16, RF-19).
 */
interface SurfaceRepository {
    /** Abre una superficie en estado `IMPORTING`; el recuadro, las cotas y los recuentos se fijan en [finish]. */
    suspend fun begin(draft: SurfaceDraft)

    /** Guarda una tanda de teselas en una sola transacción. */
    suspend fun addTiles(surfaceId: String, tiles: List<SurfaceTileWrite>)

    /** Abre un ráster de cotas en estado `IMPORTING` (S045/RF-13); la extensión, las cotas y los recuentos se fijan en [finishRaster]. */
    suspend fun beginRaster(draft: RasterDraft)

    /** Guarda una tanda de bloques de un ráster en una sola transacción. */
    suspend fun addRasterTiles(surfaceId: String, tiles: List<RasterTileWrite>)

    /** Cierra el ráster como [finish] cierra una superficie: pasa a `READY` y, si el proyecto no tenía activa, la deja activa (RF-17). */
    suspend fun finishRaster(surfaceId: String, summary: RasterSummary): Boolean

    /** El bloque de la columna [tileColumn] y la fila [tileRow] de un ráster terminado; null si no existe (S045, plan A4). */
    suspend fun rasterTile(surfaceId: String, tileColumn: Int, tileRow: Int): RasterTile?

    /**
     * Cierra la superficie: pasa a `READY` en una transacción y, si el proyecto no tenía superficie activa, la deja activa (RF-17).
     * Devuelve si quedó activa.
     */
    suspend fun finish(surfaceId: String, summary: SurfaceSummary): Boolean

    /** Borra una superficie a medias con sus teselas. */
    suspend fun abort(surfaceId: String)

    /** Borra todo lo que quedó a medias (la app se cerró en plena importación); devuelve cuántas superficies. */
    suspend fun cleanUpUnfinished(): Int

    /** Las superficies terminadas del proyecto por fecha de importación (RF-16). */
    fun surfaces(projectId: String): Flow<List<Surface>>

    /** El identificador de la superficie activa del proyecto, o null si no hay (RF-16). */
    fun activeSurfaceId(projectId: String): Flow<String?>

    /** Hace activa la superficie [surfaceId] del proyecto, o ninguna si es null. false si la superficie no existe o no es de ese proyecto. */
    suspend fun activate(projectId: String, surfaceId: String?): Boolean

    /** Borra una superficie con sus teselas; si era la activa el proyecto queda sin ninguna. No toca ningún punto (RF-18, RF-32). */
    suspend fun delete(surfaceId: String)

    /** El desplazamiento vertical del proyecto en metros (RF-19). */
    fun verticalOffset(projectId: String): Flow<Double>

    /** Cambia el desplazamiento vertical. [IllegalArgumentException] si no es un número finito (RF-20 lo valida antes). */
    suspend fun setVerticalOffset(projectId: String, offsetM: Double)

    /** Las celdas de las teselas de una superficie, sin sus bloques. */
    suspend fun cells(surfaceId: String): List<SurfaceCell>

    /** La tesela [tileId] decodificada; null si no existe. */
    suspend fun tile(tileId: Long): SurfaceTile?

    /** Muestra u oculta la superficie en el mapa (S061/RF-3). */
    suspend fun setVisible(surfaceId: String, visible: Boolean)

    /**
     * El contorno de una superficie TIN (S061/RF-6). Si no está guardado (una superficie importada antes de S061 o restaurada de una
     * copia), lo calcula con sus teselas y lo guarda para la siguiente vez. Null si no existe o es un ráster.
     */
    suspend fun outline(surfaceId: String): SurfaceOutline?

    /** Guarda el contorno de una superficie, en lugar del que tuviera. */
    suspend fun saveOutline(surfaceId: String, outline: SurfaceOutline)
}

class RoomSurfaceRepository(private val database: TopoPhoneDatabase) : SurfaceRepository {
    private val dao = database.surfaces()

    override suspend fun begin(draft: SurfaceDraft) = dao.insertSurface(
        SurfaceEntity(
            id = draft.id,
            projectId = draft.projectId,
            name = draft.name,
            fileName = draft.fileName,
            importedAtMs = draft.importedAtMs,
            sourceCrs = draft.source.encode(),
            sourceUnit = draft.unit.name,
            status = IMPORTING,
            minEasting = null,
            maxEasting = null,
            minNorthing = null,
            maxNorthing = null,
            minHeight = null,
            maxHeight = null,
            vertexCount = 0,
            triangleCount = 0,
            holeCount = 0,
        ),
    )

    override suspend fun addTiles(surfaceId: String, tiles: List<SurfaceTileWrite>) {
        if (tiles.isEmpty()) return
        dao.insertTiles(
            tiles.map {
                SurfaceTileEntity(
                    surfaceId = surfaceId,
                    minEasting = it.region.minEasting,
                    maxEasting = it.region.maxEasting,
                    minNorthing = it.region.minNorthing,
                    maxNorthing = it.region.maxNorthing,
                    triangleCount = it.triangleCount,
                    data = it.bytes,
                )
            },
        )
    }

    override suspend fun finish(surfaceId: String, summary: SurfaceSummary): Boolean = database.withTransaction {
        val projectId = dao.projectOf(surfaceId) ?: return@withTransaction false
        dao.markReady(
            surfaceId,
            summary.extent.minEasting,
            summary.extent.maxEasting,
            summary.extent.minNorthing,
            summary.extent.maxNorthing,
            summary.minElevationM,
            summary.maxElevationM,
            summary.vertexCount,
            summary.triangleCount,
            summary.holeCount,
        )
        activateIfFirst(projectId, surfaceId)
    }

    override suspend fun beginRaster(draft: RasterDraft) = dao.insertSurface(
        SurfaceEntity(
            id = draft.id,
            projectId = draft.projectId,
            name = draft.name,
            fileName = draft.fileName,
            importedAtMs = draft.importedAtMs,
            sourceCrs = draft.source.encode(),
            sourceUnit = draft.unit.name,
            status = IMPORTING,
            minEasting = null,
            maxEasting = null,
            minNorthing = null,
            maxNorthing = null,
            minHeight = null,
            maxHeight = null,
            vertexCount = 0,
            triangleCount = 0,
            holeCount = 0,
            kind = SurfaceKind.RASTER.name,
            heightSystem = draft.grid.heightSystem.name,
            valueType = draft.grid.valueType.name,
            columnCount = draft.grid.columns,
            rowCount = draft.grid.rows,
            originX = draft.grid.originX,
            originY = draft.grid.originY,
            stepX = draft.grid.stepX,
            stepY = draft.grid.stepY,
        ),
    )

    override suspend fun addRasterTiles(surfaceId: String, tiles: List<RasterTileWrite>) {
        if (tiles.isEmpty()) return
        dao.insertRasterTiles(
            tiles.map { RasterTileEntity(surfaceId = surfaceId, tileColumn = it.tileColumn, tileRow = it.tileRow, data = it.bytes) },
        )
    }

    override suspend fun finishRaster(surfaceId: String, summary: RasterSummary): Boolean = database.withTransaction {
        val projectId = dao.projectOf(surfaceId) ?: return@withTransaction false
        dao.markRasterReady(
            surfaceId,
            summary.extent.minEasting,
            summary.extent.maxEasting,
            summary.extent.minNorthing,
            summary.extent.maxNorthing,
            summary.minElevationM,
            summary.maxElevationM,
            summary.dataCells,
            summary.noDataCells,
        )
        activateIfFirst(projectId, surfaceId)
    }

    override suspend fun rasterTile(surfaceId: String, tileColumn: Int, tileRow: Int): RasterTile? =
        dao.rasterTile(surfaceId, tileColumn, tileRow)?.let { RasterTileCodec.decode(it) }

    /** Deja activa la superficie [surfaceId] si el proyecto no tenía ninguna (RF-17); devuelve si quedó activa. */
    private suspend fun activateIfFirst(projectId: String, surfaceId: String): Boolean {
        val firstActive = dao.activeId(projectId) == null
        if (firstActive) dao.setActive(projectId, surfaceId)
        return firstActive
    }

    override suspend fun abort(surfaceId: String) = dao.deleteSurface(surfaceId)

    override suspend fun cleanUpUnfinished(): Int = dao.deleteUnfinished()

    override fun surfaces(projectId: String): Flow<List<Surface>> = dao.readySurfaces(projectId).map { list -> list.map { it.toModel() } }

    override fun activeSurfaceId(projectId: String): Flow<String?> = dao.activeSurfaceId(projectId)

    override suspend fun activate(projectId: String, surfaceId: String?): Boolean = database.withTransaction {
        if (surfaceId != null) {
            val surface = dao.readySurface(surfaceId)
            if (surface == null || surface.projectId != projectId) return@withTransaction false
        }
        dao.setActive(projectId, surfaceId)
        true
    }

    override suspend fun delete(surfaceId: String) = database.withTransaction {
        dao.clearActive(surfaceId)
        dao.deleteSurface(surfaceId)
    }

    override fun verticalOffset(projectId: String): Flow<Double> = dao.verticalOffset(projectId).map { it ?: 0.0 }

    override suspend fun setVerticalOffset(projectId: String, offsetM: Double) {
        require(offsetM.isFinite()) { "El desplazamiento vertical tiene que ser un número" }
        dao.setVerticalOffset(projectId, offsetM)
    }

    override suspend fun cells(surfaceId: String): List<SurfaceCell> = dao.cells(surfaceId).map {
        SurfaceCell(it.id, SurfaceExtent(it.minEasting, it.maxEasting, it.minNorthing, it.maxNorthing), it.triangleCount)
    }

    override suspend fun tile(tileId: Long): SurfaceTile? = dao.tile(tileId)?.let { SurfaceTileCodec.decode(it.data) }

    override suspend fun setVisible(surfaceId: String, visible: Boolean) = dao.setVisible(surfaceId, visible)

    override suspend fun outline(surfaceId: String): SurfaceOutline? {
        val surface = dao.readySurface(surfaceId) ?: return null
        if (surface.kind != SurfaceKind.TIN.name) return null
        val stored = dao.outlineParts(surfaceId)
        if (stored.isNotEmpty()) return SurfaceOutlineCodec.decode(stored)
        // S061 plan A2: se une la malla de sus teselas (los triángulos repetidos entre teselas quedan una vez) y se calcula.
        val merged = TileMerger.merge(dao.cells(surfaceId).mapNotNull { tile(it.id) })
        val outline = TinOutline.rings(merged.easting, merged.northing, merged.triangles)
        saveOutline(surfaceId, outline)
        return outline
    }

    override suspend fun saveOutline(surfaceId: String, outline: SurfaceOutline) = database.withTransaction {
        dao.deleteOutline(surfaceId)
        dao.insertOutline(SurfaceOutlineCodec.encode(outline).mapIndexed { part, bytes -> SurfaceOutlineEntity(surfaceId, part, bytes) })
    }

    private companion object {
        const val IMPORTING = "IMPORTING"
    }
}

private fun SurfaceEntity.toModel() = Surface(
    id = id,
    projectId = projectId,
    name = SurfaceNames.display(name, fileName), // S061/RF-1, RF-2
    fileName = fileName,
    importedAtMs = importedAtMs,
    source = CadSourceCrs.decode(sourceCrs),
    unit = LinearUnit.valueOf(sourceUnit),
    extent = SurfaceExtent(minEasting ?: 0.0, maxEasting ?: 0.0, minNorthing ?: 0.0, maxNorthing ?: 0.0),
    minElevationM = minHeight ?: 0.0,
    maxElevationM = maxHeight ?: 0.0,
    vertexCount = vertexCount,
    triangleCount = triangleCount,
    holeCount = holeCount,
    kind = SurfaceKind.valueOf(kind),
    raster = rasterInfo(),
    visible = visible,
)

/** La malla del ráster de la fila, o null si es un TIN (S045). */
private fun SurfaceEntity.rasterInfo(): RasterInfo? {
    if (kind != SurfaceKind.RASTER.name) return null
    return RasterInfo(
        columns = columnCount ?: 1,
        rows = rowCount ?: 1,
        originX = originX ?: 0.0,
        originY = originY ?: 0.0,
        stepX = stepX ?: 1.0,
        stepY = stepY ?: 1.0,
        valueType = RasterValueType.valueOf(valueType ?: RasterValueType.FLOAT32.name),
        heightSystem = HeightSystem.valueOf(heightSystem ?: HeightSystem.ORTHOMETRIC.name),
        dataCells = dataCells ?: 0,
        noDataCells = noDataCells ?: 0,
    )
}
