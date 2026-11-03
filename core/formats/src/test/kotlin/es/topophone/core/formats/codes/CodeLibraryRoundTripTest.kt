package es.topophone.core.formats.codes

import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.TextDecoding
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.CodeSearch
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Test

/** Exportar una biblioteca y volver a importarla da lo mismo, y 2.000 códigos se leen deprisa (S023/RF-18 y requisito de rendimiento). */
class CodeLibraryRoundTripTest {

    private fun roundTrip(library: CodeLibrary): CodeCsvParse {
        val out = StringBuilder()
        CodeLibraryCsvWriter.write(library, out)
        val bytes = out.toString().toByteArray(Charsets.UTF_8)
        val rows = DelimitedReader.rows(TextDecoding.reader(ByteArrayInputStream(bytes), ImportEncoding.UTF8), ImportSeparator.COMMA)
        return CodeLibraryCsvReader.parse(rows, ImportDecimal.POINT).shouldBeInstanceOf<CodeCsvResult.Parsed>().parse
    }

    private val tricky = listOf(
        SurveyCode(
            "1",
            "ARQ",
            "Arqueta, \"cuadrada\"\ncon tapa",
            CodeGeometry.POINT,
            0x1E88E5,
            listOf(
                CodeAttribute("Material", AttributeType.LIST, "a|b", listOf("a|b", "c\\d", "Hormigón", "x,y", "\"q\"")),
                CodeAttribute("Diámetro", AttributeType.NUMBER, "0.1234567890123"),
                CodeAttribute("Plantas", AttributeType.INTEGER, "-3"),
                CodeAttribute("Nota", AttributeType.TEXT, "con, coma"),
                CodeAttribute("Sin valor", AttributeType.NUMBER),
            ),
        ),
        SurveyCode("2", "ÁRBOL", "", CodeGeometry.LINE, 0x00000F),
        SurveyCode("3", "EDI", "Edificio", CodeGeometry.POLYGON, 0xFFFFFF, listOf(CodeAttribute("Uso", AttributeType.TEXT))),
    )

    @Test
    fun `S023 RF-18 al exportar y volver a importar quedan los mismos codigos, geometrias, colores y atributos`() {
        val parse = roundTrip(CodeLibrary("l", "Prueba", false, tricky))

        parse.invalid shouldBe emptyList()
        parse.codes.map { it.code.copy(id = "") } shouldBe CodeSearch.index(tricky).filter("").map { it.copy(id = "") }
    }

    @Test
    fun `S023 RF-18 los valores por defecto numericos conservan todos sus decimales`() {
        val value = "0.30000000000000004"
        val library =
            CodeLibrary(
                "l",
                "N",
                false,
                listOf(SurveyCode("1", "A", "", CodeGeometry.POINT, 1, listOf(CodeAttribute("N", AttributeType.NUMBER, value)))),
            )

        roundTrip(library).codes.single().code.attributes.single().defaultValue shouldBe value
        value.toDouble() shouldBe 0.1 + 0.2
    }

    @Test
    fun `S023 RF-18 una biblioteca de 2000 codigos con atributos se escribe y se lee en menos de 3 s`() {
        val big = (1..2000).map {
            SurveyCode(
                "id$it",
                "C%04d".format(it),
                "Descripción $it",
                CodeGeometry.entries[it % 3],
                it * 1000 % 0x1000000,
                listOf(
                    CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón", "Acero")),
                    CodeAttribute("Diámetro", AttributeType.NUMBER, "0.25"),
                ),
            )
        }

        lateinit var parse: CodeCsvParse
        val millis = measureTimeMillis { parse = roundTrip(CodeLibrary("l", "Grande", false, big)) }

        parse.codes.size shouldBe 2000
        parse.invalid.size shouldBe 0
        (millis < 3000) shouldBe true
    }
}
