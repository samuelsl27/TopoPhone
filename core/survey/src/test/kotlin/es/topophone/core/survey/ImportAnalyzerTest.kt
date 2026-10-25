package es.topophone.core.survey

import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.DuplicatePolicy
import es.topophone.core.model.point.ImportCounts
import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.point.ImportRowError
import es.topophone.core.model.point.ImportRowStatus
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.coroutines.cancellation.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Los nombres repetidos y el análisis de un fichero entero (S028/RF-17, RF-18, RF-19, RF-21, RF-28). */
class ImportAnalyzerTest {

    private val analyzer = ImportAnalyzer()
    private val crs = ProjectCrs.ETRS89_UTM30
    private val options = ImportOptions(mapping = ColumnMapping.penzd(5))

    private fun row(line: Int, name: String, east: String = "440297.988", north: String = "4474244.001", height: String = "646.377") =
        DelimitedRow(line, listOf(name, east, north, height, "desc $name"))

    private fun rows(vararg names: String, firstLine: Int = 1) = names.mapIndexed { i, name -> row(firstLine + i, name) }

    private fun analyze(
        rows: List<DelimitedRow>,
        options: ImportOptions = this.options,
        taken: Set<String> = emptySet(),
        maxErrors: Int = ImportAnalyzer.MAX_ERRORS,
    ) = analyzer.analyze(rows.asSequence(), options, crs, HeightSystem.ORTHOMETRIC, taken, maxErrors)

    private fun collect(rows: List<DelimitedRow>, options: ImportOptions = this.options, taken: Set<String> = emptySet()): ImportBatch {
        var next = 0
        return analyzer.collect(
            rows = rows.asSequence(),
            options = options,
            crs = crs,
            heightSystem = HeightSystem.ORTHOMETRIC,
            takenNames = taken,
            projectId = "p1",
            importId = "imp-1",
            createdAtMs = 99L,
            newId = { "id-${++next}" },
        )
    }

    @Test
    fun `S028 RF-21 cuenta las filas validas y las erroneas con su linea y su motivo`() {
        val read = listOf(
            row(1, "1"),
            row(2, "2", east = "abc"),
            row(3, ""),
            row(4, "4"),
            DelimitedRow(5, listOf("5", "440297.988")),
        )

        val result = analyze(read)

        result.counts shouldBe ImportCounts(rows = 5, valid = 2, skipped = 0, renamed = 0, errors = 3)
        result.counts.isConsistent shouldBe true
        result.errors shouldBe listOf(
            ImportRowError(2, ImportErrorReason.NOT_A_NUMBER),
            ImportRowError(3, ImportErrorReason.MISSING_NAME),
            ImportRowError(5, ImportErrorReason.MISSING_COLUMNS),
        )
    }

    @Test
    fun `S028 RF-21 un fichero sin filas da recuentos a cero`() {
        val result = analyze(emptyList())

        result.counts shouldBe ImportCounts()
        result.preview.shouldBeEmpty()
        result.errors.shouldBeEmpty()
        result.blockedByDuplicates shouldBe false
    }

    @Test
    fun `S028 RF-21 la cabecera no es una fila de datos y las demas conservan su linea`() {
        val read =
            listOf(DelimitedRow(1, listOf("Name", "Easting", "Northing", "Elevation", "Description"))) + rows("1", "2", firstLine = 2)

        val withHeader = analyze(read, options.copy(hasHeader = true))
        val withoutHeader = analyze(read, options.copy(hasHeader = false))

        withHeader.counts shouldBe ImportCounts(rows = 2, valid = 2)
        withHeader.preview.map { it.line } shouldBe listOf(2, 3)
        withoutHeader.counts shouldBe ImportCounts(rows = 3, valid = 2, errors = 1)
        withoutHeader.errors shouldBe listOf(ImportRowError(1, ImportErrorReason.NOT_A_NUMBER))
    }

    @Test
    fun `S028 RF-17 saltar es lo que se hace por defecto con un repetido del proyecto o del propio fichero`() {
        val result = analyze(rows("1", "2", "2", "3"), taken = setOf("1"))

        options.duplicates shouldBe DuplicatePolicy.SKIP
        result.counts shouldBe ImportCounts(rows = 4, valid = 2, skipped = 2, renamed = 0, errors = 0)
        result.counts.repeated shouldBe 2
        result.counts.isConsistent shouldBe true
        result.preview.map { it.status } shouldBe
            listOf(ImportRowStatus.REPEATED_SKIPPED, ImportRowStatus.VALID, ImportRowStatus.REPEATED_SKIPPED, ImportRowStatus.VALID)
        result.blockedByDuplicates shouldBe false
    }

