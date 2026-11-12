package es.topophone.data.shp

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.shp.DbfReader
import es.topophone.core.formats.shp.ShpBlobEncoder
import es.topophone.core.formats.shp.ShpByteSource
import es.topophone.core.formats.shp.ShpCrsCatalog
import es.topophone.core.formats.shp.ShpEncoding
import es.topophone.core.formats.shp.ShpEncodings
import es.topophone.core.formats.shp.ShpReader
import es.topophone.core.formats.shp.ShpShape
import es.topophone.core.formats.shp.ShxIndex
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.survey.LayerBox
import es.topophone.data.cad.FeatureSession
import es.topophone.data.cad.GpkgFeature
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Un Shapefile abierto de solo lectura: cada capa es un `.shp` de la carpeta temporal de [ShapefileReader] con su `.dbf`, su `.prj` y su
 * `.cpg` (S030/RF-7 a RF-9, RF-15 a RF-21). Cumple [FeatureSession] como un GeoPackage (decisión D1 del plan): da las capas con su tipo,
 * sus columnas y su sistema, y de cada objeto el blob de geometría de GeoPackage (con [ShpBlobEncoder]) y sus atributos. El objeto `n` es
 * el registro `n` del `.shp` y su fila `n` del `.dbf`; no hay índice espacial, así que [forEachFeature] recorre todos los registros y el
 * prefiltro lo hace quien llama con las cabeceras de [forEachHead]. [close] cierra los ficheros y borra la carpeta temporal.
 */
