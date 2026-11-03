package es.topophone.core.formats.codes

import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.TextDecoding
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeError
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodePalette
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.jupiter.api.Test

/** La lectura del CSV de una biblioteca (S023/RF-12, RF-13, RF-15, D13). */
class CodeLibraryCsvReaderTest {

    private fun fixture(name: String): ByteArray {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        return File(root, "fixtures/codigos/$name").readBytes()
    }

    private fun parse(
        bytes: ByteArray,
        encoding: ImportEncoding = ImportEncoding.UTF8,
        separator: ImportSeparator = ImportSeparator.COMMA,
        decimal: ImportDecimal = ImportDecimal.POINT,
    ): CodeCsvResult {
        val rows = DelimitedReader.rows(TextDecoding.reader(ByteArrayInputStream(bytes), encoding), separator)
        var n = 0
        return CodeLibraryCsvReader.parse(rows, decimal) { "id${n++}" }
    }

    private fun ok(result: CodeCsvResult): CodeCsvParse = result.shouldBeInstanceOf<CodeCsvResult.Parsed>().parse

    @Test
    fun `S023 RF-13 se leen codigo, descripcion, geometria, color y atributos por su cabecera`() {
        val parse = ok(parse(fixture("completa.csv")))

        parse.invalid shouldBe emptyList()
        parse.codes.map { it.code.code } shouldBe listOf("ARQ", "BOR", "EDI", "FAR")
        val arq = parse.codes[0].code
        arq.description shouldBe "Arqueta"
        arq.geometry shouldBe CodeGeometry.POINT
        arq.color shouldBe 0x1E88E5
        arq.attributes shouldBe listOf(
            CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón")),
            CodeAttribute("Diámetro", AttributeType.NUMBER, "0.25"),
        )
        parse.codes[1].code.geometry shouldBe CodeGeometry.LINE
        parse.codes[1].code.attributes shouldBe emptyList()
        parse.codes[2].code.description shouldBe "Edificio, con \"patio\""
        parse.codes[2].code.attributes shouldBe listOf(
            CodeAttribute("Plantas", AttributeType.INTEGER, "2"),
            CodeAttribute("Uso", AttributeType.TEXT),
        )
    }

    @Test
    fun `S023 RF-13 sin color toma el de su geometria y la linea de cada codigo es la del fichero`() {
        val parse = ok(parse(fixture("completa.csv")))

        parse.codes[3].code.color shouldBe CodePalette.defaultFor(CodeGeometry.POINT)
        parse.codes.map { it.line } shouldBe listOf(2, 3, 4, 5)
    }

    @Test
    fun `S023 RF-12 un fichero en Windows-1252 con punto y coma y coma decimal se lee entero`() {
        val parse = ok(
            parse(
                fixture("windows1252-puntoycoma-coma.csv"),
                ImportEncoding.WINDOWS_1252,
                ImportSeparator.SEMICOLON,
                ImportDecimal.COMMA,
            ),
        )

        parse.invalid shouldBe emptyList()
        parse.codes[0].code.code shouldBe "ÁRB"
        parse.codes[0].code.description shouldBe "Árbol con ñ"
        parse.codes[0].code.color shouldBe 0x00796B
        parse.codes[0].code.attributes shouldBe listOf(CodeAttribute("Diámetro", AttributeType.NUMBER, "1.5"))
        parse.codes[1].code.attributes shouldBe listOf(CodeAttribute("Estado", AttributeType.LIST, "Bueno", listOf("Bueno", "Malo")))
    }

    @Test
    fun `S023 RF-13 sin la columna Code no se importa nada`() {
        parse(fixture("sin-code.csv")) shouldBe CodeCsvResult.MissingCode
    }

    @Test
    fun `S023 RF-13 la cabecera no distingue mayusculas ni espacios y se ignoran las columnas desconocidas`() {
        val text = " CODE , type ,Notas,description\nA,line,x,Hola\n"

        val parse = ok(parse(text.toByteArray()))

        parse.codes.single().code.let {
            it.code shouldBe "A"
            it.geometry shouldBe CodeGeometry.LINE
            it.description shouldBe "Hola"
        }
    }

    @Test
    fun `S023 RF-13 sin la columna Type los codigos son puntos`() {
        ok(parse("Code\nA\n".toByteArray())).codes.single().code.geometry shouldBe CodeGeometry.POINT
    }

    @Test
    fun `S023 RF-13 los atributos pueden saltarse numeros y su tipo se deduce si falta`() {
        val text = "Code,Attribute_3_Name,Attribute_3_Options,Attribute_1_Name\nA,Uso,Casa|Local,Edad\n"

        ok(parse(text.toByteArray())).codes.single().code.attributes shouldBe listOf(
            CodeAttribute("Edad", AttributeType.TEXT),
            CodeAttribute("Uso", AttributeType.LIST, null, listOf("Casa", "Local")),
        )
    }

    @Test
    fun `S023 RF-15 cada fila invalida se lista con su linea y su motivo y las demas entran`() {
        val parse = ok(parse(fixture("filas-invalidas.csv")))

        parse.codes.map { it.code.code } shouldBe listOf("OK1", "OK2", "ok1")
        parse.invalid.map { it.line to it.problem } shouldBe listOf(
            3 to RowProblem.Code(CodeError.Empty),
            4 to RowProblem.Code(CodeError.TooLong),
            5 to RowProblem.UnknownType("Arco"),
            6 to RowProblem.UnknownColor("rojo"),
            7 to RowProblem.UnknownAttributeType(1, "Fecha"),
            8 to RowProblem.Attribute(1, CodeError.AttributeName(0)),
            9 to RowProblem.Attribute(1, CodeError.DefaultValue(0)),
            10 to RowProblem.Attribute(1, CodeError.ListOptions(0)),
            11 to RowProblem.Attribute(1, CodeError.DefaultValue(0)),
        )
    }

    @Test
    fun `S023 RF-15 un fichero sin filas de datos no tiene codigos ni filas invalidas`() {
        val parse = ok(parse("Code,Type\n".toByteArray()))

        parse.codes shouldBe emptyList()
        parse.invalid shouldBe emptyList()
    }

    @Test
    fun `S023 RF-12 un fichero vacio no tiene codigo`() {
        parse(ByteArray(0)) shouldBe CodeCsvResult.MissingCode
    }
}
