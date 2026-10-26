package es.topophone.core.formats.landxml

import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.model.project.LinearUnit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test

/** La lectura de un LandXML: versiones, unidad, superficies, orden de las coordenadas, identificadores y caras invisibles (S042). */
class LandXmlReaderTest {

    private fun scan(name: String) = SurfaceFixtures.stream(name).use { LandXmlReader.scan(it) }

    private fun read(name: String, index: Int = 0) = SurfaceFixtures.stream(name).use { LandXmlReader.read(it, index) }

    private fun xml(version: String?, body: String) = ByteArrayInputStream(
        ("<LandXML ${if (version == null) "" else "version=\"$version\""}>$body</LandXML>").toByteArray(),
    )

    private val tin = "<Surfaces><Surface name=\"s\"><Definition surfType=\"TIN\"><Pnts><P id=\"1\">0 0 0</P><P id=\"2\">0 10 1</P>" +
        "<P id=\"3\">10 0 2</P></Pnts><Faces><F>1 2 3</F></Faces></Definition></Surface></Surfaces>"

    @Test
    fun `S042 RF-5 el escaneo lista las dos superficies con sus recuentos`() {
        val scan = scan("dos-superficies.xml")

        scan.surfaces.map { it.name } shouldBe listOf("terreno", "rasante")
        scan.surfaces.map { it.index } shouldBe listOf(0, 1)
        val terrain = scan.surfaces[0]
        terrain.pointCount shouldBe 42
        terrain.faceCount shouldBe 60
        terrain.hiddenFaceCount shouldBe 2
        terrain.visibleFaceCount shouldBe 58
        terrain.importable shouldBe true
        scan.surfaces[1].pointCount shouldBe 4
        scan.surfaces[1].visibleFaceCount shouldBe 2
        scan.version shouldBe "1.2"
        scan.importable.size shouldBe 2
    }

    @Test
    fun `S042 RF-8 el escaneo da la extension y las cotas de la superficie en la unidad del fichero`() {
        val terrain = scan("dos-superficies.xml").surfaces[0]

        terrain.minEasting shouldBe (440290.45805 plusOrMinus 1e-5)
        terrain.maxEasting shouldBe (440411.72123 plusOrMinus 1e-5)
        terrain.minNorthing shouldBe (4474256.42340 plusOrMinus 1e-5)
        terrain.minHeight shouldBe (600.0 plusOrMinus 1e-5)
        (terrain.maxHeight > 607.0) shouldBe true
    }

    @Test
    fun `S042 RF-5 la lectura entrega solo la superficie pedida`() {
        val terrain = read("dos-superficies.xml", 0)!!
        val rasante = read("dos-superficies.xml", 1)!!

        terrain.name shouldBe "terreno"
        terrain.pointCount shouldBe 42
        terrain.triangleCount shouldBe 58
        rasante.name shouldBe "rasante"
        rasante.pointCount shouldBe 4
        rasante.triangleCount shouldBe 2
        read("dos-superficies.xml", 2).shouldBeNull()
    }

    @Test
    fun `S042 RF-2 cada punto se lee como Norte Este cota`() {
        val terrain = read("dos-superficies.xml")!!

        terrain.northing[0] shouldBe (4474257.38189 plusOrMinus 1e-5)
        terrain.easting[0] shouldBe (440290.45805 plusOrMinus 1e-5)
        terrain.height[0] shouldBe (600.0 plusOrMinus 1e-9)
        terrain.northing[1] shouldBe (4474257.22201 plusOrMinus 1e-5)
        terrain.easting[1] shouldBe (440310.53611 plusOrMinus 1e-5)
        terrain.height[1] shouldBe (602.458 plusOrMinus 1e-9)
    }

    @Test
    fun `S042 RF-2 los identificadores no consecutivos se traducen a la posicion del punto`() {
        val terrain = read("dos-superficies.xml")!!

        terrain.triangles.all { it in 0 until terrain.pointCount } shouldBe true
        // La primera cara es la de la primera celda: los puntos 101, 104 y 117 (a, a + 1 y a + NX + 2), en las posiciones 0, 1 y 8.
        terrain.triangles.take(3) shouldBe listOf(0, 1, 8)
        terrain.droppedFaceCount shouldBe 0
    }

    @Test
    fun `S042 RF-2 los identificadores de texto tambien funcionan`() {
        val surface = read("ids-texto.xml")!!

        surface.pointCount shouldBe 4
        surface.triangles.toList() shouldBe listOf(0, 1, 2, 0, 2, 3)
        surface.height.toList() shouldBe listOf(10.0, 11.0, 12.0, 13.0)
    }

    @Test
    fun `S042 RF-12 las caras invisibles son huecos y no triangulos`() {
        val terrain = read("dos-superficies.xml")!!

        terrain.hiddenFaceCount shouldBe 2
        terrain.triangleCount shouldBe 58
        scan("dos-superficies.xml").surfaces[0].hiddenFaceCount shouldBe 2
    }

