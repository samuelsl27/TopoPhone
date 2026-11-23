package es.topophone.core.survey

import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.TerrainPlane

/**
 * Lo que usan las pantallas y los ficheros de un proyecto para mostrar, escribir y exportar en terreno (S058/RF-9, RF-10): con cuadrícula (o sin plano) todo
 * es la identidad. Las coordenadas guardadas están en cuadrícula (RF-8); una pantalla convierte con [coordinates], [distance] y [area] lo que muestra y con
 * [toGrid] lo que escribe el usuario. Los ángulos y las alturas no cambian y no pasan por aquí.
 */
class TerrainView private constructor(private val frame: TerrainFrame?) {
    /** ¿Está el proyecto en terreno? Con `false` todo es la cuadrícula tal cual. */
    val isTerrain: Boolean get() = frame != null

    /** El factor de terreno, o 1 en cuadrícula. */
    val factor: Double get() = frame?.factor ?: 1.0

    /** El plano, o `null` en cuadrícula. */
    val plane: TerrainPlane? get() = frame?.plane

    /** El Este y el Norte de cuadrícula, en terreno. */
    fun coordinates(gridEasting: Double, gridNorthing: Double): EastNorth =
        frame?.toTerrain(gridEasting, gridNorthing) ?: EastNorth(gridEasting, gridNorthing)

    /** El Este de cuadrícula, en terreno (con el Norte, que lo necesita también). */
    fun easting(gridEasting: Double, gridNorthing: Double): Double = coordinates(gridEasting, gridNorthing).easting

    /** El Norte de cuadrícula, en terreno. */
    fun northing(gridEasting: Double, gridNorthing: Double): Double = coordinates(gridEasting, gridNorthing).northing

    /** Lo que escribe el usuario, de terreno a cuadrícula (la inversa de [coordinates]). */
    fun toGrid(terrainEasting: Double, terrainNorthing: Double): EastNorth =
        frame?.toGrid(terrainEasting, terrainNorthing) ?: EastNorth(terrainEasting, terrainNorthing)

    /** Una distancia o un delta de cuadrícula, en terreno. */
    fun distance(gridMetres: Double): Double = frame?.distance(gridMetres) ?: gridMetres

    /**
     * La guía del replanteo en terreno: la distancia, los deltas, el avance y el lado se multiplican por el factor; el rumbo y la
     * diferencia de cota no cambian (S058/RF-9, RF-11).
     */
    fun guidance(grid: Guidance): Guidance = if (!isTerrain) {
        grid
    } else {
        grid.copy(
            distanceM = distance(grid.distanceM),
            dEastM = distance(grid.dEastM),
            dNorthM = distance(grid.dNorthM),
            aheadM = grid.aheadM?.let { distance(it) },
            rightM = grid.rightM?.let { distance(it) },
        )
    }

    /** Las distancias de un objetivo sobre una línea, en terreno; el PK y el desplazamiento son de diseño y no cambian (S058/RF-9). */
    fun lineObjective(grid: LineObjective): LineObjective = if (!isTerrain) {
        grid
    } else {
        grid.copy(
            lateralErrorM = grid.lateralErrorM?.let { distance(it) },
            alongM = distance(grid.alongM),
            remainingM = distance(grid.remainingM),
        )
    }

    /** Un área de cuadrícula, en terreno. */
    fun area(gridSquareMetres: Double): Double = frame?.area(gridSquareMetres) ?: gridSquareMetres

    /** Una distancia o un delta de terreno, en cuadrícula (la inversa de [distance]). */
    fun gridDistance(terrainMetres: Double): Double = terrainMetres / factor

    companion object {
        /** Sin terreno: cuadrícula. */
        val GRID = TerrainView(null)

        /**
         * El terreno de [plane] en el SC [crs]; cuadrícula si [plane] es `null`. Un plano que el SC no admite (SC geográfico u origen fuera de su ámbito) da
         * `null` y no cuadrícula en silencio: la pantalla debe decir que el plano no vale.
         */
        fun of(plane: TerrainPlane?, crs: ProjectCrs, calculator: ProjectPositionCalculator = ProjectPositionCalculator()): TerrainView? {
            if (plane == null) return GRID
            return TerrainFrame.of(plane, crs, calculator)?.let(::TerrainView)
        }
    }
}