    @Test
    fun `S028 RF-18 renombrar da al repetido el siguiente nombre libre del proyecto`() {
        val result = analyze(rows("1", "1", "P1", "P1"), options.copy(duplicates = DuplicatePolicy.RENAME), taken = setOf("1"))

        result.counts shouldBe ImportCounts(rows = 4, valid = 4, skipped = 0, renamed = 3, errors = 0)
        result.counts.isConsistent shouldBe true
        result.preview.map { it.status } shouldBe
            listOf(
                ImportRowStatus.REPEATED_RENAMED,
                ImportRowStatus.REPEATED_RENAMED,
                ImportRowStatus.VALID,
                ImportRowStatus.REPEATED_RENAMED,
            )
        result.preview.map { it.renamedTo } shouldBe listOf("2", "3", null, "P2")
    }

    @Test
    fun `S028 RF-18 el nombre nuevo no choca con uno que viene mas abajo en el fichero`() {
        val batch = collect(rows("1", "1", "2"), options.copy(duplicates = DuplicatePolicy.RENAME))

        batch.points.map { it.name } shouldBe listOf("1", "2", "3")
        batch.counts shouldBe ImportCounts(rows = 3, valid = 3, skipped = 0, renamed = 2, errors = 0)
    }

    @Test
    fun `S028 RF-18 el nombre nuevo conserva los ceros a la izquierda`() {
        val batch = collect(rows("009", "009"), options.copy(duplicates = DuplicatePolicy.RENAME))

        batch.points.map { it.name } shouldBe listOf("009", "010")
    }

    @Test
    fun `S028 RF-17 cancelar marca los repetidos y bloquea la importacion`() {
        val cancel = options.copy(duplicates = DuplicatePolicy.CANCEL)

        analyze(rows("1", "2", "2"), cancel).blockedByDuplicates shouldBe true
        analyze(rows("1", "2"), cancel, taken = setOf("2")).blockedByDuplicates shouldBe true
        analyze(rows("1", "2"), cancel).blockedByDuplicates shouldBe false
        analyze(rows("1", "2", "2"), options).blockedByDuplicates shouldBe false
    }

    @Test
    fun `S028 RF-17 con cancelar los repetidos se cuentan y las cuentas siguen cuadrando`() {
        val result = analyze(rows("1", "2", "2"), options.copy(duplicates = DuplicatePolicy.CANCEL))

        result.counts.repeated shouldBe 1
        result.counts.isConsistent shouldBe true
        result.preview.last().status shouldBe ImportRowStatus.REPEATED_SKIPPED
    }

    @Test
    fun `S028 RF-17 los nombres se comparan exactamente, P1 y p1 son distintos`() {
        val result = analyze(rows("P1", "p1", "P1 "), taken = setOf("P1"))

        result.counts shouldBe ImportCounts(rows = 3, valid = 1, skipped = 2, renamed = 0, errors = 0)
        result.preview.map { it.status } shouldBe
            listOf(ImportRowStatus.REPEATED_SKIPPED, ImportRowStatus.VALID, ImportRowStatus.REPEATED_SKIPPED)
    }

    @Test
    fun `S028 RF-19 los nombres del proyecto no se tocan ni se devuelven`() {
        val taken = mutableSetOf("1", "2")
        val batch = analyzer.collect(
            rows = rows("1", "2", "3").asSequence(),
            options = options.copy(duplicates = DuplicatePolicy.RENAME),
            crs = crs,
            heightSystem = HeightSystem.ORTHOMETRIC,
            takenNames = taken,
            projectId = "p1",
            importId = "imp-1",
            createdAtMs = 1L,
            newId = { "x" },
        )

        taken shouldBe setOf("1", "2")
        batch.points.map { it.name } shouldBe listOf("3", "4", "5")
    }

    @Test
    fun `S028 RF-21 la vista previa son las 20 primeras filas con lo leido sin interpretar`() {
        val read = (1..30).map { row(it, "$it", east = "440297,988") }

        val result = analyze(read, options.copy(decimal = es.topophone.core.model.point.ImportDecimal.COMMA))

        result.preview.size shouldBe 20
        result.counts.rows shouldBe 30
        result.preview.first().let {
            it.line shouldBe 1
            it.name shouldBe "1"
            it.first shouldBe "440297,988"
            it.second shouldBe "4474244.001"
            it.height shouldBe "646.377"
            it.description shouldBe "desc 1"
        }
    }

