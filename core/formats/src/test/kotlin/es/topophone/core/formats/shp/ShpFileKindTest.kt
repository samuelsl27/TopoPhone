package es.topophone.core.formats.shp

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La cabecera de un Shapefile y la de un ZIP se reconocen por su contenido, no por la extensión (S030/RF-2, RF-3, RF-6). */
class ShpFileKindTest {

    private val layers = ShpFixtures.expected("esperado-capas.csv")

    @Test
    fun `S030 RF-2 las cabeceras de los shp de ejemplo se reconocen con su tipo`() {
        (layers.size >= 40) shouldBe true
        for (row in layers) {
            val path = "${row.getValue("escenario")}/${row.getValue("capa")}.shp"
            val file = ShpFixtures.file(path)
            ShpFileKind.isShapeHeader(ShpFixtures.head(path), file.length()) shouldBe true
            ShpFileKind.shapeType(ShpFixtures.head(path)) shouldBe row.getValue("tipo_shp").toInt()
        }
    }

    @Test
    fun `S030 RF-2 un shp guardado con otra extension se reconoce por su contenido`() {
        val head = ShpFixtures.head("otra-extension/vertices.dat")

        ShpFileKind.isShapeHeader(head, ShpFixtures.file("otra-extension/vertices.dat").length()) shouldBe true
        ShpFileKind.shapeType(head) shouldBe 1
    }

    @Test
    fun `S030 RF-2 un dbf, un prj, un cpg o un texto no son un shp`() {
        for (path in listOf("puntos-25830/vertices.dbf", "puntos-25830/vertices.prj", "puntos-25830/vertices.cpg", "zip/texto.zip")) {
            ShpFileKind.isShapeHeader(ShpFixtures.head(path), ShpFixtures.file(path).length()) shouldBe false
        }
    }

    @Test
    fun `S030 RF-3 una cabecera cortada, con otro codigo o con un tipo imposible no es un shp`() {
        val good = ShpFixtures.head("puntos-25830/vertices.shp")

        ShpFileKind.isShapeHeader(good.copyOf(99), 1000) shouldBe false
        ShpFileKind.isShapeHeader(good.copyOf().also { it[3] = 0x0B }, 1000) shouldBe false // código 9995
        ShpFileKind.isShapeHeader(good.copyOf().also { it[28] = 0xE9.toByte() }, 1000) shouldBe false // versión 1001
        ShpFileKind.isShapeHeader(good.copyOf().also { it[32] = 0x02 }, 1000) shouldBe false // tipo 2 no existe
        ShpFileKind.isShapeHeader(good, 99) shouldBe false // un fichero de menos de 100 bytes no puede tenerla
        ShpFileKind.shapeType(good.copyOf(30)) shouldBe null
    }

    @Test
    fun `S030 RF-2 el shx tiene la misma cabecera que el shp`() {
        val shx = ShpFixtures.head("puntos-25830/vertices.shx")

        ShpFileKind.isShapeHeader(shx, ShpFixtures.file("puntos-25830/vertices.shx").length()) shouldBe true
    }

    @Test
    fun `S030 RF-2 un ZIP se reconoce por su cabecera y no por su extension`() {
        for (zip in ShpFixtures.expected("esperado-zip.csv").map { it.getValue("archivo") }) {
            val head = ShpFixtures.head(zip)
            ShpFileKind.isZip(head) shouldBe (zip != "zip/texto.zip")
        }
        ShpFileKind.isZip(ShpFixtures.head("zip/vacio.zip")) shouldBe true // un ZIP sin entradas empieza por PK 05 06
        ShpFileKind.isZip(byteArrayOf(0x50, 0x4B)) shouldBe false
        ShpFileKind.isZip(ShpFixtures.head("puntos-25830/vertices.shp")) shouldBe false
    }

    @Test
    fun `S030 RF-6 los limites son 100 MB entre los ficheros elegidos y 500 MB descomprimidos`() {
        ShpFileKind.MAX_PICKED_BYTES shouldBe 100L * 1024 * 1024
        ShpFileKind.MAX_UNCOMPRESSED_BYTES shouldBe 500L * 1024 * 1024
    }
}
