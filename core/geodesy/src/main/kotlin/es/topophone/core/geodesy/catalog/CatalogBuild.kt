package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.datum.DatumTransformation
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.geodesy.projection.MapProjection
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.GeoBox

/** Avisos que acompañan a un SC que se puede crear (S034/RF-10). */
enum class BuildWarning {
    /** Ninguna transformación declara cubrir el centro del proyecto; se propone la más precisa de todas. */
    TRANSFORMATION_OUTSIDE_AREA,
}

enum class UnavailableReason {
    UNKNOWN_CRS,

    /** No hay transformación entre el datum del SC y el de las correcciones (S034/RF-8). */
    NO_TRANSFORMATION,

    /** La proyección del SC no se puede construir con sus parámetros. */
    NO_PROJECTION,
}

/** Lo que da elegir un SC de la biblioteca para un proyecto (S034/RF-8, RF-12, RF-13). */
sealed interface CrsBuildResult {
    /** El SC se puede usar ya. */
    data class Ready(val definition: CrsDefinition, val warnings: Set<BuildWarning> = emptySet()) : CrsBuildResult

    /** El SC se puede usar cuando se descarguen [grids] (S034/RF-12). */
    data class NeedsGrids(val definition: CrsDefinition, val grids: List<GridEntry>, val warnings: Set<BuildWarning> = emptySet()) :
        CrsBuildResult

    /** El SC no se puede elegir; el motivo se le dice al usuario (S034/RF-8). */
    data class Unavailable(val reason: UnavailableReason) : CrsBuildResult
}

/**
 * Elige el SC [epsg] de la biblioteca para un proyecto con las correcciones en [correctionsDatum] y centrado en [centre]: propone la transformación
 * más precisa cuyo ámbito cubre el centro, o la que se pida en [choice] (S034/RF-10), y devuelve la definición completa que se guardará en el proyecto
 * (S034/RF-13). Las rejillas que no vienen en la app ni cumplen [isInstalled] se devuelven aparte (S034/RF-12).
 */
fun CrsCatalog.build(
    epsg: Int,
    correctionsDatum: CorrectionsDatum,
    centre: GeoPoint?,
    choice: TransformationOption? = null,
    isInstalled: (String) -> Boolean = { false },
): CrsBuildResult {
    val crs = crsByEpsg(epsg) ?: return CrsBuildResult.Unavailable(UnavailableReason.UNKNOWN_CRS)
    val datum = datums[crs.datumCode] ?: return CrsBuildResult.Unavailable(UnavailableReason.UNKNOWN_CRS)
    if (CrsBuilder.projection(crs, datum) == null && crs.kind == CrsKind.PROJECTED) {
        return CrsBuildResult.Unavailable(UnavailableReason.NO_PROJECTION)
    }
    val frame = correctionsDatum.frame()
    val picked = TransformationPicker.pick(this, crs.datumCode, frame, centre)
    val option = choice?.takeIf { it.datumCode == crs.datumCode && it.target == frame }
        ?: picked.best
        ?: picked.others.firstOrNull()
        ?: return CrsBuildResult.Unavailable(UnavailableReason.NO_TRANSFORMATION)
    val warnings = if (option in picked.applicable) emptySet() else setOf(BuildWarning.TRANSFORMATION_OUTSIDE_AREA)
    val definition = CrsDefinitions.of(crs, datum, option)
    val missing = GridRequirement.missing(this, option, isInstalled)
    return if (missing.isEmpty()) CrsBuildResult.Ready(definition, warnings) else CrsBuildResult.NeedsGrids(definition, missing, warnings)
}

/** De las entradas del catálogo a la definición que guarda el proyecto y de vuelta a lo que calcula (S034/RF-13). */
object CrsDefinitions {

    fun of(crs: CrsEntry, datum: DatumEntry, option: TransformationOption): CrsDefinition = CrsDefinition(
        epsg = crs.epsg,
        name = crs.name,
        projected = crs.kind == CrsKind.PROJECTED,
        datumCode = datum.code,
        datumName = datum.name,
        ellipsoidSemiMajorM = datum.ellipsoid.semiMajorAxisM,
        ellipsoidInverseFlattening = if (datum.ellipsoid.inverseFlattening.isInfinite()) 0.0 else datum.ellipsoid.inverseFlattening,
        primeMeridianDeg = datum.primeMeridianDeg,
        method = crs.method,
        parameters = crs.parameters,
        unitMetres = crs.unitMetres,
        area = crs.area?.let { GeoBox(it.southDeg, it.westDeg, it.northDeg, it.eastDeg) },
        frame = if (option.target == CorrectionFrame.ETRS89) "ETRS89" else "WGS84",
        transformationName = option.name,
        transformationAccuracyM = option.accuracyM,
        transformationArea = option.area?.let { GeoBox(it.southDeg, it.westDeg, it.northDeg, it.eastDeg) },
        transformationSteps = option.stepsText,
    )

    /**
     * Lo que calcula un SC guardado: su proyección (`null` si es geográfico), la transformación del datum del SC hasta el de las correcciones y su
     * elipsoide. No usa el catálogo; las rejillas se leen de [grids]. `null` si la definición no se puede construir.
     */
    fun resolve(definition: CrsDefinition, grids: GridSource): ResolvedCrs? {
        val ellipsoid = Ellipsoid(
            definition.ellipsoidSemiMajorM,
            if (definition.ellipsoidInverseFlattening == 0.0) Double.POSITIVE_INFINITY else definition.ellipsoidInverseFlattening,
        )
        val area = definition.area?.let { CatalogArea(it.southDeg, it.westDeg, it.northDeg, it.eastDeg) }
        val entry = CrsEntry(
            epsg = definition.epsg,
            name = definition.name,
            kind = if (definition.projected) CrsKind.PROJECTED else CrsKind.GEOGRAPHIC,
            datumCode = definition.datumCode,
            method = definition.method,
            parameters = definition.parameters,
            unitMetres = definition.unitMetres,
            area = area,
            countries = emptyList(),
            areaName = "",
        )
        val projection = if (definition.projected) {
            CrsBuilder.projection(
                entry,
                DatumEntry(definition.datumCode, definition.datumName, ellipsoid, definition.primeMeridianDeg, null),
            )
                ?: return null
        } else {
            null
        }
        val option = TransformationOption(
            datumCode = definition.datumCode,
            target = if (definition.frame == "ETRS89") CorrectionFrame.ETRS89 else CorrectionFrame.WGS84,
            name = definition.transformationName,
            accuracyM = definition.transformationAccuracyM,
            area = definition.transformationArea?.let { CatalogArea(it.southDeg, it.westDeg, it.northDeg, it.eastDeg) },
            steps = CrsCatalog.parseSteps(definition.transformationSteps),
            stepsText = definition.transformationSteps,
        )
        return ResolvedCrs(definition, projection, CatalogTransformations.build(option, grids), ellipsoid)
    }
}

/** Un SC de la biblioteca listo para calcular, sin el catálogo (S034/RF-26, RF-27). */
class ResolvedCrs(
    val definition: CrsDefinition,
    /** `null` en los SC geográficos. */
    val projection: MapProjection?,
    /** Del datum del SC al de las correcciones; su `inverse` lleva un punto de las correcciones al datum del SC. */
    val transformation: DatumTransformation,
    val ellipsoid: Ellipsoid,
)
