package es.topophone.core.formats.codes

import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeLibrary
import es.topophone.core.model.codes.SurveyCode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El CSV de una biblioteca de códigos (S023/RF-17, RF-18, D8). */
class CodeLibraryCsvWriterTest {

    private fun code(
        code: String,
        description: String = "",
        geometry: CodeGeometry = CodeGeometry.POINT,
        color: Int = 0x1E88E5,
        vararg attributes: CodeAttribute,
    ) = SurveyCode("id-$code", code, description, geometry, color, attributes.toList())

    private fun csv(vararg codes: SurveyCode): String {
        val out = StringBuilder()
        CodeLibraryCsvWriter.write(CodeLibrary("l", "Mi biblioteca", false, codes.toList()), out)
        return out.toString()
    }

    @Test
    fun `S023 RF-17 una biblioteca sin atributos escribe cuatro columnas, con CRLF y los tipos en ingles`() {
        csv(
            code("FAR", "Farola", CodeGeometry.POINT, 0x1E88E5),
            code("BOR", "Bordillo", CodeGeometry.LINE, 0xE64A19),
            code("EDI", "", CodeGeometry.POLYGON, 0x00000F),
        ) shouldBe "Code,Description,Type,Color\r\n" +
            "BOR,Bordillo,Line,#E64A19\r\n" +
            "EDI,,Polygon,#00000F\r\n" +
            "FAR,Farola,Point,#1E88E5\r\n"
    }

    @Test
    fun `S023 RF-17 las columnas de atributos llegan hasta el maximo de la biblioteca`() {
        val text = csv(
            code(
                "ARQ",
                "Arqueta",
                attributes = arrayOf(
                    CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón")),
                    CodeAttribute("Diámetro", AttributeType.NUMBER, "0.25"),
                ),
            ),
            code("FAR", "Farola"),
        )

        text shouldBe "Code,Description,Type,Color," +
            "Attribute_1_Name,Attribute_1_Type,Attribute_1_Default,Attribute_1_Options," +
            "Attribute_2_Name,Attribute_2_Type,Attribute_2_Default,Attribute_2_Options\r\n" +
            "ARQ,Arqueta,Point,#1E88E5,Material,List,PVC,PVC|Hormigón,Diámetro,Number,0.25,\r\n" +
            "FAR,Farola,Point,#1E88E5,,,,,,,,\r\n"
    }

    @Test
    fun `S023 RF-17 los textos con coma, comillas o saltos van entre comillas como en S012`() {
        csv(code("A", "Tapa, \"grande\"\nde hierro")) shouldBe
            "Code,Description,Type,Color\r\nA,\"Tapa, \"\"grande\"\"\nde hierro\",Point,#1E88E5\r\n"
    }

    @Test
    fun `S023 RF-17 el valor por defecto de un numero se escribe con punto y sin redondear`() {
        val text = csv(code("A", attributes = arrayOf(CodeAttribute("N", AttributeType.NUMBER, "0.1234567890123"))))

        text.lines()[1] shouldBe "A,,Point,#1E88E5,N,Number,0.1234567890123,"
    }

    @Test
    fun `S023 RF-18 una opcion con barra vertical o invertida se escapa`() {
        CodeCsvFormat.encodeOptions(listOf("a|b", "c\\d", "e")) shouldBe "a\\|b|c\\\\d|e"
        CodeCsvFormat.decodeOptions("a\\|b|c\\\\d|e") shouldBe listOf("a|b", "c\\d", "e")
        CodeCsvFormat.decodeOptions("") shouldBe emptyList()
        CodeCsvFormat.decodeOptions("solo") shouldBe listOf("solo")
    }

    @Test
    fun `S023 RF-17 el nombre propuesto lleva el nombre de la biblioteca y la fecha`() {
        CodeLibraryCsvWriter.fileName(
            "Mi biblioteca / 1",
            java.time.Instant.parse("2026-11-03T10:15:00Z"),
            java.time.ZoneOffset.UTC,
        ) shouldBe
            "Mi biblioteca _ 1_codes_20261103-1015.csv"
    }
}
