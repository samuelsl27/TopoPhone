package es.topophone.core.model.point

import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Las opciones de la importación, la asignación de columnas y los recuentos (S028/RF-4 a RF-24). */
class ImportOptionsTest {

    @Test
    fun `S028 RF-17 y RF-16 por defecto salta los repetidos y trata el 0 como una altura`() {
        val options = ImportOptions()

        options.duplicates shouldBe DuplicatePolicy.SKIP
        options.zeroAsNoHeight shouldBe false
    }

    @Test
    fun `S028 RF-4 a RF-9 y RF-12 y RF-14 los demas valores por defecto son los del CSV mas corriente`() {
        val options = ImportOptions()

        options.encoding shouldBe ImportEncoding.UTF8
        options.separator shouldBe ImportSeparator.COMMA
        options.decimal shouldBe ImportDecimal.POINT
        options.hasHeader shouldBe false
        options.coordinates shouldBe ImportCoordinates(EntryForm.PROJECT_CRS, HeightKind.PROJECT)
        options.unit shouldBe LinearUnit.METRE
    }

    @Test
    fun `S028 RF-8 la coma de separador y la coma decimal a la vez son un conflicto`() {
        ImportOptions(separator = ImportSeparator.COMMA, decimal = ImportDecimal.COMMA).decimalConflict shouldBe true
        ImportOptions(separator = ImportSeparator.SEMICOLON, decimal = ImportDecimal.COMMA).decimalConflict shouldBe false
        ImportOptions(separator = ImportSeparator.COMMA, decimal = ImportDecimal.POINT).decimalConflict shouldBe false
        ImportOptions(separator = ImportSeparator.TAB, decimal = ImportDecimal.COMMA).decimalConflict shouldBe false
        ImportOptions(separator = ImportSeparator.SPACES, decimal = ImportDecimal.COMMA).decimalConflict shouldBe false
    }

    @Test
    fun `S028 RF-5 cada separador sabe su caracter y los espacios no tienen uno solo`() {
        ImportSeparator.COMMA.char shouldBe ','
        ImportSeparator.SEMICOLON.char shouldBe ';'
        ImportSeparator.TAB.char shouldBe '\t'
        ImportSeparator.SPACES.char shouldBe ' '
        ImportSeparator.SPACES.mergesRuns shouldBe true
        ImportSeparator.COMMA.mergesRuns shouldBe false
        ImportDecimal.POINT.char shouldBe '.'
        ImportDecimal.COMMA.char shouldBe ','
    }

    @Test
    fun `S028 RF-10 PENZD pone nombre, Este, Norte, altura y descripcion y deja el resto sin asignar`() {
        ColumnMapping.penzd(7).roles shouldBe listOf(
            ColumnRole.NAME,
            ColumnRole.FIRST,
            ColumnRole.SECOND,
            ColumnRole.HEIGHT,
            ColumnRole.DESCRIPTION,
            ColumnRole.IGNORE,
            ColumnRole.IGNORE,
        )
    }

    @Test
    fun `S028 RF-11 PNEZD pone el Norte antes que el Este`() {
        val roles = ColumnMapping.pnezd(5).roles

        roles shouldBe listOf(ColumnRole.NAME, ColumnRole.SECOND, ColumnRole.FIRST, ColumnRole.HEIGHT, ColumnRole.DESCRIPTION)
        ColumnMapping.pnezd(5).indexOf(ColumnRole.FIRST) shouldBe 2
        ColumnMapping.pnezd(5).indexOf(ColumnRole.SECOND) shouldBe 1
    }

