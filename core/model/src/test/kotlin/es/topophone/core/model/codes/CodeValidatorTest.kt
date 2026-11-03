package es.topophone.core.model.codes

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Las reglas de un código y de sus atributos (S023/RF-5 a RF-7, RF-9). */
class CodeValidatorTest {

    private fun code(text: String) = SurveyCode("id-$text", text, "", CodeGeometry.POINT, 0x1E88E5)

    private fun check(text: String, others: List<SurveyCode> = emptyList(), attrs: List<CodeAttribute> = emptyList()) =
        CodeValidator.validate(text, attrs, others)

    @Test
    fun `S023 RF-7 un codigo vacio o solo con espacios no vale`() {
        check("") shouldBe CodeError.Empty
        check("   ") shouldBe CodeError.Empty
    }

    @Test
    fun `S023 RF-7 un codigo de mas de 32 caracteres no vale y uno de 32 si`() {
        check("A".repeat(33)) shouldBe CodeError.TooLong
        check("A".repeat(32)) shouldBe null
        check("  " + "A".repeat(32) + "  ") shouldBe null
    }

    @Test
    fun `S023 RF-7 un codigo con salto de linea no vale`() {
        check("AR\nQ") shouldBe CodeError.NewLine
        check("AR\rQ") shouldBe CodeError.NewLine
    }

    @Test
    fun `S023 RF-6 un codigo repetido con otra mayuscula o acento no vale y se indica el que choca`() {
        val others = listOf(code("Árbol"), code("ARQ"))

        check("arbol", others) shouldBe CodeError.Duplicate("Árbol")
        check(" arq ", others) shouldBe CodeError.Duplicate("ARQ")
        check("FAR", others) shouldBe null
    }

    @Test
    fun `S023 RF-9 hasta 100 atributos y no mas`() {
        val ok = (1..100).map { CodeAttribute("A$it", AttributeType.TEXT) }

        check("X", attrs = ok) shouldBe null
        check("X", attrs = ok + CodeAttribute("A101", AttributeType.TEXT)) shouldBe CodeError.TooManyAttributes
    }

    @Test
    fun `S023 RF-9 el nombre de un atributo no esta vacio ni se repite en el codigo`() {
        check("X", attrs = listOf(CodeAttribute(" ", AttributeType.TEXT))) shouldBe CodeError.AttributeName(0)
        check(
            "X",
            attrs = listOf(CodeAttribute("Diámetro", AttributeType.NUMBER), CodeAttribute("diametro", AttributeType.TEXT)),
        ) shouldBe CodeError.AttributeName(1)
    }

    @Test
    fun `S023 RF-9 una lista tiene de 1 a 50 opciones distintas`() {
        fun list(options: List<String>) = listOf(CodeAttribute("L", AttributeType.LIST, null, options))

        check("X", attrs = list(emptyList())) shouldBe CodeError.ListOptions(0)
        check("X", attrs = list((1..51).map { "o$it" })) shouldBe CodeError.ListOptions(0)
        check("X", attrs = list((1..50).map { "o$it" })) shouldBe null
        check("X", attrs = list(listOf("PVC", "pvc"))) shouldBe CodeError.ListOptions(0)
        check("X", attrs = list(listOf("PVC", " "))) shouldBe CodeError.ListOptions(0)
    }

    @Test
    fun `S023 RF-9 el valor por defecto tiene que valer para su tipo`() {
        fun one(type: AttributeType, default: String?, options: List<String> = emptyList()) =
            check("X", attrs = listOf(CodeAttribute("A", type, default, options)))

        one(AttributeType.INTEGER, "12") shouldBe null
        one(AttributeType.INTEGER, "-3") shouldBe null
        one(AttributeType.INTEGER, "1.5") shouldBe CodeError.DefaultValue(0)
        one(AttributeType.INTEGER, "abc") shouldBe CodeError.DefaultValue(0)
        one(AttributeType.NUMBER, "1.5") shouldBe null
        one(AttributeType.NUMBER, "1,5") shouldBe CodeError.DefaultValue(0)
        one(AttributeType.NUMBER, "1e3") shouldBe CodeError.DefaultValue(0)
        one(AttributeType.NUMBER, "9".repeat(400)) shouldBe CodeError.DefaultValue(0)
        one(AttributeType.TEXT, "lo que sea") shouldBe null
        one(AttributeType.LIST, "PVC", listOf("PVC", "Acero")) shouldBe null
        one(AttributeType.LIST, "Cobre", listOf("PVC", "Acero")) shouldBe CodeError.DefaultValue(0)
        one(AttributeType.NUMBER, null) shouldBe null
    }

    @Test
    fun `S023 RF-9 el valor por defecto se guarda en texto canonico sin redondear`() {
        AttributeValues.canonical(AttributeType.INTEGER, " 007 ", ',') shouldBe "7"
        AttributeValues.canonical(AttributeType.NUMBER, "1,50", ',') shouldBe "1.5"
        AttributeValues.canonical(AttributeType.NUMBER, "1.50", '.') shouldBe "1.5"
        AttributeValues.canonical(AttributeType.NUMBER, "0.1", '.') shouldBe "0.1"
        AttributeValues.canonical(AttributeType.NUMBER, "100.0", '.') shouldBe "100"
        AttributeValues.canonical(AttributeType.NUMBER, "12345678901234567890", '.') shouldBe "12345678901234567000"
        AttributeValues.canonical(AttributeType.NUMBER, "0.0000001", '.') shouldBe "0.0000001"
        AttributeValues.canonical(AttributeType.NUMBER, "x", '.') shouldBe null
        AttributeValues.canonical(AttributeType.TEXT, "  hola ", '.') shouldBe "hola"
        AttributeValues.canonical(AttributeType.LIST, "PVC", '.') shouldBe "PVC"
    }

    @Test
    fun `S023 RF-5 la validacion de formato de una fila importada no mira los duplicados`() {
        CodeValidator.validateFormat("ARQ") shouldBe null
        CodeValidator.validateFormat("") shouldBe CodeError.Empty
    }
}