class ShapefileSession private constructor(private val extracted: ShpExtracted, private val states: LinkedHashMap<String, LayerState>) :
    FeatureSession {

    private class LayerState(
        val name: String,
        val shp: ShpReader,
        val index: ShxIndex,
        var dbf: DbfReader?,
        val geometryType: String,
        val crs: GpkgCrs,
        /** El `.cpg` declara una codificación que no se conoce y se lee como Windows-1252 (RF-18). */
        val cpgUnknown: Boolean,
    ) {
        var info: GpkgLayerInfo = describe()

        fun describe() = GpkgLayerInfo(
            table = name,
            geometryColumn = "geometry",
            geometryType = geometryType,
            featureCount = index.size.toLong(),
            srsId = 0,
            columns = dbf?.columns.orEmpty(),
            hasSpatialIndex = false,
        )
    }

    private val closeables = ArrayList<Closeable>()

    override val origin: CadOrigin get() = CadOrigin.SHAPEFILE

    override val layers: List<GpkgLayerInfo> get() = states.values.map { it.info }

    /** Los ficheros sin su `.shp` que se dejaron fuera (RF-5). */
    override val leftOutTables: List<String> get() = extracted.orphans

    /** Los nombres de ZIP que se descartaron por salir de la carpeta. */
    val dropped: List<String> get() = extracted.dropped

    /** El nombre con el que se guarda el dibujo: el del ZIP o el del primer `.shp` (RF-26). */
    val drawingName: String get() = extracted.drawingName

    override fun crs(layer: GpkgLayerInfo): GpkgCrs = state(layer).crs

    /** La codificación con la que se lee el `.dbf` de la capa, o null si no tiene `.dbf` (RF-18). */
    fun encoding(layer: GpkgLayerInfo): ShpEncoding? = state(layer).dbf?.encoding

    /** Si el `.cpg` de la capa declara una codificación que no se conoce y se lee como Windows-1252 (RF-18). */
    fun cpgUnknown(layer: GpkgLayerInfo): Boolean = state(layer).cpgUnknown

    /** Cuántos objetos del `.shp` no tienen registro en un `.dbf` más corto (RF-20). */
    fun objectsWithoutRecord(layer: GpkgLayerInfo): Int {
        val state = state(layer)
        val dbf = state.dbf ?: return 0
        return maxOf(0, state.index.size - dbf.recordCount)
    }

    /** Lee el `.dbf` de la capa con otra codificación y devuelve su ficha con las columnas releídas (RF-18). */
    fun withEncoding(layer: GpkgLayerInfo, encoding: ShpEncoding): GpkgLayerInfo {
        val state = state(layer)
        state.dbf = state.dbf?.withEncoding(encoding)
        state.info = state.describe()
        return state.info
    }

    private fun state(layer: GpkgLayerInfo): LayerState = states.getValue(layer.table)

    override fun forEachFeature(layer: GpkgLayerInfo, box: LayerBox?, action: (GpkgFeature) -> Unit) {
        val state = state(layer)
        val dbf = state.dbf
        val empty = List(state.info.columns.size) { GpkgValue.Null }
        state.shp.forEachRecord { record ->
            val row = dbf?.record(record.index)
            val values = row?.values ?: empty
            val missing = dbf != null && row == null
            val feature = when {
                row?.deleted == true -> GpkgFeature(record.index.toLong(), null, values, GpkgIgnoredReason.DELETED_RECORD)
                else -> when (val shape = record.shape) {
                    is ShpShape.Parts -> GpkgFeature(
                        record.index.toLong(),
                        ShpBlobEncoder.encode(shape),
                        values,
                        withoutAttributes = missing,
                    )
                    ShpShape.Null -> GpkgFeature(record.index.toLong(), null, values, withoutAttributes = missing)
                    ShpShape.MultiPatch -> GpkgFeature(record.index.toLong(), null, values, GpkgIgnoredReason.MULTIPATCH)
                    ShpShape.Invalid -> GpkgFeature(record.index.toLong(), null, values, GpkgIgnoredReason.INVALID_GEOMETRY)
                }
            }
            action(feature)
        }
    }

    override fun forEachHead(layer: GpkgLayerInfo, action: (fid: Long, head: ByteArray?) -> Unit) {
        val state = state(layer)
        val dbf = state.dbf
        state.shp.forEachRecord { record ->
            val shape = record.shape
            if (shape is ShpShape.Parts && dbf?.isDeleted(record.index) != true) {
                action(record.index.toLong(), ShpBlobEncoder.header(shape.envelope))
            }
        }
    }

    override fun forEachIndexRow(
        layer: GpkgLayerInfo,
        action: (fid: Long, minX: Double, maxX: Double, minY: Double, maxY: Double) -> Unit,
    ): Boolean = false

    override fun forEachGeometry(layer: GpkgLayerInfo, ids: LongArray, action: (fid: Long, geometry: ByteArray?) -> Unit) {
        val state = state(layer)
        for (id in ids) {
            val number = id.toInt()
            if (number < 1 || number > state.index.size) continue
            val shape = state.shp.readAt(state.index.offsetOf(number), number)?.shape
            action(id, (shape as? ShpShape.Parts)?.let(ShpBlobEncoder::encode))
        }
    }

    override fun close() {
        closeables.forEach { runCatching { it.close() } }
        extracted.close()
    }

    companion object {
        /** Abre las capas de [extracted]: sus cabeceras, el índice de registros, el `.dbf` con su codificación y el sistema del `.prj`. */
        suspend fun open(extracted: ShpExtracted): ShapefileSession = withContext(Dispatchers.IO) {
            val states = LinkedHashMap<String, LayerState>()
            val opened = ArrayList<Closeable>()
            try {
                for (layer in extracted.layers) {
                    val shpSource = ShpByteSource.of(layer.shp).also { opened += it }
                    val shp = ShpReader.open(shpSource) ?: throw IllegalStateException("Cabecera de Shapefile ilegible: ${layer.name}")
                    val shxSource = layer.shx?.let { file -> ShpByteSource.of(file).also { opened += it } }
                    val index = ShxIndex.of(shxSource, shp)
                    val dbfSource = layer.dbf?.let { file -> ShpByteSource.of(file).also { opened += it } }
                    val declared = layer.cpg?.let { ShpEncodings.fromCpg(it.readText(Charsets.US_ASCII)) }
                    val dbf = dbfSource?.let { source ->
                        val first = DbfReader.open(source, ShpEncoding.UTF_8)
                        first?.withEncoding(declared?.encoding ?: first.detectEncoding())
                    }
                    val crs = ShpCrsCatalog.resolve(layer.prj?.readText(Charsets.UTF_8))
                    states[layer.name] =
                        LayerState(layer.name, shp, index, dbf, geometryType(shp.header.shapeType), crs, declared?.known == false)
                }
            } catch (e: CancellationException) {
                opened.forEach { runCatching { it.close() } }
                throw e
            } catch (e: Exception) {
                opened.forEach { runCatching { it.close() } }
                throw e
            }
            ShapefileSession(extracted, states).also { it.closeables += opened }
        }

        /** El tipo de geometría de la capa por el tipo del `.shp`; un `MultiPatch` se lista como polígonos aunque no se importe. */
        private fun geometryType(shapeType: Int): String = when (shapeType) {
            1, 11, 21 -> "POINT"
            8, 18, 28 -> "MULTIPOINT"
            3, 13, 23 -> "LINESTRING"
            5, 15, 25, 31 -> "POLYGON"
            else -> "GEOMETRY"
        }
    }
}
