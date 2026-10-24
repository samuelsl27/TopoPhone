package es.topophone.core.formats.csv

import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportSeparator
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.StringReader
import org.junit.jupiter.api.Test

/** La lectura de las filas del fichero: separadores, comillas, saltos de línea y límites (S028/RF-3, RF-5, RF-6). */
class DelimitedReaderTest {

    private fun rows(text: String, separator: ImportSeparator = ImportSeparator.COMMA) =
        DelimitedReader.rows(StringReader(text), separator).toList()

    private fun cells(text: String, separator: ImportSeparator = ImportSeparator.COMMA) = rows(text, separator).map { it.cells }

    private fun fixture(name: String): Pair<List<DelimitedRow>, ImportSeparator> {
        val bytes = ImportFixtures.bytes(name)
        val encoding = (TextDecoding.probe(bytes) as TextProbe.Text).encoding
        val text = TextDecoding.decode(bytes, encoding)
        val separator = DelimitedReader.detectSeparator(text)
        return DelimitedReader.rows(TextDecoding.reader(ByteArrayInputStream(bytes), encoding), separator).toList() to separator
    }

    @Test
    fun `S028 RF-5 la coma separa las columnas`() {
        cells("1,2.5,x\n") shouldBe listOf(listOf("1", "2.5", "x"))
    }

    @Test
    fun `S028 RF-5 el punto y coma, el tabulador y los espacios tambien separan`() {
        cells("1;2,5;x", ImportSeparator.SEMICOLON) shouldBe listOf(listOf("1", "2,5", "x"))
        cells("1\t2.5\tx", ImportSeparator.TAB) shouldBe listOf(listOf("1", "2.5", "x"))
        cells("1 2.5 x", ImportSeparator.SPACES) shouldBe listOf(listOf("1", "2.5", "x"))
    }

    @Test
    fun `S028 RF-5 varios espacios seguidos y los de los extremos valen un solo separador`() {
        cells("  1     2.5   x  \n", ImportSeparator.SPACES) shouldBe listOf(listOf("1", "2.5", "x"))
    }

    @Test
    fun `S028 RF-5 con los demas separadores una columna vacia es una celda vacia`() {
        cells("1,,3,\n") shouldBe listOf(listOf("1", "", "3", ""))
        cells("4;;;Sin cota", ImportSeparator.SEMICOLON) shouldBe listOf(listOf("4", "", "", "Sin cota"))
    }

    @Test
    fun `S028 RF-5 los espacios de alrededor de una celda sin comillas se quitan`() {
        cells("1 , 2.5 ,  x\n") shouldBe listOf(listOf("1", "2.5", "x"))
    }

    @Test
    fun `S028 RF-6 una celda entre comillas puede llevar el separador`() {
        cells("1,\"Pozo, registro\",3\n") shouldBe listOf(listOf("1", "Pozo, registro", "3"))
    }

    @Test
    fun `S028 RF-6 dos comillas seguidas dentro de comillas son una comilla`() {
        cells("1,\"Pozo \"\"nuevo\"\"\",3\n") shouldBe listOf(listOf("1", "Pozo \"nuevo\"", "3"))
    }

    @Test
    fun `S028 RF-6 una celda entre comillas conserva los espacios y puede llevar un salto de linea`() {
        cells("1,\" a  b \",\"x\ny\"\n") shouldBe listOf(listOf("1", " a  b ", "x\ny"))
    }

    @Test
    fun `S028 RF-6 una comilla en mitad de una celda es un caracter mas`() {
        cells("5\",6\n") shouldBe listOf(listOf("5\"", "6"))
    }

    @Test
    fun `S028 RF-6 unas comillas sin cerrar llegan hasta el final del fichero`() {
        cells("1,\"abc\ndef") shouldBe listOf(listOf("1", "abc\ndef"))
    }

    @Test
    fun `S028 RF-6 las comillas funcionan igual con los espacios como separador`() {
        cells("1 \"Pozo registro\" 3", ImportSeparator.SPACES) shouldBe listOf(listOf("1", "Pozo registro", "3"))
    }

    @Test
    fun `S028 RF-5 CRLF, LF y CR terminan una fila`() {
        cells("a,b\r\nc,d\ne,f\rg,h") shouldBe listOf(listOf("a", "b"), listOf("c", "d"), listOf("e", "f"), listOf("g", "h"))
    }

    @Test
    fun `S028 RF-5 la ultima fila puede no acabar en salto de linea`() {
        cells("a,b\nc,d") shouldBe listOf(listOf("a", "b"), listOf("c", "d"))
        cells("a,b\nc,d\n") shouldBe listOf(listOf("a", "b"), listOf("c", "d"))
    }

