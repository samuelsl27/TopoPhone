package es.topophone.core.formats.landxml

import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.model.project.LinearUnit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test

/** La lista de alineaciones de un LandXML (S049/RF-2, RF-3). */
class LandXmlAlignmentScanTest {
    private fun scan(name: String) = LandXmlAlignmentReader.scan(AlignmentFixtures.stream(name))

    @Test
    fun `S049 RF-2 lista cada alineacion con su longitud su PK inicial y si trae rasante`() {
        val scan = scan("ejes.xml")

        scan.version shouldBe "1.2"
        scan.declaredUnit shouldBe LinearUnit.METRE
        scan.alignments.map { it.name } shouldBe listOf("Eje A", "Eje B", "Eje C")
        scan.alignments.map { it.length } shouldBe listOf(420.0, 380.0, 200.0)
        scan.alignments.map { it.startStation } shouldBe listOf(0.0, 1000.5, 0.0)
        scan.alignments.map { it.hasProfile } shouldBe listOf(true, true, false)
        scan.alignments.map { it.elementCount } shouldBe listOf(5, 8, 1)
        scan.alignments.map { it.index } shouldBe listOf(0, 1, 2)
    }

    @Test
    fun `S049 RF-2 en pies lo dice la unidad y las longitudes quedan en pies`() {
        val scan = scan("eje-a-pies.xml")

        scan.declaredUnit shouldBe LinearUnit.INTERNATIONAL_FOOT
        scan.alignments.single().length shouldBe (420.0 / 0.3048 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-2 se reconoce un eje con ecuaciones de PK y una rasante de acuerdos asimetricos`() {
        scan("eje-ecuacion-pk.xml").alignments.single().hasStationEquation shouldBe true
        scan("eje-acuerdo-asimetrico.xml").alignments.single().hasProfile shouldBe true
        scan("ejes.xml").alignments.none { it.hasStationEquation } shouldBe true
    }

    @Test
    fun `S049 RF-3 un LandXML sin alineaciones da una lista vacia`() {
        scan("sin-alineaciones.xml").alignments shouldBe emptyList()
    }

    @Test
    fun `S049 RF-3 un fichero que no es XML o esta mal formado se rechaza`() {
        shouldThrow<XmlFormatException> { scan("mal-formado.xml") }
        shouldThrow<XmlFormatException> { scan("no-es-landxml.txt") }
    }

    @Test
    fun `S049 RF-3 un XML que no es un LandXML o de otra version se rechaza`() {
        shouldThrow<LandXmlException> { LandXmlAlignmentReader.scan(ByteArrayInputStream("<html><body/></html>".toByteArray())) }
            .problem shouldBe LandXmlProblem.NOT_LANDXML
        shouldThrow<LandXmlException> {
            LandXmlAlignmentReader.scan(ByteArrayInputStream("""<LandXML version="2.0"/>""".toByteArray()))
        }.problem shouldBe LandXmlProblem.UNSUPPORTED_VERSION
    }

    @Test
    fun `S049 RF-3 el limite es de 100 MB`() {
        LandXmlAlignmentReader.MAX_BYTES shouldBe 104_857_600L
    }

    @Test
    fun `S049 RF-2 un LandXML de superficies sin alineaciones sigue leyendose como superficies`() {
        val tin = SurfaceFixtures.stream("dos-superficies.xml")

        LandXmlAlignmentReader.scan(tin).alignments shouldBe emptyList()
        LandXmlReader.scan(SurfaceFixtures.stream("dos-superficies.xml")).surfaces.isNotEmpty() shouldBe true
    }
}
