package es.topophone.core.formats.section

import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.TextDecoding
import es.topophone.core.formats.landxml.AlignmentFixtures
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import io.kotest.matchers.shouldBe
import java.io.StringReader
import org.junit.jupiter.api.Test

/** El CSV de secciones con las opciones de S028 (S050/RF-4) y el de peralte (S050/RF-6). */
class SectionCsvReaderTest {
    private fun rows(text: String, separator: ImportSeparator = ImportSeparator.COMMA) =
        DelimitedReader.rows(StringReader(text), separator).toList()

    private val standard = SectionColumns(
        listOf(SectionRole.CHAINAGE, SectionRole.CODE, SectionRole.OFFSET, SectionRole.HEIGHT, SectionRole.KIND),
    )

    @Test
    fun `S050 RF-4 el CSV de ejemplo da cinco secciones de diseno y un perfil de terreno`() {
        val text = TextDecoding.decode(AlignmentFixtures.bytes("secciones/secciones-eje-c.csv"), ImportEncoding.UTF8)
        val separator = DelimitedReader.detectSeparator(text)
        val all = rows(text, separator)

        SectionCsvReader.hasHeader(all.first().cells) shouldBe true
        val decimal = SectionCsvReader.detectDecimal(all.drop(1), separator)
        decimal shouldBe ImportDecimal.POINT
        val columns = SectionCsvReader.suggest(all.first().cells, hasHeader = true)
        columns.roles shouldBe standard.roles

        val result = SectionCsvReader.parse(all.drop(1).asSequence(), columns, decimal)
        result.errors shouldBe emptyList()
        val sections = SectionCsvReader.toSections(result.rows, HeightKind.ABSOLUTE)

        sections.filter { it.kind == SectionKind.DESIGN }.map { it.chainage } shouldBe listOf(0.0, 100.0, 150.0, 190.0)
        sections.filter { it.kind == SectionKind.GROUND }.map { it.chainage } shouldBe listOf(50.0)
        val first = sections.first { it.kind == SectionKind.DESIGN && it.chainage == 0.0 }
        first.points.map { it.code } shouldBe listOf("CI", "BI", "EJE", "BD", "CD")
        first.points.map { it.offsetM } shouldBe listOf(-8.0, -6.0, 0.0, 6.0, 8.0)
        first.points.map { it.heightM } shouldBe listOf(99.8, 100.1, 100.3, 100.1, 99.8)
        first.heightKind shouldBe HeightKind.ABSOLUTE
    }

    @Test
    fun `S050 RF-4 las columnas se asignan por el nombre de la cabecera en cualquier orden`() {
        SectionCsvReader.suggest(listOf("Cota", "Desplazamiento", "P.K.", "Código", "Tipo"), true).roles shouldBe
            listOf(SectionRole.HEIGHT, SectionRole.OFFSET, SectionRole.CHAINAGE, SectionRole.CODE, SectionRole.KIND)
    }

    @Test
    fun `S050 RF-4 sin cabecera se asigna PK codigo desplazamiento cota y tipo por posicion`() {
        SectionCsvReader.suggest(listOf("0", "BD", "3.5", "100", "diseno"), false).roles shouldBe standard.roles
    }

    @Test
    fun `S050 RF-4 sin columna de codigo ni de tipo los puntos se llaman P1 P2 por desplazamiento y son de diseno`() {
        val columns = SectionColumns(listOf(SectionRole.CHAINAGE, SectionRole.OFFSET, SectionRole.HEIGHT))
        val result = SectionCsvReader.parse(rows("10,3,1.5\n10,-3,1.4\n10,0,1.6").asSequence(), columns, ImportDecimal.POINT)

        val s = SectionCsvReader.toSections(result.rows, HeightKind.RELATIVE).single()

        s.kind shouldBe SectionKind.DESIGN
        s.heightKind shouldBe HeightKind.RELATIVE
        s.points.map { it.code } shouldBe listOf("P1", "P2", "P3")
        s.points.map { it.offsetM } shouldBe listOf(-3.0, 0.0, 3.0)
        s.points.map { it.heightM } shouldBe listOf(1.4, 1.6, 1.5)
    }

    @Test
    fun `S050 RF-4 la coma decimal y el punto y coma se leen`() {
        val columns = SectionColumns(listOf(SectionRole.CHAINAGE, SectionRole.OFFSET, SectionRole.HEIGHT))
        val parsed = SectionCsvReader.parse(rows("1020,5;-3,5;0,25", ImportSeparator.SEMICOLON).asSequence(), columns, ImportDecimal.COMMA)

        parsed.rows.single().let {
            it.chainage shouldBe 1020.5
            it.offset shouldBe -3.5
            it.height shouldBe 0.25
        }
    }

