package es.topophone.core.survey

import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GroundPosition

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
 *    [ProjectPositionCalculator.fromGround]; si vienen en un SC de la biblioteca (S057/RF-13), por [SourceGround] (inversa de su proyección y cambio
 *    de datum hasta el de las correcciones) y la misma ruta;
 * 3. un vértice fuera del ámbito o de la rejilla rechaza el elemento entero;
 * 4. la altura no se convierte entre sistemas (la Z del DXF es ya la del sistema de alturas del proyecto) y se descarta si el
 *    usuario no quiso las cotas (RF-8).
 */
class CadProjector(
    private val crs: ProjectCrs,
    private val options: CadImportOptions,
    private val calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
    /** El paso de terreno a cuadrícula del proyecto: «las coordenadas del proyecto» de un fichero son de terreno y se guardan en cuadrícula (S058/RF-17). */
    private val terrain: TerrainView = TerrainView.GRID,
) {
    private val metres = options.unit.metres
    private val geographicProject = !crs.isProjected
    private val library: SourceGround? = (options.source as? CadSourceCrs.Library)?.let { SourceGround(it.definition) }

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
                val fileE = if (geographicProject) x else x * metres
                val fileN = if (geographicProject) y else y * metres
                val grid = if (geographicProject) null else terrain.toGrid(fileE, fileN)
                val e = grid?.easting ?: fileE
                val n = grid?.northing ?: fileN
                val ground = GroundPosition.of(crs, e, n) ?: return CadIgnoredReason.OUTSIDE_ED50_GRID
                if (!crs.containsPosition(ground.first, ground.second)) return CadIgnoredReason.OUTSIDE_CRS_AREA
                east[i] = e
                north[i] = n
                null
            }
            is CadSourceCrs.Utm, is CadSourceCrs.Geographic, is CadSourceCrs.Library -> {
                val ground = when (source) {
                    is CadSourceCrs.Utm -> ground(source.datum, x * metres, y * metres, source.zone)
                    is CadSourceCrs.Geographic -> ground(source.datum, x, y, null)
                    is CadSourceCrs.Library -> when (val result = requireNotNull(library).toGround(x, y, metres)) {
                        is GroundResult.Ok -> result.latitudeDeg to result.longitudeDeg
                        is GroundResult.Unavailable ->
                            return if (result.reason == PositionUnavailable.OUTSIDE_CRS_AREA) {
                                CadIgnoredReason.OUTSIDE_CRS_AREA
                            } else {
                                CadIgnoredReason.OUTSIDE_ED50_GRID
                            }
                    }
                    CadSourceCrs.Project -> null
                } ?: return CadIgnoredReason.OUTSIDE_ED50_GRID
                val position = calculator.fromGround(GeodeticPosition(ground.first, ground.second, null), crs)
                when {
                    PositionUnavailable.OUTSIDE_CRS_AREA in position.unavailable -> CadIgnoredReason.OUTSIDE_CRS_AREA
                    PositionUnavailable.OUTSIDE_ED50_GRID in position.unavailable ||
                        PositionUnavailable.OUTSIDE_DATUM_GRID in position.unavailable ||
                        PositionUnavailable.MISSING_GRID in position.unavailable -> CadIgnoredReason.OUTSIDE_ED50_GRID
                    else -> {
                        east[i] = if (geographicProject) position.longitudeDeg!! else position.easting!!
                        north[i] = if (geographicProject) position.latitudeDeg!! else position.northing!!
                        null
                    }
                }
            }
        }
    }

    /** La latitud y la longitud ETRS89 o REGCAN95 del punto; null si es ED50 y cae fuera de la rejilla (S026: [GroundPosition]). */
    private fun ground(datum: GeodeticDatum, first: Double, second: Double, zone: Int?): Pair<Double, Double>? =
        GroundPosition.of(datum, first, second, zone)
}