    @Test
    fun `S028 RF-21 la vista previa lleva el estado y el motivo de cada fila`() {
        val read = listOf(row(1, "1"), row(2, "2", east = "abc"), row(3, "1"))

        val result = analyze(read)

        result.preview.map { it.status } shouldBe listOf(ImportRowStatus.VALID, ImportRowStatus.ERROR, ImportRowStatus.REPEATED_SKIPPED)
        result.preview[1].reason shouldBe ImportErrorReason.NOT_A_NUMBER
        result.preview[0].reason.shouldBeNull()
    }

    @Test
    fun `S028 RF-21 las columnas que faltan en una fila corta salen vacias en la vista previa`() {
        val result = analyze(listOf(DelimitedRow(1, listOf("1", "440297.988"))))

        result.preview.single().let {
            it.name shouldBe "1"
            it.first shouldBe "440297.988"
            it.second shouldBe ""
            it.height shouldBe ""
            it.description shouldBe ""
        }
    }

    @Test
    fun `S028 RF-24 la lista de errores se queda en 500 pero el recuento los cuenta todos`() {
        val read = (1..600).map { row(it, "$it", east = "x") }

        val capped = analyze(read)
        val full = analyze(read, maxErrors = Int.MAX_VALUE)

        capped.errors.size shouldBe ImportAnalyzer.MAX_ERRORS
        capped.errors.last().line shouldBe 500
        capped.counts.errors shouldBe 600
        full.errors.size shouldBe 600
        ImportAnalyzer.MAX_ERRORS shouldBe 500
    }

    @Test
    fun `S028 RF-21 el analisis recorre el fichero una vez y no guarda los puntos`() {
        var consumed = 0
        val source = generateSequence(1) { if (it < 1000) it + 1 else null }.map {
            consumed++
            row(it, "$it")
        }

        val result = analyzer.analyze(source, options, crs, HeightSystem.ORTHOMETRIC, emptySet())

        consumed shouldBe 1000
        result.counts.valid shouldBe 1000
        result.preview.size shouldBe 20
    }

    @Test
    fun `S028 RF-22 el analisis se puede cancelar a mitad del fichero`() {
        var consumed = 0
        val source = generateSequence(1) { it + 1 }.map {
            consumed++
            row(it, "$it")
        }
        var checks = 0

        assertThrows<CancellationException> {
            analyzer.analyze(source, options, crs, HeightSystem.ORTHOMETRIC, emptySet(), checkCancelled = {
                if (++checks > 5) throw CancellationException("cancelado")
            })
        }

        (consumed <= 7) shouldBe true
    }

    @Test
    fun `S028 RF-25 los puntos salen en el orden del fichero con su origen, importacion, fecha e identificador`() {
        val batch = collect(listOf(row(1, "B"), row(2, "A", east = "abc"), row(3, "C"), row(4, "B")))

        batch.points.map { it.name } shouldBe listOf("B", "C")
        batch.points.map { it.id } shouldBe listOf("id-1", "id-2")
        batch.points.forEach {
            it.projectId shouldBe "p1"
            it.origin shouldBe PointOrigin.IMPORTED
            it.importId shouldBe "imp-1"
            it.createdAtMs shouldBe 99L
            it.measurement.shouldBeNull()
        }
        batch.counts shouldBe ImportCounts(rows = 4, valid = 2, skipped = 1, renamed = 0, errors = 1)
    }

    @Test
    fun `S028 RF-28 el recuento de la importacion es el mismo que el del analisis del mismo fichero`() {
        val read = listOf(row(1, "1"), row(2, "2", east = "x"), row(3, "1"), row(4, "3"), row(5, "3"))
        val renaming = options.copy(duplicates = DuplicatePolicy.RENAME)

        analyze(read).counts shouldBe collect(read).counts
        analyze(read, renaming).counts shouldBe collect(read, renaming).counts
    }

    @Test
    fun `S028 RF-18 el punto renombrado conserva su descripcion y sus coordenadas`() {
        val batch = collect(listOf(row(1, "7"), row(2, "7")), options.copy(duplicates = DuplicatePolicy.RENAME))

        batch.points.map { it.name } shouldBe listOf("7", "8")
        batch.points[1].description shouldBe "desc 7"
        batch.points[1].typed shouldBe batch.points[0].typed
    }
}