    @Test
    fun `S050 RF-4 la columna de tipo admite diseno y terreno en espanol e ingles`() {
        val columns = SectionColumns(listOf(SectionRole.CHAINAGE, SectionRole.OFFSET, SectionRole.HEIGHT, SectionRole.KIND))
        val text = "1,0,1,Diseño\n2,0,1,TERRENO\n3,0,1,existing\n4,0,1,proposed\n5,0,1,"
        val result = SectionCsvReader.parse(rows(text).asSequence(), columns, ImportDecimal.POINT)

        result.errors shouldBe emptyList()
        result.rows.map { it.kind } shouldBe listOf(
            SectionKind.DESIGN,
            SectionKind.GROUND,
            SectionKind.GROUND,
            SectionKind.DESIGN,
            SectionKind.DESIGN,
        )
    }

    @Test
    fun `S050 RF-4 una celda que no es numero o un tipo desconocido da la linea y el papel`() {
        val columns = SectionColumns(listOf(SectionRole.CHAINAGE, SectionRole.OFFSET, SectionRole.HEIGHT, SectionRole.KIND))
        val result = SectionCsvReader.parse(
            sequenceOf(
                DelimitedRow(7, listOf("x", "0", "1", "")),
                DelimitedRow(8, listOf("2", "", "1", "")),
                DelimitedRow(9, listOf("3", "0", "1", "raro")),
            ),
            columns,
            ImportDecimal.POINT,
        )

        result.rows shouldBe emptyList()
        result.errors.map { Triple(it.line, it.role, it.reason) } shouldBe listOf(
            Triple(7, SectionRole.CHAINAGE, SectionCsvError.Reason.NOT_A_NUMBER),
            Triple(8, SectionRole.OFFSET, SectionCsvError.Reason.MISSING),
            Triple(9, SectionRole.KIND, SectionCsvError.Reason.UNKNOWN_KIND),
        )
    }

    @Test
    fun `S050 RF-4 sin PK desplazamiento o cota la asignacion no vale y un papel no se repite`() {
        SectionColumns(listOf(SectionRole.OFFSET, SectionRole.HEIGHT)).problem shouldBe SectionMappingProblem.MISSING_CHAINAGE
        SectionColumns(listOf(SectionRole.CHAINAGE, SectionRole.HEIGHT)).problem shouldBe SectionMappingProblem.MISSING_OFFSET
        SectionColumns(listOf(SectionRole.CHAINAGE, SectionRole.OFFSET)).problem shouldBe SectionMappingProblem.MISSING_HEIGHT
        SectionColumns(listOf(SectionRole.CHAINAGE, SectionRole.CHAINAGE, SectionRole.OFFSET, SectionRole.HEIGHT)).problem shouldBe
            SectionMappingProblem.REPEATED_ROLE
        standard.problem shouldBe null
    }

    @Test
    fun `S050 RF-6 el CSV de peralte da las pendientes de cada lado en porcentaje`() {
        val columns = SuperelevationCsvReader.suggest(listOf("PK", "Izquierda", "Derecha"), true)
        columns.roles shouldBe listOf(SuperelevationRole.CHAINAGE, SuperelevationRole.LEFT, SuperelevationRole.RIGHT)

        val result = SuperelevationCsvReader.parse(rows("0,2,2\n120,-6,6\n").asSequence(), columns, ImportDecimal.POINT)

        result.errors shouldBe emptyList()
        result.rows.map { Triple(it.chainage, it.leftSlope, it.rightSlope) } shouldBe
            listOf(Triple(0.0, 0.02, 0.02), Triple(120.0, -0.06, 0.06))
    }

    @Test
    fun `S050 RF-6 el peralte tambien se lee en fraccion y un error da la linea`() {
        val columns = SuperelevationColumns(listOf(SuperelevationRole.CHAINAGE, SuperelevationRole.LEFT, SuperelevationRole.RIGHT))
        val ok = SuperelevationCsvReader.parse(rows("0,0.02,0.02").asSequence(), columns, ImportDecimal.POINT, percent = false)
        val bad = SuperelevationCsvReader.parse(sequenceOf(DelimitedRow(4, listOf("0", "x", "2"))), columns, ImportDecimal.POINT)

        ok.rows.single().leftSlope shouldBe 0.02
        bad.errors.map { Triple(it.line, it.role, it.reason) } shouldBe
            listOf(Triple(4, SuperelevationRole.LEFT, SectionCsvError.Reason.NOT_A_NUMBER))
    }
}
