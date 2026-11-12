package es.topophone.data.gpkg

import es.topophone.core.formats.gpkg.GpkgGeometry
import es.topophone.core.formats.gpkg.GpkgGeometryReader
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.ImportAreaFilter
import es.topophone.core.survey.LayerBox
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.cad.FeatureSession
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Las envolventes de todos los objetos no vacíos de una capa, en el sistema de la capa y en la misma posición (S048/RF-13): una
 * matriz en memoria sobre la que cambiar el radio de la zona es recorrerla, sin volver a leer el fichero.
 */
class GpkgLayerIndex(
    val layer: GpkgLayerInfo,
    val ids: LongArray,
    private val minX: DoubleArray,
    private val maxX: DoubleArray,
    private val minY: DoubleArray,
    private val maxY: DoubleArray,
) {
    val size: Int get() = ids.size

    /** Objetos vacíos, nulos o con la cabecera ilegible, que no tienen envolvente. */
    val withoutEnvelope: Int get() = (layer.featureCount - ids.size).toInt()

    /** La unión de todas las envolventes, o null si no hay ninguna. */
    val bounds: LayerBox?
        get() = if (ids.isEmpty()) null else LayerBox(minX.min(), maxX.max(), minY.min(), maxY.max())

    /** Los objetos cuya envolvente corta [box]; todos si [box] es null. */
    fun idsIn(box: LayerBox?): LongArray {
        if (box == null) return ids
        val out = LongArray(ids.size)
        var n = 0
        for (i in ids.indices) if (box.intersects(minX[i], maxX[i], minY[i], maxY[i])) out[n++] = ids[i]
        return out.copyOf(n)
    }

    /** Las envolventes, de 0 a [size], para los tests. */
    fun envelope(index: Int): LayerBox = LayerBox(minX[index], maxX[index], minY[index], maxY[index])
}

/** Cuántos objetos entrarían en la zona (S048/RF-16). [exceeded] es que pasan de [ImportArea.MAX_OBJECTS] y no se siguió contando. */
data class GpkgZoneCount(val inZone: Int, val ignored: Map<GpkgIgnoredReason, Int>, val exceeded: Boolean) {
    val ignoredTotal: Int get() = ignored.values.sum()
}

/**
 * Lo que se ve de un GeoPackage antes de importar (S048/RF-12, RF-13, RF-16): la extensión de cada capa en el sistema del proyecto, su
 * distancia al centro y cuántos objetos entran en una zona. Las envolventes salen del R-tree si lo hay y, si no, de los primeros 40
 * bytes de cada geometría (su cabecera); solo si un blob no trae envolvente se lee su geometría, una vez. El recuento exacto lee solo
 * las geometrías de los candidatos del prefiltro y las pasa por el mismo [GpkgFeatureConverter] que la importación.
 */
