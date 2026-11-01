package es.topophone.core.formats.landxml

import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.project.LinearUnit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Las secciones transversales de un LandXML (S050/RF-5, plan A6, D8). */
class LandXmlCrossSectReaderTest {
    private fun scan(name: String = "secciones/secciones-eje-c.xml") = LandXmlCrossSectReader.scan(AlignmentFixtures.stream(name))

    private fun xml(body: String, units: String = """<Units><Metric linearUnit="meter"/></Units>""", version: String = "1.2") =
        """<?xml version="1.0"?><LandXML xmlns="http://www.landxml.org/schema/LandXML-1.2" version="$version">$units<CrossSects>$body</CrossSects></LandXML>"""
            .byteInputStream()

    @Test
    fun `S050 RF-5 el fichero de ejemplo lista sus cinco secciones por PK con su tipo`() {
        val scan = scan()

        scan.version shouldBe "1.2"
        scan.declaredUnit shouldBe LinearUnit.METRE
        scan.alignmentRef shouldBe "Eje C"
        scan.sections.map { it.station } shouldBe listOf(0.0, 100.0, 150.0, 190.0, 50.0)
        scan.sections.map { it.kind } shouldBe listOf(
            SectionKind.DESIGN,
            SectionKind.DESIGN,
            SectionKind.DESIGN,
            SectionKind.DESIGN,
            SectionKind.GROUND,
        )
        scan.sections.map { it.pointCount } shouldBe listOf(5, 5, 3, 3, 4)
        scan.sections.map { it.unnamedCount } shouldBe listOf(0, 0, 3, 0, 0)
    }

    @Test
    fun `S050 RF-5 una DesignCrossSectSurf es de diseno y existing es terreno`() {
        val scan = LandXmlCrossSectReader.scan(
            xml(
                """<CrossSect sta="1"><DesignCrossSectSurf><CrossSectPnt>0 1</CrossSectPnt></DesignCrossSectSurf></CrossSect>
                   <CrossSect sta="2"><CrossSectSurf state="existing"><CrossSectPnt>0 1</CrossSectPnt></CrossSectSurf></CrossSect>
                   <CrossSect sta="3"><CrossSectSurf state="proposed"><CrossSectPnt>0 1</CrossSectPnt></CrossSectSurf></CrossSect>
                   <CrossSect sta="4"><CrossSectSurf><CrossSectPnt>0 1</CrossSectPnt></CrossSectSurf></CrossSect>""",
            ),
        )

        scan.sections.map { it.kind } shouldBe listOf(SectionKind.DESIGN, SectionKind.GROUND, SectionKind.DESIGN, SectionKind.DESIGN)
    }

    @Test
    fun `S050 RF-5 leer las elegidas da las secciones ordenadas por desplazamiento con cotas absolutas`() {
        val read = LandXmlCrossSectReader.read(AlignmentFixtures.stream("secciones/secciones-eje-c.xml"), setOf(0, 4))

        read.map { it.chainage } shouldBe listOf(0.0, 50.0)
        read[0].heightKind shouldBe HeightKind.ABSOLUTE
        read[0].points.map { it.code } shouldBe listOf("CI", "BI", "EJE", "BD", "CD")
        read[0].points.map { it.offsetM } shouldBe listOf(-8.0, -6.0, 0.0, 6.0, 8.0)
        read[0].points.map { it.heightM } shouldBe listOf(99.8, 100.1, 100.3, 100.1, 99.8)
        read[1].kind shouldBe SectionKind.GROUND
    }

    @Test
    fun `S050 RF-5 los puntos sin nombre se llaman P1 P2 P3 por desplazamiento creciente`() {
        val read = LandXmlCrossSectReader.read(AlignmentFixtures.stream("secciones/secciones-eje-c.xml"), setOf(2))

        read.single().points.map { it.code } shouldBe listOf("P1", "P2", "P3")
        read.single().points.map { it.offsetM } shouldBe listOf(-7.0, 0.0, 7.0)
    }

    @Test
    fun `S050 RF-5 un punto con nombre y otro sin el el sin nombre toma P y su posicion`() {
        val read = LandXmlCrossSectReader.read(
            xml(
                """<CrossSect sta="1"><CrossSectSurf state="proposed"><CrossSectPnt code="EJE">0 5</CrossSectPnt><CrossSectPnt>3 4</CrossSectPnt></CrossSectSurf></CrossSect>""",
            ),
            setOf(0),
        )

        read.single().points.map { it.code } shouldBe listOf("EJE", "P2")
    }

    @Test
    fun `S050 RF-5 la unidad del fichero se declara y no se convierte`() {
        val imperial = LandXmlCrossSectReader.scan(
            xml(
                """<CrossSect sta="10"><CrossSectSurf state="proposed"><CrossSectPnt>0 10</CrossSectPnt></CrossSectSurf></CrossSect>""",
                units = """<Units><Imperial linearUnit="foot"/></Units>""",
            ),
        )

        imperial.declaredUnit shouldBe LinearUnit.INTERNATIONAL_FOOT
        imperial.sections.single().station shouldBe 10.0
    }

    @Test
    fun `S050 RF-5 una seccion sin PK o con un punto que no es numero se cuenta como omitida`() {
        val scan = LandXmlCrossSectReader.scan(
            xml(
                """<CrossSect><CrossSectSurf><CrossSectPnt>0 1</CrossSectPnt></CrossSectSurf></CrossSect>
                   <CrossSect sta="5"><CrossSectSurf><CrossSectPnt>x y</CrossSectPnt><CrossSectPnt>1 2</CrossSectPnt></CrossSectSurf></CrossSect>
                   <CrossSect sta="6"><CrossSectSurf><CrossSectPnt>0 1</CrossSectPnt></CrossSectSurf></CrossSect>""",
            ),
        )

        scan.skipped shouldBe 2
        scan.sections.map { it.station } shouldBe listOf(6.0)
    }

    @Test
    fun `S050 RF-5 un fichero sin CrossSects da una lista vacia`() {
        val scan = LandXmlCrossSectReader.scan(AlignmentFixtures.stream("ejes.xml"))

        scan.sections shouldBe emptyList()
    }

    @Test
    fun `S050 RF-5 un fichero que no es LandXML de una version no admitida o mal formado se rechaza`() {
        shouldThrow<LandXmlException> { LandXmlCrossSectReader.scan("<otro/>".byteInputStream()) }.problem shouldBe
            LandXmlProblem.NOT_LANDXML
        shouldThrow<LandXmlException> { LandXmlCrossSectReader.scan(xml("", version = "2.0")) }.problem shouldBe
            LandXmlProblem.UNSUPPORTED_VERSION
        shouldThrow<XmlFormatException> { LandXmlCrossSectReader.scan("<LandXML version=\"1.2\"><CrossSects><CrossSect".byteInputStream()) }
    }

    @Test
    fun `S050 RF-5 las secciones se pasan a metros con la unidad que elija el usuario`() {
        val read = LandXmlCrossSectReader.read(
            xml(
                """<CrossSect sta="100"><CrossSectSurf state="proposed"><CrossSectPnt code="BD">10 20</CrossSectPnt></CrossSectSurf></CrossSect>""",
            ),
            setOf(0),
        )

        val metres = LandXmlCrossSectReader.toMetres(read, LinearUnit.INTERNATIONAL_FOOT).single()

        metres.chainage shouldBe (30.48 plusOrMinus 1e-12)
        metres.points.single().offsetM shouldBe (3.048 plusOrMinus 1e-12)
        metres.points.single().heightM shouldBe (6.096 plusOrMinus 1e-12)
    }
}
