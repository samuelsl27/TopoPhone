package es.topophone.core.survey

import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.SurfaceExtent

/** Lo que sale de pasar los vértices de una superficie al sistema del proyecto (S042/RF-6, RF-7). */
sealed interface SurfaceProjection {
    /** Los vértices en Este y Norte del sistema del proyecto y la cota en metros, sin redondear. */
    class Converted(val easting: DoubleArray, val northing: DoubleArray, val height: DoubleArray) : SurfaceProjection

    /** Algún vértice cae fuera del ámbito del sistema del proyecto o de la rejilla de ED50: la superficie entera no entra. */
    data class Rejected(val reason: CadIgnoredReason) : SurfaceProjection
}

/**
 * De las coordenadas de un LandXML a las del proyecto (S042/RF-6, RF-7): el mismo convertidor que el de un dibujo ([CadProjector],
 * S040/RF-6, RF-7) con los vértices de la superficie como un solo elemento. Este, Norte y cota pasan a metros con la unidad elegida;
 * si vienen en otro UTM se convierten por latitud y longitud, y si vienen en el sistema del proyecto se guardan tal cual tras
 * comprobar el ámbito. La cota no cambia de sistema de alturas. Un vértice fuera del ámbito rechaza la superficie entera.
 */
class SurfaceProjector(
    private val crs: ProjectCrs,
    private val source: CadSourceCrs = CadSourceCrs.Project,
    private val unit: LinearUnit = LinearUnit.METRE,
    calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
) {
    private val projector = CadProjector(crs, CadImportOptions(source = source, unit = unit, useElevations = true), calculator)

    /** Pasa los vértices de la superficie al proyecto. [easting], [northing] y [height] están en la unidad del fichero. */
    fun convert(easting: DoubleArray, northing: DoubleArray, height: DoubleArray): SurfaceProjection {
        if (easting.isEmpty()) return SurfaceProjection.Converted(DoubleArray(0), DoubleArray(0), DoubleArray(0))
        val entity = CadEntity(CadEntityKind.POINT, easting, northing, height, null)
        return when (val projection = projector.convert(entity)) {
            is CadProjection.Rejected -> SurfaceProjection.Rejected(projection.reason)
            is CadProjection.Converted -> SurfaceProjection.Converted(
                projection.entity.easting,
                projection.entity.northing,
                projection.entity.height!!,
            )
        }
    }

    /**
     * La extensión aproximada de una superficie cuyo recuadro en la unidad del fichero es el dado: el recuadro de sus cuatro esquinas
     * convertidas (RF-8). Es la que se enseña antes de importar; la exacta sale de convertir todos los vértices. null si alguna esquina
     * queda fuera del ámbito del sistema del proyecto.
     */
    fun cornersExtent(minEasting: Double, maxEasting: Double, minNorthing: Double, maxNorthing: Double): SurfaceExtent? {
        val e = doubleArrayOf(minEasting, maxEasting, maxEasting, minEasting)
        val n = doubleArrayOf(minNorthing, minNorthing, maxNorthing, maxNorthing)
        val converted = convert(e, n, DoubleArray(4)) as? SurfaceProjection.Converted ?: return null
        return SurfaceExtent(converted.easting.min(), converted.easting.max(), converted.northing.min(), converted.northing.max())
    }
}
