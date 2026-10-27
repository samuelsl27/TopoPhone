package es.topophone.core.model.alignment

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los bloques binarios de la planta y de la rasante de un eje (S049/RF-13, plan: modelo de datos). */
class AlignmentCodecTest {

    private fun elements() = listOf(
        AlignmentElement.line(1000.5, 120.0, 440000.123456789, 4474000.000000001, 1.0471975511965976),
        AlignmentElement.clothoid(1120.5, 60.0, 440103.9230484541, 4474060.0, 1.0471975511965976, 0.0, -1.0 / 150),
        AlignmentElement.arc(1180.5, 100.0, 440157.67140411126, 4474086.426005247, 1.2, -1.0 / 150),
    )

    private fun profile() = VerticalProfile(
        listOf(
            VerticalVertex(1000.5, 50.0),
            VerticalVertex(1200.0, 52.0000000001, VerticalCurve.Parabola(53.2)),
            VerticalVertex(1300.0, 51.0, VerticalCurve.Circular(2000.0, 43.3)),
            VerticalVertex(1380.5, 49.0),
        ),
    )

    @Test
    fun `S049 RF-13 la planta vuelve bit a bit sin redondear`() {
        val back = AlignmentCodec.decodeGeometry(AlignmentCodec.encodeGeometry(elements()))

        back shouldBe elements()
        back[0].startNorthing.toRawBits() shouldBe 4474000.000000001.toRawBits()
        back[1].kind shouldBe ElementKind.CLOTHOID
    }

    @Test
    fun `S049 RF-13 la rasante vuelve igual con sus tres clases de vertice`() {
        AlignmentCodec.decodeProfile(AlignmentCodec.encodeProfile(profile())) shouldBe profile()
    }

    @Test
    fun `S049 RF-13 un bloque cortado o de otro formato se rechaza`() {
        val bytes = AlignmentCodec.encodeGeometry(elements())

        shouldThrow<IllegalArgumentException> { AlignmentCodec.decodeGeometry(bytes.copyOf(bytes.size - 3)) }
        shouldThrow<IllegalArgumentException> { AlignmentCodec.decodeGeometry(byteArrayOf(99, 0)) }
        shouldThrow<IllegalArgumentException> { AlignmentCodec.decodeProfile(AlignmentCodec.encodeProfile(profile()) + byteArrayOf(1)) }
    }

    @Test
    fun `S049 RF-4 la longitud del eje es la suma de sus tramos y acaba donde acaba el ultimo`() {
        val alignment = Alignment(
            id = "a",
            projectId = "p",
            name = "Eje A",
            fileName = "ejes.xml",
            importedAtMs = 1L,
            source = AlignmentSource.LANDXML,
            elements = elements(),
            profile = null,
            visible = true,
            extent = AlignmentExtent(0.0, 1.0, 0.0, 1.0),
        )

        alignment.startChainage shouldBe 1000.5
        alignment.lengthM shouldBe 280.0
        alignment.endChainage shouldBe 1280.5
    }

    @Test
    fun `S049 RF-4 un tramo recto no puede tener curvatura ni un arco curvaturas distintas`() {
        shouldThrow<IllegalArgumentException> { AlignmentElement(ElementKind.LINE, 0.0, 10.0, 0.0, 0.0, 0.0, 0.01, 0.01) }
        shouldThrow<IllegalArgumentException> { AlignmentElement(ElementKind.ARC, 0.0, 10.0, 0.0, 0.0, 0.0, 0.01, 0.02) }
        shouldThrow<IllegalArgumentException> { AlignmentElement(ElementKind.LINE, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
    }
}
