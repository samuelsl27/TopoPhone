package es.topophone.core.formats.alignment

import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.TextDecoding
import es.topophone.core.formats.landxml.AlignmentFixtures
import es.topophone.core.model.point.DelimitedRow
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.StringReader
import org.junit.jupiter.api.Test

/** El CSV de rasante con las opciones de S028 (S049/RF-9). */
class ProfileCsvReaderTest {

    private fun rows(text: String, separator: ImportSeparator = ImportSeparator.SEMICOLON) =
        DelimitedReader.rows(StringReader(text), separator).toList()

    @Test
    fun `S049 RF-9 el CSV de ejemplo se lee con separador punto y coma y coma decimal`() {
        val text = TextDecoding.decode(AlignmentFixtures.bytes("rasante-b.csv"), ImportEncoding.UTF8)
        val separator = DelimitedReader.detectSeparator(text)
        val all = rows(text, separator)

        separator shouldBe ImportSeparator.SEMICOLON
        ProfileCsvReader.hasHeader(all.first().cells) shouldBe true
        val decimal = ProfileCsvReader.detectDecimal(all.drop(1), separator)
        decimal shouldBe ImportDecimal.COMMA
        val columns = ProfileCsvReader.suggest(all.first().cells, hasHeader = true)
        columns.roles shouldBe listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION, ProfileRole.KV)

        val result = ProfileCsvReader.parse(all.drop(1).asSequence(), columns, decimal)

        result.errors shouldBe emptyList()
        result.rows.map { it.chainage } shouldBe listOf(1000.5, 1200.0, 1380.5)
        result.rows.map { it.elevation } shouldBe listOf(50.0, 52.0, 49.0)
        result.rows.map { it.kv } shouldBe listOf(null, 2000.0, null)
    }

    @Test
    fun `S049 RF-9 las columnas se asignan por el nombre de la cabecera en cualquier orden`() {
        ProfileCsvReader.suggest(listOf("Cota", "P.K.", "Kv"), true).roles shouldBe
            listOf(ProfileRole.ELEVATION, ProfileRole.CHAINAGE, ProfileRole.KV)
        ProfileCsvReader.suggest(listOf("Estación", "Elevación"), true).roles shouldBe listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION)
    }

    @Test
    fun `S049 RF-9 sin cabecera o con nombres que no se reconocen se asigna PK cota y Kv por posicion`() {
        ProfileCsvReader.suggest(listOf("0", "10.5", "300"), hasHeader = false).roles shouldBe
            listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION, ProfileRole.KV)
        ProfileCsvReader.suggest(listOf("a", "b", "c", "d"), hasHeader = true).roles shouldBe
            listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION, ProfileRole.KV, ProfileRole.IGNORE)
        ProfileCsvReader.hasHeader(listOf("0", "10.5")) shouldBe false
    }

    @Test
    fun `S049 RF-9 el Kv es opcional y puede faltar la columna`() {
        val columns = ProfileColumns(listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION))

        val result = ProfileCsvReader.parse(rows("0;10.5\n100;12", ImportSeparator.SEMICOLON).asSequence(), columns, ImportDecimal.POINT)

        result.rows.map { it.kv }.all { it == null } shouldBe true
        result.rows.map { it.elevation } shouldBe listOf(10.5, 12.0)
    }

    @Test
    fun `S049 RF-9 una celda que no es un numero o falta da su linea y su papel`() {
        val columns = ProfileColumns(listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION, ProfileRole.KV))
        val text = "0;10\n50;abc\n;12\n100;11;x\n150;12;"

        val result = ProfileCsvReader.parse(rows(text).asSequence(), columns, ImportDecimal.POINT)

        result.errors shouldBe listOf(
            ProfileCsvError(2, ProfileRole.ELEVATION, ProfileCsvError.Reason.NOT_A_NUMBER),
            ProfileCsvError(3, ProfileRole.CHAINAGE, ProfileCsvError.Reason.MISSING),
            ProfileCsvError(4, ProfileRole.KV, ProfileCsvError.Reason.NOT_A_NUMBER),
        )
        result.rows.map { it.line } shouldBe listOf(1, 5)
    }

    @Test
    fun `S049 RF-9 una asignacion sin PK o sin cota o con un papel repetido no vale`() {
        ProfileColumns(listOf(ProfileRole.CHAINAGE, ProfileRole.KV)).problem shouldBe ProfileMappingProblem.MISSING_ELEVATION
        ProfileColumns(listOf(ProfileRole.ELEVATION, ProfileRole.IGNORE)).problem shouldBe ProfileMappingProblem.MISSING_CHAINAGE
        ProfileColumns(listOf(ProfileRole.CHAINAGE, ProfileRole.CHAINAGE, ProfileRole.ELEVATION)).problem shouldBe
            ProfileMappingProblem.REPEATED_ROLE
        ProfileColumns(listOf(ProfileRole.CHAINAGE, ProfileRole.ELEVATION)).problem.shouldBeNull()
    }

    @Test
    fun `S049 RF-9 con coma de separador de columnas el decimal es el punto`() {
        val all = rows("0,10.5,300\n100,12.25,", ImportSeparator.COMMA)

        ProfileCsvReader.detectDecimal(all, ImportSeparator.COMMA) shouldBe ImportDecimal.POINT
    }

    @Test
    fun `S049 RF-9 el PK con coma decimal se detecta aunque la cota sea entera`() {
        val all = rows("1000,5;50\n1200,25;52", ImportSeparator.SEMICOLON)

        ProfileCsvReader.detectDecimal(all, ImportSeparator.SEMICOLON) shouldBe ImportDecimal.COMMA
        DelimitedRow(1, listOf("a")).cells.size shouldBe 1
    }
}