    @Test
    fun `S028 RF-5 las lineas vacias se saltan pero cuentan en el numero de linea`() {
        val read = rows("a,b\n\n   \nc,d\r\n\r\ne,f")

        read.map { it.line } shouldBe listOf(1, 4, 6)
        read.map { it.cells } shouldBe listOf(listOf("a", "b"), listOf("c", "d"), listOf("e", "f"))
    }

    @Test
    fun `S028 RF-5 una fila de solo separadores no es una linea vacia`() {
        cells(",,\n") shouldBe listOf(listOf("", "", ""))
    }

    @Test
    fun `S028 RF-5 un fichero vacio o de solo saltos no tiene filas`() {
        rows("") shouldBe emptyList()
        rows("\n\r\n\n") shouldBe emptyList()
    }

    @Test
    fun `S028 RF-5 tras una celda con saltos de linea dentro, las filas siguientes llevan su linea del fichero`() {
        val read = rows("a,b\n\"x\ny\nz\",c\nd,e\n")

        read.map { it.line } shouldBe listOf(1, 2, 5)
    }

    @Test
    fun `S028 RF-5 la lectura es perezosa y no pide mas texto del que se consume`() {
        val reader = StringReader("a,b\nc,d\ne,f\n")

        DelimitedReader.rows(reader, ImportSeparator.COMMA).first().cells shouldBe listOf("a", "b")
        reader.read() shouldBe 'c'.code // solo se ha leido la primera fila y el salto
    }

    @Test
    fun `S028 RF-5 detecta la coma, el punto y coma, el tabulador y los espacios`() {
        DelimitedReader.detectSeparator("a,b,c\n1,2,3\n4,5,6\n") shouldBe ImportSeparator.COMMA
        DelimitedReader.detectSeparator("a;b;c\n1;2;3\n4;5;6\n") shouldBe ImportSeparator.SEMICOLON
        DelimitedReader.detectSeparator("a\tb\tc\n1\t2\t3\n4\t5\t6\n") shouldBe ImportSeparator.TAB
        DelimitedReader.detectSeparator("a   b   c\n1   2   3\n4   5   6\n") shouldBe ImportSeparator.SPACES
    }

    @Test
    fun `S028 RF-5 con la coma decimal y el punto y coma de separador gana el punto y coma`() {
        DelimitedReader.detectSeparator("1;440297,988;4474244,001\n2;725695,707;4372210,140\n") shouldBe ImportSeparator.SEMICOLON
    }

    @Test
    fun `S028 RF-5 no cuenta los separadores de dentro de comillas`() {
        DelimitedReader.detectSeparator("a;\"x,y,z,w\";c\n1;\"p,q,r\";3\n2;\"s\";4\n") shouldBe ImportSeparator.SEMICOLON
    }

    @Test
    fun `S028 RF-5 en un empate elige punto y coma, luego tabulador, luego coma y por ultimo espacio`() {
        DelimitedReader.detectSeparator("a;b,c\n1;2,3\n") shouldBe ImportSeparator.SEMICOLON
        DelimitedReader.detectSeparator("a\tb,c\n1\t2,3\n") shouldBe ImportSeparator.TAB
        DelimitedReader.detectSeparator("a b,c\n1 2,3\n") shouldBe ImportSeparator.COMMA
    }

    @Test
    fun `S028 RF-5 gana el separador que da el mismo numero de columnas en mas filas`() {
        DelimitedReader.detectSeparator("a,b,c\n1,2,3\n4,5 6,7\n8,9 10,11\n") shouldBe ImportSeparator.COMMA
    }

    @Test
    fun `S028 RF-5 sin ningun separador reconocible propone la coma`() {
        DelimitedReader.detectSeparator("uno\ndos\ntres\n") shouldBe ImportSeparator.COMMA
        DelimitedReader.detectSeparator("") shouldBe ImportSeparator.COMMA
    }

    @Test
    fun `S028 RF-5 solo mira las 20 primeras filas no vacias`() {
        val tail = (1..30).joinToString("") { "x y z w\n" }

        DelimitedReader.detectSeparator("a;b;c\n" + "1;2;3\n".repeat(19) + tail) shouldBe ImportSeparator.SEMICOLON
    }

