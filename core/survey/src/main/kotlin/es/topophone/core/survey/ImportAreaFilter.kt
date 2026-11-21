package es.topophone.core.survey

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** El recuadro de la zona de importación en el sistema de una capa, en sus unidades (metros en UTM, grados en geográficas). */
data class LayerBox(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {
    /** El recuadro [minX]…[maxY] de un objeto toca este (el prefiltro por envolvente o por R-tree). */
    fun intersects(minX: Double, maxX: Double, minY: Double, maxY: Double): Boolean =
        minX <= this.maxX && maxX >= this.minX && minY <= this.maxY && maxY >= this.minY
}

/**
 * La zona de importación de un GeoPackage: un círculo alrededor de un punto (S048/RF-13, RF-15).
 * - [contains] y [anyInside] son la prueba exacta, en el plano del SC del proyecto (distancia gran círculo si el SC es geográfico),
 *   con los vértices ya convertidos al proyecto: un objeto entra si **algún vértice** está dentro, aunque el segmento que pasa por la
 *   zona no tenga ninguno, y entra entero.
 * - [candidateBox] es el prefiltro: el recuadro de la zona en el SC de la capa, con un margen del 0,5 % del radio por la escala de la
 *   proyección y de 5 m por la diferencia entre datums. Nunca deja fuera un objeto que la prueba exacta aceptaría.
 */
class ImportAreaFilter private constructor(
    private val circle: ImportArea.Circle,
    private val geographicProject: Boolean,
    private val centerX: Double,
    private val centerY: Double,
) {
    val radiusM: Double get() = circle.radiusM

    /** El punto (Este, Norte) —o (longitud, latitud) en un SC geográfico— está a [radiusM] o menos del centro. */
    fun contains(x: Double, y: Double): Boolean = distanceM(x, y) <= radiusM

    /** La distancia del punto (Este, Norte) —o (longitud, latitud)— al centro de la zona, en metros (S048/RF-12). */
    fun distanceM(x: Double, y: Double): Double =
        if (geographicProject) greatCircleM(centerY, centerX, y, x) else hypot(x - centerX, y - centerY)

    /** Algún vértice está dentro. */
    fun anyInside(x: DoubleArray, y: DoubleArray): Boolean = x.indices.any { contains(x[it], y[it]) }

    /** El recuadro de la zona en el SC [source] de una capa, o null si el centro no se puede pasar a él (ED50 fuera de su rejilla). */
    fun candidateBox(source: CadSourceCrs): LayerBox? {
        val half = circle.radiusM * (1.0 + SCALE_MARGIN) + DATUM_MARGIN_M
        val lat = circle.center.latitudeDeg
        val lon = circle.center.longitudeDeg
        return when (source) {
            CadSourceCrs.Project -> null
            is CadSourceCrs.Geographic -> {
                val dLat = half / METRES_PER_DEGREE
                val dLon = half / (METRES_PER_DEGREE * max(cos(Math.toRadians(lat)), MIN_COS_LAT))
                LayerBox(lon - dLon, lon + dLon, lat - dLat, lat + dLat)
            }
            is CadSourceCrs.Utm -> {
                val (layerLat, layerLon) = if (source.datum == GeodeticDatum.ED50) Ed50.fromEtrs89(lat, lon) ?: return null else lat to lon
                val ellipsoid = if (source.datum == GeodeticDatum.ED50) Ellipsoid.INTERNATIONAL_1924 else Ellipsoid.GRS80
                val utm = Utm.forward(layerLat, layerLon, source.zone, ellipsoid)
                LayerBox(utm.easting - half, utm.easting + half, utm.northing - half, utm.northing + half)
            }
            is CadSourceCrs.Library -> libraryBox(source, half)
        }
    }

    /**
     * El recuadro de la zona en el SC de la biblioteca de una capa (S057/RF-16), en su unidad (la del SC; grados en uno geográfico): el de
     * [CIRCLE_SAMPLES] puntos del círculo de radio [half] pasados al SC con [SourceGround.fromGround], más un margen del 1 % por si el
     * recuadro verdadero del círculo curvado sobresale de los puntos. Con otras proyecciones la escala no está acotada como en UTM, por eso se
     * mide en vez de suponerla. null si el centro no se puede pasar al SC (fuera de su ámbito o de la rejilla del datum).
     */
    private fun libraryBox(source: CadSourceCrs.Library, half: Double): LayerBox? {
        val ground = SourceGround(source.definition)
        val lat = circle.center.latitudeDeg
        val lon = circle.center.longitudeDeg
        val centre = ground.fromGround(lat, lon) ?: return null
        val cosLat = max(cos(Math.toRadians(lat)), MIN_COS_LAT)
        val xs = ArrayList<Double>(CIRCLE_SAMPLES + 1).apply { add(centre.first) }
        val ys = ArrayList<Double>(CIRCLE_SAMPLES + 1).apply { add(centre.second) }
        for (i in 0 until CIRCLE_SAMPLES) {
            val theta = 2 * Math.PI * i / CIRCLE_SAMPLES
            val sampleLat = lat + half * cos(theta) / METRES_PER_DEGREE
            val sampleLon = lon + half * sin(theta) / (METRES_PER_DEGREE * cosLat)
            ground.fromGround(sampleLat, sampleLon)?.let {
                xs.add(it.first)
                ys.add(it.second)
            }
        }
        val width = xs.max() - xs.min()
        val height = ys.max() - ys.min()
        return LayerBox(
            xs.min() - width * BOX_MARGIN,
            xs.max() + width * BOX_MARGIN,
            ys.min() - height * BOX_MARGIN,
            ys.max() + height * BOX_MARGIN,
        )
    }

    companion object {
        private const val CIRCLE_SAMPLES = 32
        private const val BOX_MARGIN = 0.01
        private const val SCALE_MARGIN = 0.005
        private const val DATUM_MARGIN_M = 5.0
        private const val METRES_PER_DEGREE = 111_320.0
        private const val MIN_COS_LAT = 0.01
        private const val EARTH_RADIUS_M = 6_371_008.8

        /** El filtro de una zona circular en el SC [crs] del proyecto; null si el centro no tiene coordenadas en él. */
        fun of(
            crs: ProjectCrs,
            area: ImportArea.Circle,
            calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
        ): ImportAreaFilter? {
            val ground = GeodeticPosition(area.center.latitudeDeg, area.center.longitudeDeg, null)
            val position = calculator.fromGround(ground, crs, HeightSystem.ELLIPSOIDAL)
            return if (!crs.isProjected) {
                val lon = position.longitudeDeg ?: return null
                val lat = position.latitudeDeg ?: return null
                ImportAreaFilter(area, geographicProject = true, centerX = lon, centerY = lat)
            } else {
                val east = position.easting ?: return null
                val north = position.northing ?: return null
                ImportAreaFilter(area, geographicProject = false, centerX = east, centerY = north)
            }
        }

        private fun greatCircleM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