    @Test
    fun `S042 RF-7 la unidad sale de Units`() {
        scan("dos-superficies.xml").declaredUnit shouldBe LinearUnit.METRE
        scan("en-pies.xml").declaredUnit shouldBe LinearUnit.INTERNATIONAL_FOOT
        LandXmlReader.scan(xml("1.2", "<Units><Imperial linearUnit=\"USSurveyFoot\"/></Units>$tin")).declaredUnit shouldBe
            LinearUnit.US_SURVEY_FOOT
        LandXmlReader.scan(xml("1.2", "<Units><Metric linearUnit=\"millimeter\"/></Units>$tin")).declaredUnit.shouldBeNull()
        LandXmlReader.scan(xml("1.2", tin)).declaredUnit.shouldBeNull()
    }

    @Test
    fun `S042 RF-7 las coordenadas en pies se entregan sin convertir`() {
        val feet = read("en-pies.xml")!!
        val metres = read("dos-superficies.xml")!!

        feet.easting[0] shouldBe (metres.easting[0] / 0.3048 plusOrMinus 1e-5)
        feet.height[1] shouldBe (metres.height[1] / 0.3048 plusOrMinus 1e-5)
    }

    @Test
    fun `S042 RF-2 acepta las versiones 1_0 a 1_2 y los ficheros sin version`() {
        for (version in listOf("1.0", "1.1", "1.2", null)) {
            LandXmlReader.scan(xml(version, tin)).importable.size shouldBe 1
        }
    }

    @Test
    fun `S042 RF-2 una version que no es de 1_0 a 1_2 se rechaza con la version`() {
        val e = shouldThrow<LandXmlException> { LandXmlReader.scan(xml("2.0", tin)) }

        e.problem shouldBe LandXmlProblem.UNSUPPORTED_VERSION
        e.version shouldBe "2.0"
        shouldThrow<LandXmlException> { LandXmlReader.scan(xml("1.3", tin)) }
    }

    @Test
    fun `S042 RF-3 un XML que no es LandXML se rechaza`() {
        val e = shouldThrow<LandXmlException> { LandXmlReader.scan(ByteArrayInputStream("<html><body/></html>".toByteArray())) }

        e.problem shouldBe LandXmlProblem.NOT_LANDXML
    }

    @Test
    fun `S042 RF-3 una malla regular no se ofrece`() {
        val scan = scan("malla.xml")

        scan.surfaces.single().isTin shouldBe false
        scan.surfaces.single().importable shouldBe false
        scan.importable shouldBe emptyList()
        read("malla.xml").shouldBeNull()
    }

    @Test
    fun `S042 RF-3 un LandXML sin superficies no tiene nada que importar`() {
        val scan = scan("sin-superficies.xml")

        scan.surfaces shouldBe emptyList()
        scan.importable shouldBe emptyList()
    }

    @Test
    fun `S042 RF-3 una superficie sin triangulos visibles no se ofrece`() {
        val onlyHidden = "<Surfaces><Surface name=\"h\"><Definition surfType=\"TIN\"><Pnts><P id=\"1\">0 0 0</P><P id=\"2\">0 1 0</P>" +
            "<P id=\"3\">1 0 0</P></Pnts><Faces><F i=\"1\">1 2 3</F></Faces></Definition></Surface></Surfaces>"
        val noFaces = "<Surfaces><Surface name=\"n\"><Definition surfType=\"TIN\"><Pnts><P id=\"1\">0 0 0</P></Pnts></Definition>" +
            "</Surface></Surfaces>"

        LandXmlReader.scan(xml("1.2", onlyHidden)).importable shouldBe emptyList()
        LandXmlReader.scan(xml("1.2", noFaces)).importable shouldBe emptyList()
    }

    @Test
    fun `S042 RF-3 un XML cortado o mal formado se rechaza`() {
        shouldThrow<XmlFormatException> { scan("mal-formado.xml") }
        shouldThrow<XmlFormatException> { read("mal-formado.xml") }
    }

    @Test
    fun `S042 RF-12 una cara que cita un punto que no existe o repite un punto se descarta y se cuenta`() {
        val body = "<Surfaces><Surface name=\"s\"><Definition surfType=\"TIN\"><Pnts><P id=\"1\">0 0 0</P><P id=\"2\">0 10 1</P>" +
            "<P id=\"3\">10 0 2</P><P id=\"4\">x y z</P></Pnts><Faces><F>1 2 3</F><F>1 2 9</F><F>1 1 2</F><F>1 2 4</F><F>1 2</F></Faces>" +
            "</Definition></Surface></Surfaces>"

        val surface = LandXmlReader.read(xml("1.2", body), 0)!!

        surface.pointCount shouldBe 3
        surface.triangleCount shouldBe 1
        surface.droppedFaceCount shouldBe 4
    }

    @Test
    fun `S042 RF-2 un fichero con muchos puntos se lee entero`() {
        val n = 200_000
        val points = (1..n).joinToString("") { "<P id=\"${it * 2}\">${4474000.0 + it} ${440000.0 + it} ${600.0 + it % 7}</P>" }
        val faces = (1..n - 2).joinToString("") { "<F>${it * 2} ${(it + 1) * 2} ${(it + 2) * 2}</F>" }
        val body =
            "<Surfaces><Surface name=\"grande\"><Definition surfType=\"TIN\"><Pnts>$points</Pnts><Faces>$faces</Faces></Definition>" +
                "</Surface></Surfaces>"

        val surface = LandXmlReader.read(xml("1.2", body), 0)!!

        surface.pointCount shouldBe n
        surface.triangleCount shouldBe n - 2
        surface.triangles.last() shouldBe n - 1
    }
}
