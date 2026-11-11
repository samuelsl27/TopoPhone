package es.topophone.core.formats.shp

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** La codificación del `.dbf`: la que declara el `.cpg` y, sin él, UTF-8 si todo el texto lo es y Windows-1252 si no (S030/RF-18). */
class ShpEncodingTest {

    private val layers = ShpFixtures.expected("esperado-capas.csv")

    private fun encoding(label: String) = if (label == "UTF-8") ShpEncoding.UTF_8 else ShpEncoding.WINDOWS_1252

    @Test
    fun `S030 RF-18 el cpg de UTF-8 y de Windows-1252 se reconoce con sus variantes`() {
        for (text in listOf("UTF-8", "utf8", "65001", " UTF-8\r\n", "UTF_8")) {
            ShpEncodings.fromCpg(text) shouldBe CpgDeclaration(ShpEncoding.UTF_8, known = true)
        }
        for (text in listOf("1252", "ANSI 1252", "windows-1252", "CP1252", "Windows1252", "1252\n")) {
            ShpEncodings.fromCpg(text) shouldBe CpgDeclaration(ShpEncoding.WINDOWS_1252, known = true)
        }
    }

    @Test
    fun `S030 RF-18 un cpg de otra codificacion se lee como Windows-1252 y se avisa y uno vacio es como no tenerlo`() {
        for (text in listOf("ISO-8859-5", "ISO-8859-1", "850", "UTF-16")) {
            ShpEncodings.fromCpg(text) shouldBe CpgDeclaration(ShpEncoding.WINDOWS_1252, known = false)
        }
        ShpEncodings.fromCpg("") shouldBe null
        ShpEncodings.fromCpg("  \r\n") shouldBe null
        ShpEncodings.fromCpg(null) shouldBe null
    }

    @Test
    fun `S030 RF-18 el cpg de cada capa de ejemplo da la codificacion esperada`() {
        var checked = 0
        for (row in layers.filter { it.getValue("cpg") == "SI" && it.getValue("codificacion").isNotEmpty() }) {
            val text = ShpFixtures.file("${row.getValue("escenario")}/${row.getValue("capa")}.cpg").readText()

            val declared = ShpEncodings.fromCpg(text)!!

            declared.encoding shouldBe encoding(row.getValue("codificacion"))
            declared.known shouldBe (row.getValue("cpg_desconocido") == "NO")
            checked++
        }
        (checked > 30) shouldBe true
    }

    @Test
    fun `S030 RF-18 sin cpg se decide por el contenido de los registros`() {
        var checked = 0
        for (row in layers.filter { it.getValue("cpg") == "NO" && it.getValue("columnas").isNotEmpty() }) {
            val source = ShpByteSource.of(ShpFixtures.bytes("${row.getValue("escenario")}/${row.getValue("capa")}.dbf"))
            val reader = DbfReader.open(source, ShpEncoding.UTF_8).shouldBeInstanceOf<DbfReader>()

            reader.detectEncoding() shouldBe encoding(row.getValue("codificacion"))
            checked++
        }
        (checked >= 3) shouldBe true
    }

    @Test
    fun `S030 RF-18 el texto de Windows-1252 no es UTF-8 y el de UTF-8 sin tildes tampoco molesta`() {
        val w1252 = ShpByteSource.of("Árbol ñandú".toByteArray(charset("windows-1252")))
        val utf8 = ShpByteSource.of("Árbol ñandú — Plaza".toByteArray())
        val ascii = ShpByteSource.of("Solo ASCII".toByteArray())

        ShpEncodings.isValidUtf8(w1252, 0, w1252.size) shouldBe false
        ShpEncodings.isValidUtf8(utf8, 0, utf8.size) shouldBe true
        ShpEncodings.isValidUtf8(ascii, 0, ascii.size) shouldBe true
    }

    @Test
    fun `S030 RF-18 un caracter de varios bytes en la frontera de una lectura no es un error`() {
        // 30.000 rayas «—» (3 bytes cada una) cruzan el tamaño de lectura (64 KB) por la mitad de un carácter
        val text = "—".repeat(30_000).toByteArray()
        ShpEncodings.isValidUtf8(ShpByteSource.of(text), 0, text.size.toLong()) shouldBe true
        // la misma cadena con el último byte cortado sí lo es
        val cut = text.copyOf(text.size - 1)
        ShpEncodings.isValidUtf8(ShpByteSource.of(cut), 0, cut.size.toLong()) shouldBe false
        // y solo se mira entre los límites dados
        val mixed = byteArrayOf(0xF1.toByte(), 'a'.code.toByte(), 'b'.code.toByte())
        ShpEncodings.isValidUtf8(ShpByteSource.of(mixed), 1, 3) shouldBe true
    }

    @Test
    fun `S030 RF-18 el cpg de QGIS es UTF-8 o 1252`() {
        ShpEncodings.fromCpg(ShpFixtures.file("qgis/windows-1252/nombres.cpg").readText()) shouldBe
            CpgDeclaration(ShpEncoding.WINDOWS_1252, known = true)
        ShpEncodings.fromCpg(ShpFixtures.file("qgis/catastro-25830/parcelas.cpg").readText()) shouldBe
            CpgDeclaration(ShpEncoding.UTF_8, known = true)
    }
}
