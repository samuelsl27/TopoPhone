package es.topophone.data.gpkg

import es.topophone.core.formats.gpkg.GpkgGeometry
import es.topophone.core.formats.gpkg.GpkgGeometryReader
import es.topophone.core.formats.gpkg.GpkgPart
import es.topophone.core.formats.gpkg.GpkgPartKind
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.CadProjection
import es.topophone.core.survey.CadProjector
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.core.survey.TerrainView

/** Lo que sale de pasar un objeto del GeoPackage al sistema del proyecto (S048/RF-11, RF-17, RF-18). */
sealed interface GpkgConversion {
    /** Los elementos del objeto en el sistema del proyecto, sin redondear; un polígono da el contorno y un elemento por hueco. */
    class Converted(val parts: List<CadEntity>) : GpkgConversion

    /** El objeto entero queda fuera, con su motivo. */
    data class Ignored(val reason: GpkgIgnoredReason) : GpkgConversion
}

/**
 * Del blob de geometría de un objeto a sus elementos en el sistema del proyecto (S048/RF-8, RF-11, RF-17 a RF-19): lo lee con
 * [GpkgGeometryReader], pasa cada vértice por [CadProjector] y, si alguno cae fuera del ámbito, descarta el objeto entero. Lo usan la
 * importación y el recuento de la zona, para que cuenten lo mismo.
 */
class GpkgFeatureConverter(
    source: CadSourceCrs,
    crs: ProjectCrs,
    useElevations: Boolean,
    calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
    /** S058/RF-17: el paso de terreno a cuadrícula del proyecto; las coordenadas «del proyecto» de una capa son de terreno. */
    terrain: TerrainView = TerrainView.GRID,
) {
    // Las coordenadas de una capa en un SC de la biblioteca vienen en la unidad de ese SC (S057/RF-3); las de España, en metros.
    private val projector = CadProjector(
        crs,
        CadImportOptions(
            source = source,
            unit = (source as? CadSourceCrs.Library)?.unit ?: LinearUnit.METRE,
            useElevations = useElevations,
        ),
        calculator,
        terrain,
    )

    /**
     * El objeto de [blob] (null es un campo NULL: vacío), con [name] como nombre de cada elemento y [color] (ARGB) como el suyo propio, o
     * el de su capa si es `null` (S031/RF-12).
     */
    fun convert(blob: ByteArray?, name: String? = null, color: Int? = null): GpkgConversion {
        if (blob == null) return GpkgConversion.Ignored(GpkgIgnoredReason.EMPTY_GEOMETRY)
        val parts = when (val geometry = GpkgGeometryReader.read(blob)) {
            GpkgGeometry.Empty -> return GpkgConversion.Ignored(GpkgIgnoredReason.EMPTY_GEOMETRY)
            GpkgGeometry.Curve -> return GpkgConversion.Ignored(GpkgIgnoredReason.CURVE_GEOMETRY)
            GpkgGeometry.Invalid -> return GpkgConversion.Ignored(GpkgIgnoredReason.INVALID_GEOMETRY)
            is GpkgGeometry.Parts -> geometry.parts
        }
        val out = ArrayList<CadEntity>(parts.size)
        for (part in parts) {
            when (val projection = projector.convert(part.toEntity(name, color))) {
                is CadProjection.Converted -> out += projection.entity
                is CadProjection.Rejected -> return GpkgConversion.Ignored(projection.reason.toIgnored())
            }
        }
        return GpkgConversion.Converted(out)
    }

    /** Un punto (x, y) del sistema de la capa en el del proyecto, o null si cae fuera de su ámbito. */
    fun project(x: Double, y: Double): Pair<Double, Double>? {
        val entity = CadEntity(CadEntityKind.POINT, doubleArrayOf(x), doubleArrayOf(y), null, null)
        val projected = (projector.convert(entity) as? CadProjection.Converted)?.entity ?: return null
        return projected.easting[0] to projected.northing[0]
    }

    private fun GpkgPart.toEntity(name: String?, color: Int?) = CadEntity(
        kind = if (kind == GpkgPartKind.POINT) CadEntityKind.POINT else CadEntityKind.POLYLINE,
        easting = x,
        northing = y,
        height = z,
        color = color?.let { CadColor(it) },
        name = name,
        polygonRing = isPolygonRing,
    )

    private fun CadIgnoredReason.toIgnored() = when (this) {
        CadIgnoredReason.OUTSIDE_CRS_AREA -> GpkgIgnoredReason.OUTSIDE_CRS_AREA
        CadIgnoredReason.OUTSIDE_ED50_GRID -> GpkgIgnoredReason.OUTSIDE_ED50_GRID
        else -> GpkgIgnoredReason.INVALID_GEOMETRY
    }
}
