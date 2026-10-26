package es.topophone.core.survey

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs

/** Lo que sale de pasar un elemento al sistema del proyecto (S040/RF-17). */
sealed interface CadProjection {
    data class Converted(val entity: CadEntity) : CadProjection

    /** Algún vértice cae fuera del ámbito del sistema o de la rejilla de ED50: el elemento entero no entra. */
    data class Rejected(val reason: CadIgnoredReason) : CadProjection
}

/**
 * De las coordenadas de un DXF a las del proyecto (S040/RF-6, RF-7, RF-17, RF-21):
 * 1. Este, Norte y altura a metros con la unidad elegida (RNF-T9); en un proyecto en geográficas, X es la longitud e Y la latitud
 *    en grados y no se convierten;
 * 2. si vienen en el SC del proyecto se guardan tal cual, tras comprobar el ámbito; si vienen en otro UTM, por latitud y longitud
 *    con la misma ruta que un punto escrito a mano (S011/RF-17): UTM inversa, ED50 a ETRS89 por la rejilla SPED2ETV2 y
 *    [ProjectPositionCalculator.fromGround];
 * 3. un vértice fuera del ámbito o de la rejilla rechaza el elemento entero;
 * 4. la altura no se convierte entre sistemas (la Z del DXF es ya la del sistema de alturas del proyecto) y se descarta si el
 *    usuario no quiso las cotas (RF-8).
 */
class CadProjector(
    private val crs: ProjectCrs,
    private val options: CadImportOptions,
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
) {
    private val metres = options.unit.metres
    private val geographicProject = crs.utmZone == null

    fun convert(entity: CadEntity): CadProjection {
        val n = entity.vertexCount
        val east = DoubleArray(n)
        val north = DoubleArray(n)
        for (i in 0 until n) {
            val rejected = convertVertex(entity.easting[i], entity.northing[i], east, north, i)
            if (rejected != null) return CadProjection.Rejected(rejected)
        }
        val height = if (options.useElevations) entity.height?.let { source -> DoubleArray(n) { source[it] * metres } } else null
        return CadProjection.Converted(entity.withVertices(east, north, height))
    }

    /** Escribe el vértice [i] ya en el proyecto; devuelve por qué se rechaza, o null. */
    private fun convertVertex(x: Double, y: Double, east: DoubleArray, north: DoubleArray, i: Int): CadIgnoredReason? {
        return when (val source = options.source) {
            CadSourceCrs.Project -> {
                val e = if (geographicProject) x else x * metres
                val n = if (geographicProject) y else y * metres
                val ground = ground(crs.datum, e, n, crs.utmZone) ?: return CadIgnoredReason.OUTSIDE_ED50_GRID
                if (!crs.area.contains(ground.first, ground.second)) return CadIgnoredReason.OUTSIDE_CRS_AREA
                east[i] = e
                north[i] = n
                null
            }
            is CadSourceCrs.Utm -> {
                val ground = ground(source.datum, x * metres, y * metres, source.zone) ?: return CadIgnoredReason.OUTSIDE_ED50_GRID
                val position = calculator.fromGround(GeodeticPosition(ground.first, ground.second, null), crs)
                when {
                    PositionUnavailable.OUTSIDE_CRS_AREA in position.unavailable -> CadIgnoredReason.OUTSIDE_CRS_AREA
                    PositionUnavailable.OUTSIDE_ED50_GRID in position.unavailable -> CadIgnoredReason.OUTSIDE_ED50_GRID
                    else -> {
                        east[i] = if (geographicProject) position.longitudeDeg!! else position.easting!!
                        north[i] = if (geographicProject) position.latitudeDeg!! else position.northing!!
                        null
                    }
                }
            }
        }
    }

    /**
     * Latitud y longitud ETRS89 o REGCAN95 del punto (Este, Norte) UTM de [zone], o (longitud, latitud) si [zone] es null; null si
     * es ED50 y cae fuera de la rejilla.
     */
    private fun ground(datum: GeodeticDatum, first: Double, second: Double, zone: Int?): Pair<Double, Double>? {
        val (lat, lon) = if (zone != null) {
            val ellipsoid = if (datum == GeodeticDatum.ED50) Ellipsoid.INTERNATIONAL_1924 else Ellipsoid.GRS80
            Utm.inverse(first, second, zone, ellipsoid).let { it.latitudeDeg to it.longitudeDeg }
        } else {
            second to first
        }
        return if (datum == GeodeticDatum.ED50) Ed50.toEtrs89(lat, lon) else lat to lon
    }
}
