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
