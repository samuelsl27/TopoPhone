package es.topophone.core.formats.shp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El WKT de un `.prj` a un árbol, en los estilos de ESRI y de OGC (S030/RF-8). */
class PrjReaderTest {

    private fun parse(text: String): WktNode = PrjReader.parse(text).shouldBeInstanceOf<WktNode>()

    @Test
    fun `S030 RF-8 un prj de QGIS en estilo ESRI se lee con su proyeccion sus parametros y su unidad`() {
        val root = parse(ShpFixtures.file("qgis/catastro-25830/parcelas.prj").readText())

        root.keyword shouldBe "PROJCS"
        root.text(0) shouldBe "ETRS_1989_UTM_Zone_30N"
        root.child("PROJECTION")!!.text(0) shouldBe "Transverse_Mercator"
        root.parameter("central_meridian") shouldBe -3.0
        root.parameter("FALSE_EASTING") shouldBe 500000.0
        root.parameter("scale_factor") shouldBe 0.9996
        root.parameter("no_existe") shouldBe null
        val geographic = root.child("GEOGCS")!!
        geographic.text(0) shouldBe "GCS_ETRS_1989"
        geographic.child("DATUM")!!.text(0) shouldBe "D_ETRS_1989"
        val spheroid = geographic.child("DATUM")!!.child("SPHEROID")!!
        listOf(spheroid.text(0), spheroid.number(1), spheroid.number(2)) shouldBe listOf("GRS_1980", 6378137.0, 298.257222101)
        root.children.last { it.keyword == "UNIT" }.let { listOf(it.text(0), it.number(1)) } shouldBe listOf("Meter", 1.0)
    }

    @Test
    fun `S030 RF-8 un prj en estilo OGC trae la autoridad del sistema al final de la raiz`() {
        val root = parse(ShpFixtures.file("prj/gdal-25830.prj").readText())

        root.keyword shouldBe "PROJCS"
        root.children.last { it.keyword == "AUTHORITY" }.let { listOf(it.text(0), it.text(1)) } shouldBe listOf("EPSG", "25830")
        root.child("GEOGCS")!!.child("AUTHORITY")!!.text(1) shouldBe "4258"
    }

    @Test
    fun `S030 RF-8 todos los prj de ejemplo se leen`() {
        val files = ShpFixtures.expected("esperado-prj.csv").map { it.getValue("archivo") }

        (files.size >= 50) shouldBe true
        for (file in files) {
            val root = parse(ShpFixtures.file(file).readText())
            (root.keyword in listOf("PROJCS", "GEOGCS")) shouldBe true
        }
    }

    @Test
    fun `S030 RF-8 los parentesis valen como los corchetes y los numeros admiten exponente y signo`() {
        val root = parse("GEOGCS(\"Prueba, con coma\", UNIT(\"Degree\", 1.74532925199433E-2), PARAMETER(\"x\", -1.5e+2))")

        root.text(0) shouldBe "Prueba, con coma"
        root.child("UNIT")!!.number(1) shouldBe 0.0174532925199433
        root.child("PARAMETER")!!.number(1) shouldBe -150.0
    }

    @Test
    fun `S030 RF-8 una palabra sin corchetes es un valor y una comilla doble dentro de un texto se duplica`() {
        val root = parse("PROJCS[\"Con \"\"comillas\"\"\",AXIS[\"Easting\",EAST],AXIS[\"Northing\",NORTH]]")

        root.text(0) shouldBe "Con \"comillas\""
        root.children.map { it.text(1) } shouldBe listOf("EAST", "NORTH")
    }

    @Test
    fun `S030 RF-8 lo que no es un WKT no se lee`() {
        val bad = listOf(
            "", "   ", "Esto no es un WKT\r\n", "PROJCS[", "PROJCS[\"a\"", "PROJCS[\"a\"]]", "[\"a\"]",
            "PROJCS[\"a\"] y algo mas", "\u0000\u0001\u0002",
        )
        for (text in bad) {
            PrjReader.parse(text) shouldBe null
        }
        PrjReader.parse("[".repeat(200) + "]".repeat(200)) shouldBe null // anidado hasta el absurdo
        PrjReader.parse("PROJCS[\"" + "x".repeat(300_000) + "\"]") shouldBe null // más largo de lo razonable
    }

    @Test
    fun `S030 RF-8 las palabras clave y los parametros no distinguen mayusculas`() {
        val root = parse("projcs[\"x\", parameter[\"False_Easting\", 5.0]]")

        root.keyword shouldBe "PROJCS"
        root.child("Parameter") shouldBe root.children.single()
        root.parameter("false_easting") shouldBe 5.0
    }
}
