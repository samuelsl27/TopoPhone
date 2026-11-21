package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.GeoTiffGrid
import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.HorizontalShiftGrid
import kotlin.math.abs

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
class GridShiftTransformation(
    private val gridName: String,
    private val source: GridSource,
    override val info: TransformationInfo,
    private val maxIterations: Int = MAX_ITERATIONS,
) : DatumTransformation {

    /** `null` si la rejilla no está, no se puede leer o no tiene dos bandas (latitud y longitud) en todas sus subrejillas (S033/RF-9). */
    private val grid: HorizontalShiftGrid? by lazy { loadGrid() }

    private fun loadGrid(): HorizontalShiftGrid? = try {
        source.read(gridName)?.let { GeoTiffGrid.readAll(it) }?.takeIf { grids -> grids.isNotEmpty() && grids.all { it.bands >= 2 } }
            ?.let(::HorizontalShiftGrid)
    } catch (e: Exception) {
        null
    }

    override fun missingGrid(): String? = if (grid == null) gridName else null

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

    /**
     * Inversa de la rejilla (en la rejilla el desplazamiento se da en el datum de origen): se itera `x = destino − desplazamiento(x)` hasta
     * que el paso cambia menos de 1·10⁻¹² ° (0,1 µm). Si no llega en [maxIterations] pasos, o el punto sale de la rejilla, se rechaza el
     * punto: la ida y vuelta queda siempre por debajo de 0,1 mm o no hay coordenada (S033/RF-10).
     */
    override fun inverse(point: GeodeticPoint): TransformResult {
        if (!point.isFinite()) return TransformResult.NotConverged(gridName)
        val grid = grid ?: return TransformResult.GridUnavailable(gridName)
        var lat = point.latitudeDeg
        var lon = point.longitudeDeg
        repeat(maxIterations) { iteration ->
            val offset = grid.offset(lat, lon) ?: (if (iteration == 0) nearbyOffset(grid, lat, lon) else null)
                ?: return TransformResult.OutsideGrid(gridName)
            val nextLat = point.latitudeDeg - offset.latitudeArcSec / ARC_SECONDS_PER_DEGREE
            val nextLon = point.longitudeDeg - offset.longitudeArcSec / ARC_SECONDS_PER_DEGREE
            val converged = abs(nextLat - lat) < TOLERANCE_DEG && abs(nextLon - lon) < TOLERANCE_DEG
            lat = nextLat
            lon = nextLon
            if (converged) return TransformResult.Ok(GeodeticPoint(lat, lon, point.ellipsoidalHeightM))
        }
        return TransformResult.NotConverged(gridName)
    }

    /**
     * Desplazamiento aproximado cerca del punto de partida, solo para arrancar la inversa: en una franja de unos 140 m dentro del borde la
     * coordenada de destino cae fuera de la rejilla aunque la solución esté dentro. No es una extrapolación del resultado: cada paso
     * siguiente (y el último) se evalúa dentro de la rejilla o se rechaza el punto (S033/RF-8, RF-10; ADR-5).
     */
    private fun nearbyOffset(grid: HorizontalShiftGrid, latitudeDeg: Double, longitudeDeg: Double): HorizontalShiftGrid.Offset? {
        for (step in PROBE_STEPS_DEG) {
            for ((dLat, dLon) in PROBE_DIRECTIONS) {
                grid.offset(latitudeDeg + dLat * step, longitudeDeg + dLon * step)?.let { return it }
            }
        }
        return null
    }

    companion object {
        private val PROBE_STEPS_DEG = doubleArrayOf(0.005, 0.02)
        private val PROBE_DIRECTIONS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)

        /** Iteraciones máximas de la inversa: con los desplazamientos de España se llega en 4 o 5. */
        const val MAX_ITERATIONS = 30

        private const val TOLERANCE_DEG = 1e-12
        private const val ARC_SECONDS_PER_DEGREE = 3600.0
    }
}
