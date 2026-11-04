package es.topophone.core.model.codes

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los tipos del código que lleva un punto (S024/RF-10, RF-17). */
class PointCodeModelTest {

    private fun plain() = Point(
        id = "p1",
        projectId = "proj",
        name = "1",
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 699.423), 440290.45805, 4474257.38189, 648.19),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 0,
        deletedAtMs = null,
    )

    @Test
    fun `S024 RF-17 hay seis simbolos y un codigo sin simbolo usa el circulo`() {
        CodeSymbol.entries.map { it.name } shouldBe listOf("CIRCLE", "SQUARE", "TRIANGLE", "DIAMOND", "CROSS", "STAR")
        SurveyCode("c", "ARB", "", CodeGeometry.POINT, 0x1E88E5).symbol shouldBe CodeSymbol.CIRCLE
    }

    @Test
    fun `S024 RF-10 el punto guarda codigo, descripcion, color, simbolo y atributos por valor`() {
        val code = PointCode(
            code = "ARQ",
            description = "Arqueta",
            color = 0x1E88E5,
            symbol = CodeSymbol.SQUARE,
            attributes = listOf(PointAttribute("Diametro", AttributeType.NUMBER, "0.123456789012345")),
        )
        val point = plain().copy(code = code)

        point.code?.attributes?.single()?.value shouldBe "0.123456789012345"
        point.summary().code shouldBe "ARQ"
        point.summary().codeColor shouldBe 0x1E88E5
        point.summary().codeSymbol shouldBe CodeSymbol.SQUARE
    }

    @Test
    fun `S024 RF-17 un punto sin codigo no lleva nada en el resumen`() {
        val summary = plain().summary()
        summary.code shouldBe null
        summary.codeColor shouldBe null
        summary.codeSymbol shouldBe null
    }
}
