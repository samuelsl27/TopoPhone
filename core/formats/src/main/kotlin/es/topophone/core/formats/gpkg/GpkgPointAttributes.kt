package es.topophone.core.formats.gpkg

import es.topophone.core.formats.csv.AttributeColumn
import es.topophone.core.formats.csv.BaseProjection
import es.topophone.core.formats.csv.CsvExport
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.hasMeasurement
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project

/** El tipo de una columna de la capa de puntos: los tres de S053/RF-4 y el booleano de GeoPackage, que es un entero 0 o 1 (D2). */
enum class GpkgAttributeType(val sql: String) {
    TEXT("TEXT"),
    INTEGER("INTEGER"),
    REAL("REAL"),
    BOOLEAN("BOOLEAN"),
}

/**
 * Los atributos de la capa de puntos de la exportación a GeoPackage (S053/RF-4, RF-5, plan A1): las columnas de "Todas las columnas" de S012
 * con su tipo y el valor de cada punto sin redondear. Las longitudes van en metros y los ángulos en grados, sin pasar por la unidad del
 * proyecto (D1); lo que el punto no tiene es `null`. Los textos son los mismos del CSV (origen, sistema, fechas en UTC, lista de fallos).
 */
object GpkgPointAttributes {
    private const val LIST_SEPARATOR = "|"

    /** El tipo de la columna de un atributo de un código: Entero, Número como real, y Texto y Lista como texto (S024/RF-21). */
    fun typeOf(column: AttributeColumn): GpkgAttributeType = when (column.type) {
        AttributeType.INTEGER -> GpkgAttributeType.INTEGER
        AttributeType.NUMBER -> GpkgAttributeType.REAL
        AttributeType.TEXT, AttributeType.LIST -> GpkgAttributeType.TEXT
    }

    /** El valor de [column] en [point], del tipo de la columna y sin redondear, o `null` si no lo tiene o está vacío (S024/RF-21). */
    fun attributeValue(point: Point, column: AttributeColumn): Any? = attributeValue(point.code, column)

    /** El valor de [column] en el [code] de un punto o de una línea, del tipo de la columna y sin redondear, o `null` (S025/RF-26). */
    fun attributeValue(code: PointCode?, column: AttributeColumn): Any? {
        val text = column.valueOf(code)?.value?.takeIf { it.isNotEmpty() } ?: return null
        return when (column.type) {
            AttributeType.INTEGER -> text.toLongOrNull()
            AttributeType.NUMBER -> text.toDoubleOrNull()
            AttributeType.TEXT, AttributeType.LIST -> text
        }
    }

