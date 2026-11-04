package es.topophone.core.model.codes

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El formulario de atributos del recolector (S024/RF-8, RF-9). */
class AttributeFormTest {

    private val attributes = listOf(
        CodeAttribute("Nota", AttributeType.TEXT, "sin dato"),
        CodeAttribute("Unidades", AttributeType.INTEGER, "3"),
        CodeAttribute("Diametro", AttributeType.NUMBER, "0.25"),
        CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigon")),
        CodeAttribute("Libre", AttributeType.TEXT),
    )

    @Test
    fun `S024 RF-8 el formulario nace con el valor por defecto y el numero con el signo decimal de la interfaz`() {
        AttributeForm.initial(attributes, '.') shouldBe listOf("sin dato", "3", "0.25", "PVC", "")
        AttributeForm.initial(attributes, ',') shouldBe listOf("sin dato", "3", "0,25", "PVC", "")
    }

    @Test
    fun `S024 RF-8 los valores validos se guardan en texto canonico con su nombre y su tipo`() {
        val result = AttributeForm.validate(attributes, listOf(" nota ", "7", "1,5000", "Hormigon", ""), ',')

        result.isValid shouldBe true
        result.values shouldBe listOf(
            PointAttribute("Nota", AttributeType.TEXT, "nota"),
            PointAttribute("Unidades", AttributeType.INTEGER, "7"),
            PointAttribute("Diametro", AttributeType.NUMBER, "1.5"),
            PointAttribute("Material", AttributeType.LIST, "Hormigon"),
            PointAttribute("Libre", AttributeType.TEXT, ""),
        )
    }

    @Test
    fun `S024 RF-9 un valor no valido para su tipo se marca con su motivo`() {
        val result = AttributeForm.validate(attributes, listOf("x", "3,5", "abc", "Acero", ""), ',')

        result.isValid shouldBe false
        result.errors shouldBe listOf(
            null,
            AttributeError.NOT_INTEGER,
            AttributeError.NOT_NUMBER,
            AttributeError.NOT_IN_OPTIONS,
            null,
        )
    }

    @Test
    fun `S024 RF-9 un atributo vacio es valido en todos los tipos`() {
        val result = AttributeForm.validate(attributes, listOf("", "", "", "", ""), '.')

        result.isValid shouldBe true
        result.values.map { it.value } shouldBe listOf("", "", "", "", "")
    }

    @Test
    fun `S024 RF-10 el numero no se redondea`() {
        val result = AttributeForm.validate(
            listOf(CodeAttribute("Cota", AttributeType.NUMBER)),
            listOf("0.123456789012345"),
            '.',
        )

        result.values.single().value shouldBe "0.123456789012345"
    }
}
