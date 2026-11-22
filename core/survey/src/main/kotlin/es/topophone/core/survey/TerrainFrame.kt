package es.topophone.core.survey

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.TerrainPlane

/** Un punto de la cuadrícula o del terreno: Este y Norte en metros (S058). */
data class EastNorth(val easting: Double, val northing: Double)

/**
 * El paso entre las coordenadas de cuadrícula y las de terreno de un plano en un SC (S058/RF-7, RF-8): una homotecia respecto al origen, que conserva sus
 * coordenadas ([originEasting], [originNorthing]) y multiplica por el [factor] la distancia de cualquier punto a él. Por eso las distancias y los deltas
 * calculados sobre coordenadas de terreno salen ya en terreno, los ángulos no cambian y las áreas se multiplican por el factor al cuadrado. Las alturas no
 * entran: no cambian.
 */
class TerrainFrame private constructor(val plane: TerrainPlane, val originEasting: Double, val originNorthing: Double) {
    val factor: Double get() = plane.factor

    /** De cuadrícula a terreno. */
    fun toTerrain(easting: Double, northing: Double): EastNorth =
        EastNorth(originEasting + factor * (easting - originEasting), originNorthing + factor * (northing - originNorthing))

    /** De terreno a cuadrícula: la inversa exacta de [toTerrain]. */
    fun toGrid(easting: Double, northing: Double): EastNorth =
        EastNorth(originEasting + (easting - originEasting) / factor, originNorthing + (northing - originNorthing) / factor)

    /** Una distancia o un delta de cuadrícula, en terreno. */
    fun distance(gridMetres: Double): Double = gridMetres * factor

    /** Un área de cuadrícula, en terreno. */
    fun area(gridSquareMetres: Double): Double = gridSquareMetres * factor * factor

    companion object {
        /**
         * El marco de [plane] en el SC [crs], o `null` si no se puede construir: el SC es geográfico (S058/RF-2) o el origen no se puede calcular en él, por
         * ejemplo porque cae fuera de su ámbito (RF-5).
         */
        fun of(plane: TerrainPlane, crs: ProjectCrs, calculator: ProjectPositionCalculator = ProjectPositionCalculator()): TerrainFrame? {
            if (!crs.isProjected) return null
            val origin = calculator.fromGround(
                GeodeticPosition(plane.originLatitudeDeg, plane.originLongitudeDeg, null),
                crs,
                HeightSystem.ELLIPSOIDAL,
            )
            val easting = origin.easting ?: return null
            val northing = origin.northing ?: return null
            return TerrainFrame(plane, easting, northing)
        }
    }
}
