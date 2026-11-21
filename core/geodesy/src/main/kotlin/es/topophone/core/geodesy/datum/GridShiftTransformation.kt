package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.GeoTiffGrid
import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.HorizontalShiftGrid

/** De dónde salen los ficheros de rejilla: `null` si no está instalada (S033/RF-9). S034 aportará la fuente de las descargadas. */
fun interface GridSource {
    fun read(name: String): ByteArray?
}

/** Rejillas incluidas en el APK como recursos del classpath, bajo [prefix] (por ejemplo `/shift/`; ADR-4). */
class ClasspathGridSource(private val prefix: String) : GridSource {
    override fun read(name: String): ByteArray? = ClasspathGridSource::class.java.getResourceAsStream(prefix + name)?.use { it.readBytes() }
}

/**
 * Rejilla de desplazamiento horizontal como transformación de datum (S033/RF-7): suma a la latitud y la longitud los desplazamientos en
 * segundos de arco de la primera subrejilla del fichero que contiene el punto, interpolados bilinealmente, y deja la altura igual. Fuera
 * de la rejilla no extrapola: rechaza el punto (S033/RF-8, ADR-5). Es el `hgridshift` de PROJ; el sentido directo es el de la rejilla.
 */
class GridShiftTransformation(private val gridName: String, private val source: GridSource, override val info: TransformationInfo) :
    DatumTransformation {

    /** `null` si la rejilla no está, no se puede leer o no tiene dos bandas (latitud y longitud) en todas sus subrejillas (S033/RF-9). */
    private val grid: HorizontalShiftGrid? by lazy { loadGrid() }

    private fun loadGrid(): HorizontalShiftGrid? = try {
        source.read(gridName)?.let { GeoTiffGrid.readAll(it) }?.takeIf { grids -> grids.isNotEmpty() && grids.all { it.bands >= 2 } }
            ?.let(::HorizontalShiftGrid)
    } catch (e: Exception) {
        null
    }

    override fun forward(point: GeodeticPoint): TransformResult {
        if (!point.isFinite()) return TransformResult.NotConverged(gridName)
        val grid = grid ?: return TransformResult.GridUnavailable(gridName)
        val offset = grid.offset(point.latitudeDeg, point.longitudeDeg) ?: return TransformResult.OutsideGrid(gridName)
        return TransformResult.Ok(
            GeodeticPoint(
                point.latitudeDeg + offset.latitudeArcSec / ARC_SECONDS_PER_DEGREE,
                point.longitudeDeg + offset.longitudeArcSec / ARC_SECONDS_PER_DEGREE,
                point.ellipsoidalHeightM,
            ),
        )
    }

    override fun inverse(point: GeodeticPoint): TransformResult = TODO("S033 T10: inversa iterativa")

    private companion object {
        const val ARC_SECONDS_PER_DEGREE = 3600.0
    }
}