    fun typeOf(column: ExportColumn): GpkgAttributeType = when (column) {
        ExportColumn.NAME,
        ExportColumn.DESCRIPTION,
        ExportColumn.ORIGIN,
        ExportColumn.CRS,
        ExportColumn.HEIGHT_SYSTEM,
        ExportColumn.UNITS,
        ExportColumn.SOLUTION,
        ExportColumn.MODE,
        ExportColumn.OUT_OF_TOLERANCE,
        ExportColumn.MEASURED_FROM,
        ExportColumn.MEASURED_TO,
        ExportColumn.TILT_MODE,
        ExportColumn.EQUIPMENT_MODEL,
        ExportColumn.EQUIPMENT_SERIAL,
        ExportColumn.EQUIPMENT_FIRMWARE,
        ExportColumn.COPY_OF,
        ExportColumn.STAKE_DESIGN,
        ExportColumn.CODE,
        -> GpkgAttributeType.TEXT
        ExportColumn.SATELLITES,
        ExportColumn.EPOCHS_USED,
        ExportColumn.EPOCHS_DISCARDED,
        -> GpkgAttributeType.INTEGER
        ExportColumn.REPLAY,
        ExportColumn.TILT_MODE_VERIFIED,
        ExportColumn.AUTOMATIC,
        ExportColumn.EDITED,
        ExportColumn.STAKE_WITHIN,
        -> GpkgAttributeType.BOOLEAN
        ExportColumn.EASTING,
        ExportColumn.NORTHING,
        ExportColumn.ELEVATION,
        ExportColumn.LATITUDE,
        ExportColumn.LONGITUDE,
        ExportColumn.ELLIPSOIDAL_HEIGHT,
        ExportColumn.POLE_HEIGHT,
        ExportColumn.HDOP,
        ExportColumn.CORRECTION_AGE,
        ExportColumn.HORIZONTAL_ACCURACY,
        ExportColumn.VERTICAL_ACCURACY,
        ExportColumn.BASE_LATITUDE,
        ExportColumn.BASE_LONGITUDE,
        ExportColumn.BASE_ELLIPSOIDAL_HEIGHT,
        ExportColumn.BASE_EASTING,
        ExportColumn.BASE_NORTHING,
        ExportColumn.BASE_ELEVATION,
        ExportColumn.BASELINE,
        ExportColumn.TILT,
        ExportColumn.TILT_DIRECTION,
        ExportColumn.HEADING,
        ExportColumn.MAX_TILT,
        ExportColumn.TILT_HORIZONTAL_ACCURACY,
        ExportColumn.TILT_VERTICAL_ACCURACY,
        ExportColumn.AVERAGED_TIME,
        ExportColumn.DEVIATION_HORIZONTAL,
        ExportColumn.DEVIATION_VERTICAL,
        ExportColumn.STAKE_DESIGN_ELEVATION,
        ExportColumn.STAKE_D_NORTH,
        ExportColumn.STAKE_D_EAST,
        ExportColumn.STAKE_D_HORIZONTAL,
        ExportColumn.STAKE_D_ELEVATION,
        ExportColumn.SHIFT_DX,
        ExportColumn.SHIFT_DY,
        ExportColumn.SHIFT_DZ,
        -> GpkgAttributeType.REAL
    }

