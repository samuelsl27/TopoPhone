package es.topophone.core.model.codes

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Qué valores pasan al cambiar el código de un punto (S024/RF-14, RF-15). */
class AttributeCarryOverTest {

    private fun old(name: String, type: AttributeType, value: String) = PointAttribute(name, type, value)

    @Test
    fun `S024 RF-14 se conserva el valor del atributo con el mismo nombre y tipo y el resto toma el valor por defecto`() {
        val oldValues = listOf(old("Material", AttributeType.TEXT, "PVC"), old("Diametro", AttributeType.NUMBER, "0.3"))
        val target = listOf(
            CodeAttribute("diámetro", AttributeType.NUMBER, "0.1"),
            CodeAttribute("Estado", AttributeType.TEXT, "bueno"),
            CodeAttribute("Sin", AttributeType.INTEGER),
        )

        val result = AttributeCarryOver.apply(oldValues, target)

        result.values shouldBe listOf(
            PointAttribute("diámetro", AttributeType.NUMBER, "0.3"),
            PointAttribute("Estado", AttributeType.TEXT, "bueno"),
            PointAttribute("Sin", AttributeType.INTEGER, ""),
        )
    }

    @Test
    fun `S024 RF-15 los valores no vacios que se pierden se devuelven para avisar`() {
        val oldValues = listOf(
            old("Material", AttributeType.TEXT, "PVC"),
            old("Diametro", AttributeType.NUMBER, "0.3"),
            old("Nota", AttributeType.TEXT, ""),
        )
        val target = listOf(CodeAttribute("Diametro", AttributeType.INTEGER, "1"))

        val result = AttributeCarryOver.apply(oldValues, target)

        result.discarded.map { it.name } shouldBe listOf("Material", "Diametro")
        result.values.single().value shouldBe "1"
    }

    @Test
    fun `S024 RF-15 un valor de lista que no esta en las opciones del codigo nuevo se descarta`() {
        val oldValues = listOf(old("Material", AttributeType.LIST, "Acero"))
        val target = listOf(CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigon")))

        val result = AttributeCarryOver.apply(oldValues, target)

        result.values.single().value shouldBe "PVC"
        result.discarded.map { it.value } shouldBe listOf("Acero")
    }

    @Test
    fun `S024 RF-14 sin codigo nuevo se descartan todos los valores no vacios`() {
        val result = AttributeCarryOver.apply(listOf(old("A", AttributeType.TEXT, "x")), emptyList())

        result.values shouldBe emptyList()
        result.discarded.size shouldBe 1
    }
}