    @Test
    fun `S028 RF-5 detecta el separador de cada fichero de ejemplo`() {
        val expected = mapOf(
            "etrs89-utm30-utf8-bom-coma.csv" to ImportSeparator.COMMA,
            "etrs89-utm30-windows1252-puntoycoma.txt" to ImportSeparator.SEMICOLON,
            "etrs89-utm30-tabulador-sin-cabecera.txt" to ImportSeparator.TAB,
            "etrs89-utm30-espacios-pnezd.txt" to ImportSeparator.SPACES,
            "etrs89-utm30-comillas.csv" to ImportSeparator.COMMA,
            "etrs89-geograficas.csv" to ImportSeparator.COMMA,
            "regcan95-utm28.csv" to ImportSeparator.COMMA,
            "ed50-utm30.csv" to ImportSeparator.COMMA,
            "etrs89-utm30-pies.csv" to ImportSeparator.COMMA,
            "etrs89-utm30-errores.csv" to ImportSeparator.SEMICOLON,
        )

        ImportFixtures.samples.toSet() shouldBe expected.keys
        expected.forEach { (name, separator) -> fixture(name).second shouldBe separator }
    }

    @Test
    fun `S028 RF-5 detecta el separador de los CSV que escribe S012`() {
        val expected = mapOf(
            "todas_coma_punto.csv" to ImportSeparator.COMMA,
            "todas_puntoycoma_coma.csv" to ImportSeparator.SEMICOLON,
            "todas_tabulador_punto.csv" to ImportSeparator.TAB,
            "penzd_puntoycoma_coma.csv" to ImportSeparator.SEMICOLON,
            "pnezd_coma_punto.csv" to ImportSeparator.COMMA,
        )

        expected.forEach { (name, separator) ->
            DelimitedReader.detectSeparator(TextDecoding.decode(ImportFixtures.s012(name), ImportEncoding.UTF8)) shouldBe separator
        }
    }

    @Test
    fun `S028 RF-6 el fichero de comillas se lee con la celda de varias lineas y las lineas del fichero`() {
        val (read, _) = fixture("etrs89-utm30-comillas.csv")

        read[0].cells shouldBe listOf("Name", "Easting", "Northing", "Elevation", "Description")
        read[2].cells[4] shouldBe "Pozo,\nregistro"
        read.map { it.line } shouldBe listOf(1, 2, 3, 5, 6, 7, 8)
    }

    @Test
    fun `S028 RF-5 el fichero con errores da las lineas que dice la tabla de esperados`() {
        val (read, _) = fixture("etrs89-utm30-errores.csv")

        read.map { it.line } shouldBe (1..10).toList()
        read[4].cells shouldBe listOf("E2", "440290,458")
        read[2].cells[0] shouldBe ""
    }

    @Test
    fun `S028 RF-5 el fichero sin cabecera y con tabulador no pierde la primera fila`() {
        val (read, separator) = fixture("etrs89-utm30-tabulador-sin-cabecera.txt")

        separator shouldBe ImportSeparator.TAB
        read.first().cells[0] shouldBe "1"
        read.first().line shouldBe 1
    }

    @Test
    fun `S028 RF-3 el limite es de 20 MB y 50000 filas`() {
        DelimitedReader.MAX_BYTES shouldBe 20L * 1024 * 1024
        DelimitedReader.MAX_ROWS shouldBe 50_000
    }

    @Test
    fun `S028 RF-3 un fichero dentro de los limites no tiene problema de tamano`() {
        DelimitedReader.limitExceeded(sizeBytes = 20L * 1024 * 1024, rows = 50_000) shouldBe null
        DelimitedReader.limitExceeded(sizeBytes = null, rows = 10) shouldBe null
    }

    @Test
    fun `S028 RF-3 un fichero de mas de 20 MB o de mas de 50000 filas pasa el limite`() {
        DelimitedReader.limitExceeded(sizeBytes = 20L * 1024 * 1024 + 1, rows = 10) shouldBe ImportLimit.TOO_BIG
        DelimitedReader.limitExceeded(sizeBytes = 100, rows = 50_001) shouldBe ImportLimit.TOO_MANY_ROWS
        DelimitedReader.limitExceeded(sizeBytes = 20L * 1024 * 1024 + 1, rows = 50_001) shouldBe ImportLimit.TOO_BIG
    }

    @Test
    fun `S028 RF-3 el recuento de filas se corta al pasar el limite sin leer todo el fichero`() {
        val text = (1..60_000).joinToString("") { "$it,1,2\n" }

        DelimitedReader.countRows(StringReader(text), ImportSeparator.COMMA) shouldBe DelimitedReader.MAX_ROWS + 1
        DelimitedReader.countRows(StringReader("a,b\n\nc,d\n"), ImportSeparator.COMMA) shouldBe 2
    }
}
