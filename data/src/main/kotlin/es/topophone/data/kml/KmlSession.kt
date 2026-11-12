package es.topophone.data.kml

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsUnit
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.GpkgColumn
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.survey.LayerBox
import es.topophone.data.cad.FeatureSession
import es.topophone.data.cad.GpkgFeature

/**
 * El texto con el que se distinguen las dos capas de una carpeta que tiene puntos y líneas (S031/plan D4): la ruta de la carpeta seguida de
 * este sufijo. Lo da la app desde sus recursos para que siga el idioma de la interfaz.
 */
class KmlLayerSuffixes(val points: String = " (puntos)", val lines: String = " (líneas)")

/**
 * Un KML leído ([KmlStore]) como sesión de capas para el importador y el recuento por zona de S048 (S031/RF-5 a RF-9, RF-11 a RF-15, plan D1):
 * una capa de puntos y otra de líneas por cada ruta de carpetas, todas en las geográficas del datum de los cálculos del proyecto
 * ([datum]: ETRS89 o REGCAN95) y marcadas como WGS84, que es lo que declara un KML y se toma como ese datum (RF-7, RF-18). Con una sola capa en
 * una ruta, su nombre es la ruta; con las dos, la ruta con [suffixes] (RF-5).
 */
class KmlSession(private val store: KmlStore, private val datum: GeodeticDatum, suffixes: KmlLayerSuffixes = KmlLayerSuffixes()) :
    FeatureSession {
    init {
        require(datum != GeodeticDatum.ED50) { "Un KML se toma como el datum de los cálculos: ETRS89 o REGCAN95" }
    }

    private class State(val info: GpkgLayerInfo, val layer: KmlStoreLayer)

    private val states: List<State> = run {
        val kinds = store.layers.groupBy { it.path }.mapValues { (_, layers) -> layers.map { it.points }.toSet().size }
        store.layers.map { layer ->
            val suffix = if (kinds.getValue(layer.path) > 1) (if (layer.points) suffixes.points else suffixes.lines) else ""
            val info = GpkgLayerInfo(
                table = layer.path + suffix,
                geometryColumn = "geometry",
                geometryType = if (layer.points) "POINT" else "LINESTRING",
                featureCount = layer.count.toLong(),
                srsId = 0,
                columns = layer.columnNames.map { GpkgColumn(it, "TEXT") },
                hasSpatialIndex = false,
            )
            State(info, layer)
        }
    }

    override val origin: CadOrigin get() = CadOrigin.KML

    override val layers: List<GpkgLayerInfo> get() = states.map { it.info }

    override val leftOutTables: List<String> get() = emptyList()

    override val hasElevations: Boolean get() = store.hasElevations

    override val ignoredElements: Map<GpkgIgnoredReason, Int> get() = store.ignoredElements

    /** El nombre con el que se guarda el dibujo: el del fichero (S031/RF-19). */
    val drawingName: String get() = store.drawingName

    /** Los objetos con geometría válida de todas las capas. */
    val validObjects: Int get() = store.validObjects

    override fun crs(layer: GpkgLayerInfo): GpkgCrs = GpkgCrs.Supported(
        epsg = if (datum == GeodeticDatum.REGCAN95) EPSG_REGCAN95 else EPSG_ETRS89,
        source = CadSourceCrs.Geographic(datum),
        unit = GpkgCrsUnit.DEGREE,
        wgs84 = true,
    )

    private fun state(layer: GpkgLayerInfo): State = states.first { it.info.table == layer.table }

    override fun forEachFeature(layer: GpkgLayerInfo, box: LayerBox?, action: (GpkgFeature) -> Unit) {
        val state = state(layer)
        val columns = state.layer.columnNames.size
        for (fid in 1..state.layer.count) {
            val record = store.read(state.layer, fid)
            if (record.ignoredEmpty) {
                action(GpkgFeature(fid.toLong(), null, emptyList(), GpkgIgnoredReason.EMPTY_GEOMETRY))
            } else {
                val values = List(columns) { column -> record.values[column]?.let { GpkgValue.Text(it) } ?: GpkgValue.Null }
                action(GpkgFeature(fid.toLong(), record.blob, values, color = record.color))
            }
        }
    }

    override fun forEachHead(layer: GpkgLayerInfo, action: (fid: Long, head: ByteArray?) -> Unit) {
        val state = state(layer)
        for (fid in 1..state.layer.count) {
            val record = store.readHead(state.layer, fid)
            if (!record.ignoredEmpty) action(fid.toLong(), record.blob)
        }
    }

    override fun forEachIndexRow(
        layer: GpkgLayerInfo,
        action: (fid: Long, minX: Double, maxX: Double, minY: Double, maxY: Double) -> Unit,
    ): Boolean = false

    override fun forEachGeometry(layer: GpkgLayerInfo, ids: LongArray, action: (fid: Long, geometry: ByteArray?) -> Unit) {
        val state = state(layer)
        for (id in ids) {
            val fid = id.toInt()
            if (fid in 1..state.layer.count) action(id, store.read(state.layer, fid).blob)
        }
    }

    override fun close() {
        store.close()
    }

    private companion object {
        const val EPSG_ETRS89 = 4258
        const val EPSG_REGCAN95 = 4081
    }
}