class GeoPackageScan(
    private val session: FeatureSession,
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
    private val maxObjects: Int = ImportArea.MAX_OBJECTS,
) {
    private class StopCounting : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** Las envolventes de [layer]. Cancelable. */
    suspend fun index(layer: GpkgLayerInfo): GpkgLayerIndex = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val builder = IndexBuilder(layer.featureCount.toInt().coerceAtLeast(0))
        val viaIndex = session.forEachIndexRow(layer) { fid, x0, x1, y0, y1 ->
            builder.add(fid, x0, x1, y0, y1)
            context.checkEvery(builder.size)
        }
        if (!viaIndex) readHeads(layer, builder, context)
        builder.build(layer)
    }

    private fun readHeads(layer: GpkgLayerInfo, builder: IndexBuilder, context: CoroutineContext) {
        val missing = ArrayList<Long>()
        var seen = 0
        session.forEachHead(layer) { fid, head ->
            context.checkEvery(seen++)
            val header = head?.let(GpkgGeometryReader::header)
            val envelope = header?.envelope
            when {
                header == null || header.empty -> Unit
                envelope != null -> builder.add(fid, envelope.minX, envelope.maxX, envelope.minY, envelope.maxY)
                else -> missing += fid
            }
        }
        // Sin envolvente en el blob: se calcula una vez leyendo la geometría.
        session.forEachGeometry(layer, missing.toLongArray()) { fid, blob ->
            context.checkEvery(seen++)
            val geometry = blob?.let(GpkgGeometryReader::read)
            if (geometry is GpkgGeometry.Parts) {
                val xs = geometry.parts.flatMap { it.x.asList() }
                val ys = geometry.parts.flatMap { it.y.asList() }
                builder.add(fid, xs.min(), xs.max(), ys.min(), ys.max())
            }
        }
    }

    private fun CoroutineContext.checkEvery(count: Int) {
        if (count % CHECK_EVERY == 0) ensureActive()
    }

    /** La extensión de la capa en el sistema del proyecto (de las esquinas de su envolvente), o null si no cabe en él. */
    fun extent(index: GpkgLayerIndex, source: CadSourceCrs, crs: ProjectCrs): CadExtent? {
        val box = index.bounds ?: return null
        val converter = GpkgFeatureConverter(source, crs, useElevations = false, calculator = calculator)
        val corners = listOf(box.minX to box.minY, box.minX to box.maxY, box.maxX to box.minY, box.maxX to box.maxY)
            .mapNotNull { (x, y) -> converter.project(x, y) }
        if (corners.isEmpty()) return null
        return CadExtent(corners.minOf { it.first }, corners.maxOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.second })
    }

    /** La distancia en metros entre el centro de [extent] y [center], el centro del proyecto (S048/RF-12). */
    fun distanceToCenterM(extent: CadExtent, crs: ProjectCrs, center: CentralPoint): Double? {
        val filter = ImportAreaFilter.of(crs, ImportArea.Circle(center, ImportArea.MIN_RADIUS_M), calculator) ?: return null
        return filter.distanceM((extent.minEasting + extent.maxEasting) / 2, (extent.minNorthing + extent.maxNorthing) / 2)
    }

    /**
     * Cuántos objetos de la capa entran en [area] (RF-13, RF-15, RF-16). Con [ImportArea.All] son todos los que tienen envolvente, sin
     * leer geometrías. Con un círculo, los candidatos del prefiltro que tras pasar al proyecto tienen algún vértice dentro; los que se
     * ignorarían (curvas, fuera del ámbito…) se cuentan aparte. Cancelable; se detiene al pasar de [maxObjects].
     */
    suspend fun count(index: GpkgLayerIndex, source: CadSourceCrs, crs: ProjectCrs, area: ImportArea): GpkgZoneCount =
        withContext(Dispatchers.IO) {
            val filter = (area as? ImportArea.Circle)?.let { ImportAreaFilter.of(crs, it, calculator) }
            if (filter == null) return@withContext GpkgZoneCount(index.size, emptyMap(), exceeded = index.size > maxObjects)
            val context = currentCoroutineContext()
            val converter = GpkgFeatureConverter(source, crs, useElevations = false, calculator = calculator)
            val ids = index.idsIn(filter.candidateBox(source))
            val ignored = HashMap<GpkgIgnoredReason, Int>()
            var inZone = 0
            var seen = 0
            try {
                session.forEachGeometry(index.layer, ids) { _, blob ->
                    context.checkEvery(seen++)
                    when (val conversion = converter.convert(blob)) {
                        is GpkgConversion.Ignored -> ignored.merge(conversion.reason, 1, Int::plus)
                        is GpkgConversion.Converted -> if (conversion.parts.any { filter.anyInside(it.easting, it.northing) }) inZone++
                    }
                    if (inZone > maxObjects) throw StopCounting()
                }
            } catch (e: StopCounting) {
                return@withContext GpkgZoneCount(inZone, ignored, exceeded = true)
            }
            GpkgZoneCount(inZone, ignored, exceeded = false)
        }

    private class IndexBuilder(capacity: Int) {
        private var ids = LongArray(capacity.coerceAtLeast(16))
        private var minX = DoubleArray(ids.size)
        private var maxX = DoubleArray(ids.size)
        private var minY = DoubleArray(ids.size)
        private var maxY = DoubleArray(ids.size)
        var size = 0
            private set

        fun add(fid: Long, x0: Double, x1: Double, y0: Double, y1: Double) {
            if (size == ids.size) grow()
            ids[size] = fid
            minX[size] = x0
            maxX[size] = x1
            minY[size] = y0
            maxY[size] = y1
            size++
        }

        private fun grow() {
            val n = ids.size * 2
            ids = ids.copyOf(n)
            minX = minX.copyOf(n)
            maxX = maxX.copyOf(n)
            minY = minY.copyOf(n)
            maxY = maxY.copyOf(n)
        }

        fun build(layer: GpkgLayerInfo) =
            GpkgLayerIndex(layer, ids.copyOf(size), minX.copyOf(size), maxX.copyOf(size), minY.copyOf(size), maxY.copyOf(size))
    }

    private companion object {
        const val CHECK_EVERY = 1000
    }
}