    @Test
    fun `S028 RF-10 un fichero con menos columnas que PENZD recorta la asignacion`() {
        ColumnMapping.penzd(3).roles shouldBe listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.SECOND)
        ColumnMapping.penzd(4).indexOf(ColumnRole.DESCRIPTION).shouldBeNull()
        ColumnMapping.penzd(0).roles shouldBe emptyList()
    }

    @Test
    fun `S028 RF-11 una asignacion con un nombre y las dos coordenadas es valida`() {
        ColumnMapping.penzd(5).problem.shouldBeNull()
        ColumnMapping.penzd(3).problem.shouldBeNull() // sin altura ni descripción
        ColumnMapping.penzd(5).isValid shouldBe true
    }

    @Test
    fun `S028 RF-11 sin nombre, sin una coordenada o con un papel repetido no es valida`() {
        val noName = ColumnMapping(listOf(ColumnRole.IGNORE, ColumnRole.FIRST, ColumnRole.SECOND))
        val noFirst = ColumnMapping(listOf(ColumnRole.NAME, ColumnRole.IGNORE, ColumnRole.SECOND))
        val noSecond = ColumnMapping(listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.IGNORE))
        val twoEast = ColumnMapping(listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.FIRST, ColumnRole.SECOND))
        val twoHeights = ColumnMapping(listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.HEIGHT, ColumnRole.HEIGHT))

        noName.problem shouldBe MappingProblem.MISSING_NAME
        noFirst.problem shouldBe MappingProblem.MISSING_FIRST
        noSecond.problem shouldBe MappingProblem.MISSING_SECOND
        twoEast.problem shouldBe MappingProblem.REPEATED_ROLE
        twoHeights.problem shouldBe MappingProblem.REPEATED_ROLE
        listOf(noName, noFirst, noSecond, twoEast, twoHeights).all { !it.isValid } shouldBe true
    }

    @Test
    fun `S028 RF-11 varias columnas sin asignar no son un problema`() {
        ColumnMapping(listOf(ColumnRole.IGNORE, ColumnRole.NAME, ColumnRole.IGNORE, ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.IGNORE))
            .problem.shouldBeNull()
    }

    @Test
    fun `S028 RF-11 cambiar el papel de una columna no toca las demas`() {
        val changed = ColumnMapping.penzd(5).withRole(3, ColumnRole.IGNORE).withRole(4, ColumnRole.HEIGHT)

        changed.roles shouldBe listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.IGNORE, ColumnRole.HEIGHT)
        changed.indexOf(ColumnRole.HEIGHT) shouldBe 4
        ColumnMapping.penzd(5).roles[3] shouldBe ColumnRole.HEIGHT // el original no cambia
    }

    @Test
    fun `S028 RF-11 el papel de una columna que no esta es sin asignar`() {
        ColumnMapping.penzd(3).roleOf(9) shouldBe ColumnRole.IGNORE
        ColumnMapping.penzd(3).roleOf(1) shouldBe ColumnRole.FIRST
    }

    @Test
    fun `S028 RF-13 las alturas elipsoidales solo valen en geograficas y no en ED50`() {
        ImportCoordinates(EntryForm.PROJECT_CRS, HeightKind.PROJECT).isAllowed(ed50 = false) shouldBe true
        ImportCoordinates(EntryForm.PROJECT_CRS, HeightKind.ELLIPSOIDAL).isAllowed(ed50 = false) shouldBe false
        ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT).isAllowed(ed50 = false) shouldBe true
        ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.ELLIPSOIDAL).isAllowed(ed50 = false) shouldBe true
        ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.ELLIPSOIDAL).isAllowed(ed50 = true) shouldBe false
        ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT).isAllowed(ed50 = true) shouldBe true
    }

    @Test
    fun `S028 RF-28 los recuentos suman las filas y los repetidos son los saltados mas los renombrados`() {
        val counts = ImportCounts(rows = 20, valid = 14, skipped = 2, renamed = 1, errors = 4)

        counts.repeated shouldBe 3
        counts.isConsistent shouldBe true
        ImportCounts(rows = 20, valid = 14, skipped = 2, renamed = 1, errors = 3).isConsistent shouldBe false
        ImportCounts().isConsistent shouldBe true
    }

    @Test
    fun `S028 RF-28 un renombrado entra, por eso cuenta como valido`() {
        ImportCounts(rows = 5, valid = 4, skipped = 0, renamed = 2, errors = 1).isConsistent shouldBe true
        ImportCounts(rows = 5, valid = 1, skipped = 0, renamed = 2, errors = 4).isConsistent shouldBe false // más renombrados que válidos
    }

    @Test
    fun `S028 RF-23 cada fila con error dice su linea y su motivo`() {
        val error = ImportRowError(line = 12, reason = ImportErrorReason.NOT_A_NUMBER)

        error.line shouldBe 12
        error.reason shouldBe ImportErrorReason.NOT_A_NUMBER
        ImportErrorReason.entries.map { it.name }.toSet() shouldBe setOf(
            "MISSING_NAME",
            "NOT_A_NUMBER",
            "MISSING_COLUMNS",
            "OUTSIDE_CRS_AREA",
            "OUTSIDE_ED50_GRID",
            "OUTSIDE_GEOID",
        )
    }

    @Test
    fun `S028 RF-21 una fila de la vista previa lleva lo leido y su estado`() {
        val valid =
            ImportPreviewRow(
                line = 2,
                name = "1",
                first = "440297,988",
                second = "4474244,001",
                height = "646,377",
                description = "Esquina",
            )
        val renamed = valid.copy(status = ImportRowStatus.REPEATED_RENAMED, renamedTo = "2")
        val failed = valid.copy(status = ImportRowStatus.ERROR, reason = ImportErrorReason.NOT_A_NUMBER)

        valid.status shouldBe ImportRowStatus.VALID
        valid.reason.shouldBeNull()
        renamed.renamedTo shouldBe "2"
        failed.reason shouldBe ImportErrorReason.NOT_A_NUMBER
        ImportRowStatus.entries.size shouldBe 4
    }
}
