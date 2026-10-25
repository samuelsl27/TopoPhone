package es.topophone.core.survey

import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.DuplicatePolicy
import es.topophone.core.model.point.ImportCounts
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.point.ImportPreviewRow
import es.topophone.core.model.point.ImportRowError
import es.topophone.core.model.point.ImportRowStatus
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointNames
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs

/**
 * El análisis de un fichero (S028/RF-21): los recuentos, las filas de la vista previa y los primeros errores. [blockedByDuplicates]
 * es cierto si se eligió cancelar ante un repetido y hay alguno (RF-17). No guarda los puntos.
 */
data class ImportAnalysis(
    val counts: ImportCounts,
    val preview: List<ImportPreviewRow>,
    val errors: List<ImportRowError>,
    val blockedByDuplicates: Boolean,
)

/** Los puntos de las filas válidas, en el orden del fichero, y los recuentos de esa pasada (S028/RF-25, RF-28). */
data class ImportBatch(val points: List<Point>, val counts: ImportCounts)

/**
 * Recorre las filas de un fichero con unas opciones (S028/RF-17 a RF-19, RF-21): valida cada una con [PointImporter] y resuelve los
 * nombres repetidos. Un nombre ya ocupado, en el proyecto o por una fila anterior del fichero, es un repetido: se salta, se
 * renombra con el siguiente nombre libre o bloquea la importación. Los puntos del proyecto no se tocan nunca.
 */
class ImportAnalyzer(private val importer: PointImporter = PointImporter()) {

    /** Lo que pasa con una fila, para que el análisis y la importación compartan el recorrido. */
    private sealed interface Outcome {
        data class Entered(val point: ImportRowResult.Valid, val finalName: String, val renamed: Boolean) : Outcome

        data object Skipped : Outcome

        data class Failed(val error: ImportRowError) : Outcome
    }

    /**
     * Analiza [rows] (la cabecera incluida, si la hay) sin retener los puntos. [takenNames] son los nombres del proyecto; [maxErrors] limita
     * la lista de errores, no su recuento; [checkCancelled] se llama antes de cada fila y puede lanzar la cancelación.
     */
    fun analyze(
        rows: Sequence<DelimitedRow>,
        options: ImportOptions,
        crs: ProjectCrs,
        heightSystem: HeightSystem,
        takenNames: Set<String>,
        maxErrors: Int = MAX_ERRORS,
        checkCancelled: () -> Unit = {},
    ): ImportAnalysis {
        val preview = ArrayList<ImportPreviewRow>(PREVIEW_ROWS)
        val errors = ArrayList<ImportRowError>()
        val counts = traverse(rows, options, crs, heightSystem, takenNames, checkCancelled) { row, outcome ->
            if (outcome is Outcome.Failed && errors.size < maxErrors) errors += outcome.error
            if (preview.size < PREVIEW_ROWS) preview += previewRow(row, outcome, options)
        }
        val blocked = options.duplicates == DuplicatePolicy.CANCEL && counts.repeated > 0
        return ImportAnalysis(counts, preview, errors, blocked)
    }

    /**
     * Recorre [rows] otra vez y reúne los puntos de las filas válidas, con [newId] para el identificador de cada uno. Los recuentos son
     * los de esta pasada (RF-28).
     */
    fun collect(
        rows: Sequence<DelimitedRow>,
        options: ImportOptions,
        crs: ProjectCrs,
        heightSystem: HeightSystem,
        takenNames: Set<String>,
        projectId: String,
        importId: String,
        createdAtMs: Long,
        newId: () -> String,
        checkCancelled: () -> Unit = {},
    ): ImportBatch {
        val points = ArrayList<Point>()
        val counts = traverse(rows, options, crs, heightSystem, takenNames, checkCancelled) { _, outcome ->
            if (outcome is Outcome.Entered) points += outcome.point.toPoint(newId(), projectId, importId, createdAtMs, outcome.finalName)
        }
        return ImportBatch(points, counts)
    }

    private fun traverse(
        rows: Sequence<DelimitedRow>,
        options: ImportOptions,
        crs: ProjectCrs,
        heightSystem: HeightSystem,
        takenNames: Set<String>,
        checkCancelled: () -> Unit,
        onRow: (DelimitedRow, Outcome) -> Unit,
    ): ImportCounts {
        val taken = HashSet(takenNames)
        var total = 0
        var valid = 0
        var skipped = 0
        var renamed = 0
        var failed = 0
        val data = if (options.hasHeader) rows.drop(1) else rows
        for (row in data) {
            checkCancelled()
            total++
            val outcome = when (val result = importer.convert(row.cells, options, crs, heightSystem)) {
                is ImportRowResult.Invalid -> Outcome.Failed(ImportRowError(row.line, result.reason))
                is ImportRowResult.Valid -> when {
                    taken.add(result.name) -> Outcome.Entered(result, result.name, renamed = false)
                    options.duplicates == DuplicatePolicy.RENAME -> {
                        val name = PointNames.nextFree(result.name, taken)
                        taken.add(name)
                        Outcome.Entered(result, name, renamed = true)
                    }
                    else -> Outcome.Skipped
                }
            }
            when (outcome) {
                is Outcome.Entered -> {
                    valid++
                    if (outcome.renamed) renamed++
                }
                Outcome.Skipped -> skipped++
                is Outcome.Failed -> failed++
            }
            onRow(row, outcome)
        }
        return ImportCounts(rows = total, valid = valid, skipped = skipped, renamed = renamed, errors = failed)
    }

    private fun previewRow(row: DelimitedRow, outcome: Outcome, options: ImportOptions): ImportPreviewRow {
        fun cell(role: ColumnRole) = options.mapping.indexOf(role)?.let { row.cells.getOrNull(it) }.orEmpty()
        val status = when (outcome) {
            is Outcome.Entered -> if (outcome.renamed) ImportRowStatus.REPEATED_RENAMED else ImportRowStatus.VALID
            Outcome.Skipped -> ImportRowStatus.REPEATED_SKIPPED
            is Outcome.Failed -> ImportRowStatus.ERROR
        }
        return ImportPreviewRow(
            line = row.line,
            name = cell(ColumnRole.NAME),
            first = cell(ColumnRole.FIRST),
            second = cell(ColumnRole.SECOND),
            height = cell(ColumnRole.HEIGHT),
            description = cell(ColumnRole.DESCRIPTION),
            status = status,
            reason = (outcome as? Outcome.Failed)?.error?.reason,
            renamedTo = (outcome as? Outcome.Entered)?.finalName?.takeIf { outcome.renamed },
        )
    }

    companion object {
        /** Las filas de la vista previa (RF-21). */
        const val PREVIEW_ROWS = 20

        /** Los errores que lista el asistente; el informe de texto lleva todos (RF-24). */
        const val MAX_ERRORS = 500
    }
}
