package es.topophone.core.survey

import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportNumber
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointNames
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs

/** Lo que da una fila del fichero: un punto por crear o el motivo de que no se pueda (S028/RF-23). */
sealed interface ImportRowResult {
    /** La fila es válida: nombre y descripción recortados, lo escrito en metros (o grados) y las coordenadas calculadas. */
    data class Valid(val name: String, val description: String, val typed: TypedCoordinates, val coordinates: PointCoordinates) :
        ImportRowResult {
        /** El punto importado, sin medida, jalón ni calidad (S028/RF-27); [name] cambia el nombre si era un repetido (RF-18). */
        fun toPoint(id: String, projectId: String, importId: String, createdAtMs: Long, name: String = this.name) = Point(
            id = id,
            projectId = projectId,
            name = name,
            description = description,
            origin = PointOrigin.IMPORTED,
            coordinates = coordinates,
            measurement = null,
            typed = typed,
            edited = false,
            hidden = false,
            copyOf = null,
            createdAtMs = createdAtMs,
            deletedAtMs = null,
            importId = importId,
        )
    }

    data class Invalid(val reason: ImportErrorReason) : ImportRowResult
}

/**
 * De una fila del fichero a un punto (S028/RF-12 a RF-16, RF-20, RF-23, RF-27): las columnas que dice la asignación, las unidades a
 * metros y, por la misma ruta que un punto manual (S011/RF-17 a RF-20), las coordenadas del punto del suelo y del SC del proyecto,
 * con su ámbito y el del modelo de geoide.
 */
class PointImporter(private val manual: ManualCoordinates = ManualCoordinates()) {

    fun convert(cells: List<String>, options: ImportOptions, crs: ProjectCrs, heightSystem: HeightSystem): ImportRowResult {
        val mapping = options.mapping
        val nameIndex = mapping.indexOf(ColumnRole.NAME)
        val firstIndex = mapping.indexOf(ColumnRole.FIRST)
        val secondIndex = mapping.indexOf(ColumnRole.SECOND)
        if (nameIndex == null || firstIndex == null || secondIndex == null || maxOf(nameIndex, firstIndex, secondIndex) >= cells.size) {
            return ImportRowResult.Invalid(ImportErrorReason.MISSING_COLUMNS)
        }

        val name = PointNames.clean(cells[nameIndex])
        if (!PointNames.isValid(name)) return ImportRowResult.Invalid(ImportErrorReason.MISSING_NAME)

        val geographic = options.coordinates.form == EntryForm.GEOGRAPHIC
        val unit = options.unit
        val first = ImportNumber.parse(cells[firstIndex], options.decimal)
        val second = ImportNumber.parse(cells[secondIndex], options.decimal)
        if (first == null || second == null) return ImportRowResult.Invalid(ImportErrorReason.NOT_A_NUMBER)

        val height = mapping.indexOf(ColumnRole.HEIGHT)
            ?.let { cells.getOrNull(it) }
            ?.let { ImportNumber.parse(it, options.decimal) }
            ?.takeUnless { options.zeroAsNoHeight && it == 0.0 }
            ?.let { unit.toMetres(it) }
        val description = mapping.indexOf(ColumnRole.DESCRIPTION)?.let { cells.getOrNull(it) }.orEmpty().trim()

        val typed = TypedCoordinates(
            form = options.coordinates.form,
            first = if (geographic) first else unit.toMetres(first),
            second = if (geographic) second else unit.toMetres(second),
            height = height,
            heightKind = options.coordinates.heightKind,
        )
        return when (val result = manual.compute(typed, crs, heightSystem)) {
            is ManualResult.Computed -> ImportRowResult.Valid(name, description, typed, result.coordinates)
            is ManualResult.Rejected -> ImportRowResult.Invalid(reasonOf(result.reason))
        }
    }

    private fun reasonOf(reason: PositionUnavailable) = when (reason) {
        PositionUnavailable.OUTSIDE_GEOID -> ImportErrorReason.OUTSIDE_GEOID
        PositionUnavailable.OUTSIDE_ED50_GRID -> ImportErrorReason.OUTSIDE_ED50_GRID
        // Sin altura no se rechaza nunca una fila (S011/RF-18); fuera del ámbito es el motivo que queda.
        PositionUnavailable.OUTSIDE_CRS_AREA, PositionUnavailable.NO_HEIGHT -> ImportErrorReason.OUTSIDE_CRS_AREA
    }
}
