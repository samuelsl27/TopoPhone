package es.topophone.core.survey.cogo

import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GeometryNotice
import es.topophone.core.survey.geometry.GeometryVertex
import es.topophone.core.survey.geometry.GroundPosition
import es.topophone.core.survey.geometry.GroundScale
import kotlin.math.atan2

/**
 * Un punto de entrada de un cálculo COGO (S038/RF-3): su Este y su Norte en el SC proyectado, la altura del proyecto si la tiene y, si
 * se conocen, su posición geodésica y su altura elipsoidal. Estas tres solo hacen falta para el factor de terreno; un punto escrito a
 * mano no las trae y se calculan de su Este y su Norte.
 */
data class CogoPoint(
    val easting: Double,
    val northing: Double,
    val height: Double? = null,
    val latitudeDeg: Double? = null,
    val longitudeDeg: Double? = null,
    val ellipsoidalHeightM: Double? = null,
)

/** Azimuts de cuadrícula: horarios desde el norte, en [0, 2π) (S038, RNF-T9). */
object CogoAngles {
    const val TWO_PI = 2 * Math.PI

    /** [radians] llevado a [0, 2π). */
    fun normalize(radians: Double): Double {
        val r = radians % TWO_PI
        val positive = if (r < 0.0) r + TWO_PI else r
        return if (positive >= TWO_PI) 0.0 else positive
    }

    /** El azimut de cuadrícula de un vector de Este [de] y Norte [dn]. */
    fun azimuth(de: Double, dn: Double): Double = normalize(atan2(de, dn))
}

/**
 * La escala entre la distancia de cuadrícula y la de terreno en un cálculo COGO (S038/RF-5): el factor combinado de S008 en el
 * **primer punto de entrada** (el de inicio en una poligonal), con la altura elipsoidal de ese punto. Lo que se escribe y se muestra
 * en «Terreno» se pasa a cuadrícula multiplicando por [factor]; en «Cuadrícula» no cambia. El factor se calcula siempre, porque la
 * inversa muestra las dos distancias.
 *
 * Sin altura elipsoidal solo se usa el factor de escala y se avisa; sin factor (SC geográfico, o un punto fuera del área del SC o de
 * la rejilla de ED50) se usa 1 y se avisa (como S026/D7).
 */
class CogoScale private constructor(val mode: DistanceMode, val factor: Double, val notices: Set<GeometryNotice>) {

    /** De la distancia escrita en el modo activo a la de cuadrícula. */
    fun toGrid(active: Double): Double = if (mode == DistanceMode.GROUND) active * factor else active

    /** De la distancia de cuadrícula a la del modo activo. */
    fun toActive(grid: Double): Double = if (mode == DistanceMode.GROUND) grid / factor else grid

    companion object {
        fun forFirst(first: CogoPoint, crs: ProjectCrs, mode: DistanceMode, fallbackHeightM: Double? = null): CogoScale {
            val ground = GroundScale(crs, fallbackHeightM)
            val factor = ground.polygon(listOf(first.toVertex(crs)))
            return CogoScale(mode, factor, ground.notices)
        }

        /** Un punto sin posición geodésica la toma de su Este y su Norte; en un SC geográfico no hay factor. */
        private fun CogoPoint.toVertex(crs: ProjectCrs): GeometryVertex {
            val position = if (latitudeDeg != null && longitudeDeg != null) {
                latitudeDeg to longitudeDeg
            } else if (crs.isUtm) {
                GroundPosition.of(crs, easting, northing)
            } else {
                null
            }
            return GeometryVertex(
                easting = easting,
                northing = northing,
                height = height,
                latitudeDeg = position?.first,
                longitudeDeg = position?.second,
                ellipsoidalHeightM = ellipsoidalHeightM,
            )
        }
    }
}
