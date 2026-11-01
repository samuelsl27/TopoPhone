package es.topophone.core.model.alignment.section

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los bloques binarios de las secciones y de la sección tipo (S050/RF-8, plan: modelo de datos). */
class SectionCodecTest {
    private val points = listOf(
        SectionPoint("BI", -3.500000000000001, -0.07000000000000002),
        SectionPoint("EJE", 0.0, 0.0),
        SectionPoint("Bordillo ñ", 3.5, -0.0699999999),
    )

    private val template = SectionTemplate(
        1000.5,
        1380.5,
        "BD",
        listOf(TemplateSegment("BI", 3.5, 0.02, true), TemplateSegment("CI", 1.0, -0.5, false)),
        listOf(TemplateSegment("BD", 3.5, 0.02, true)),
    )

    @Test
    fun `S050 RF-8 los puntos de una seccion vuelven bit a bit sin redondear`() {
        val back = SectionCodec.decodePoints(SectionCodec.encodePoints(points))

        back shouldBe points
        back[0].offsetM.toRawBits() shouldBe (-3.500000000000001).toRawBits()
    }

    @Test
    fun `S050 RF-8 la seccion tipo vuelve igual con sus dos lados`() {
        SectionCodec.decodeTemplate(SectionCodec.encodeTemplate(template)) shouldBe template
    }

    @Test
    fun `S050 RF-8 una seccion sin puntos y una plantilla sin tramos son validas`() {
        SectionCodec.decodePoints(SectionCodec.encodePoints(emptyList())) shouldBe emptyList()
        val bare = template.copy(left = emptyList(), right = emptyList())
        SectionCodec.decodeTemplate(SectionCodec.encodeTemplate(bare)) shouldBe bare
    }

    @Test
    fun `S050 RF-8 un bloque cortado de otro formato o con bytes de mas se rechaza`() {
        val bytes = SectionCodec.encodePoints(points)

        shouldThrow<IllegalArgumentException> { SectionCodec.decodePoints(bytes.copyOf(bytes.size - 3)) }
        shouldThrow<IllegalArgumentException> { SectionCodec.decodePoints(byteArrayOf(9) + bytes.copyOfRange(1, bytes.size)) }
        shouldThrow<IllegalArgumentException> { SectionCodec.decodePoints(bytes + byteArrayOf(0)) }
        shouldThrow<IllegalArgumentException> { SectionCodec.decodePoints(ByteArray(0)) }
        shouldThrow<IllegalArgumentException> { SectionCodec.decodeTemplate(SectionCodec.encodePoints(points)) }
    }
}