    /**
     * El valor de [column] en [point]: un [String], [Long], [Double] o [Boolean] según [typeOf], o `null` si el punto no lo tiene.
     * [projected] es la base del punto en el sistema del proyecto, que calcula quien exporta (S015/RF-22); sin ella esas columnas van vacías.
     */
    fun value(point: Point, column: ExportColumn, project: Project, projected: BaseProjection?): Any? {
        val coordinates = point.coordinates
        val measurement = point.measurement
        val quality = measurement?.quality
        val base = measurement?.base
        val tilt = measurement?.tilt
        val angles = tilt?.angles
        val staked = point.staked
        fun degrees(valueRad: Double?) = valueRad?.let(Math::toDegrees)
        return when (column) {
            ExportColumn.NAME -> point.name
            ExportColumn.CODE -> point.code?.code
            ExportColumn.DESCRIPTION -> point.description
            ExportColumn.ORIGIN -> when {
                point.origin == PointOrigin.IMPORTED -> "Imported"
                point.origin == PointOrigin.ON_ALIGNMENT -> "OnAlignment"
                point.origin == PointOrigin.COGO -> "Cogo" // S038/RF-24
                point.origin.hasMeasurement -> "Measured"
                else -> "Manual"
            }
            ExportColumn.EASTING -> coordinates.easting
            ExportColumn.NORTHING -> coordinates.northing
            ExportColumn.ELEVATION -> coordinates.height
            ExportColumn.LATITUDE -> coordinates.ground.latitudeDeg
            ExportColumn.LONGITUDE -> coordinates.ground.longitudeDeg
            ExportColumn.ELLIPSOIDAL_HEIGHT -> coordinates.ground.ellipsoidalHeightM
            ExportColumn.CRS -> "EPSG:${project.crs.epsg}"
            ExportColumn.HEIGHT_SYSTEM -> if (project.heightSystem == HeightSystem.ORTHOMETRIC) "EGM08-REDNAP" else "Ellipsoidal"
            ExportColumn.UNITS -> unitCode(project.linearUnit)
            ExportColumn.POLE_HEIGHT -> measurement?.poleHeightM
            ExportColumn.SOLUTION -> quality?.solution
            ExportColumn.SATELLITES -> quality?.satellites?.toLong()
            ExportColumn.HDOP -> quality?.hdop
            ExportColumn.CORRECTION_AGE -> quality?.correctionAgeS
            ExportColumn.HORIZONTAL_ACCURACY -> quality?.horizontalM
            ExportColumn.VERTICAL_ACCURACY -> quality?.verticalM
            ExportColumn.MODE -> quality?.mode?.let { if (it == MeasurementMode.INSTANT) "Instant" else "Averaged" }
            ExportColumn.EPOCHS_USED -> quality?.epochsUsed?.toLong()
            ExportColumn.EPOCHS_DISCARDED -> quality?.epochsDiscarded?.toLong()
            ExportColumn.REPLAY -> quality?.fromReplay
            ExportColumn.OUT_OF_TOLERANCE -> measurement?.outOfTolerance?.sorted()?.joinToString(LIST_SEPARATOR) { it.name }
            ExportColumn.MEASURED_FROM -> measurement?.measuredFromMs?.let(CsvExport::utc)
            ExportColumn.MEASURED_TO -> measurement?.measuredToMs?.let(CsvExport::utc)
            ExportColumn.BASE_LATITUDE -> base?.position?.latitudeDeg
            ExportColumn.BASE_LONGITUDE -> base?.position?.longitudeDeg
            ExportColumn.BASE_ELLIPSOIDAL_HEIGHT -> base?.position?.ellipsoidalHeightM
            ExportColumn.BASE_EASTING -> projected?.takeIf { base != null }?.easting
            ExportColumn.BASE_NORTHING -> projected?.takeIf { base != null }?.northing
            ExportColumn.BASE_ELEVATION -> projected?.takeIf { base != null }?.height
            ExportColumn.BASELINE -> base?.baselineM
            ExportColumn.TILT_MODE -> tilt?.mode?.name
            ExportColumn.TILT_MODE_VERIFIED -> tilt?.takeIf { it.mode.usesTilt }?.verified
            ExportColumn.TILT -> degrees(angles?.tiltRad)
            ExportColumn.TILT_DIRECTION -> degrees(angles?.directionRad)
            ExportColumn.HEADING -> degrees(angles?.headingRad)
            ExportColumn.MAX_TILT -> degrees(angles?.maxTiltRad)
            ExportColumn.TILT_HORIZONTAL_ACCURACY -> angles?.horizontalAccuracyM
            ExportColumn.TILT_VERTICAL_ACCURACY -> angles?.verticalAccuracyM
            ExportColumn.AVERAGED_TIME -> quality?.averagedTimeS
            ExportColumn.DEVIATION_HORIZONTAL -> quality?.deviationHorizontalM
            ExportColumn.DEVIATION_VERTICAL -> quality?.deviationVerticalM
            ExportColumn.AUTOMATIC -> quality?.auto
            ExportColumn.EQUIPMENT_MODEL -> measurement?.equipment?.model?.takeIf { it.isNotEmpty() }
            ExportColumn.EQUIPMENT_SERIAL -> measurement?.equipment?.serial?.takeIf { it.isNotEmpty() }
            ExportColumn.EQUIPMENT_FIRMWARE -> measurement?.equipment?.firmware?.takeIf { it.isNotEmpty() }
            ExportColumn.EDITED -> point.edited
            ExportColumn.COPY_OF -> point.copyOf
            ExportColumn.STAKE_DESIGN -> staked?.designName
            ExportColumn.STAKE_DESIGN_ELEVATION -> staked?.designElevationM
            ExportColumn.STAKE_D_NORTH -> staked?.dNorthM
            ExportColumn.STAKE_D_EAST -> staked?.dEastM
            ExportColumn.STAKE_D_HORIZONTAL -> staked?.dHorizontalM
            ExportColumn.STAKE_D_ELEVATION -> staked?.dHeightM
            ExportColumn.STAKE_WITHIN -> staked?.withinTolerance
            ExportColumn.SHIFT_DX -> measurement?.shift?.vector?.dx
            ExportColumn.SHIFT_DY -> measurement?.shift?.vector?.dy
            ExportColumn.SHIFT_DZ -> measurement?.shift?.vector?.dz
        }
    }

    private fun unitCode(unit: LinearUnit): String = when (unit) {
        LinearUnit.METRE -> "m"
        LinearUnit.INTERNATIONAL_FOOT -> "ft"
        LinearUnit.US_SURVEY_FOOT -> "US ft"
    }
}
