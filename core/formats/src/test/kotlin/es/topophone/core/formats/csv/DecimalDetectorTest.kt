package es.topophone.core.formats.csv

import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportSeparator
import io.kotest.matchers.shouldBe
import java.io.StringReader
import org.junit.jupiter.api.Test

/** El signo decimal del fichero (S028/RF-7, RF-8). */
class DecimalDetectorTest {

    private fun rows(vararg cells: List<String>) = cells.mapIndexed { i, row -> DelimitedRow(i + 1, row) }

    @Test
    fun `S028 RF-7 con comas entre digitos y ningun punto, el decimal es la coma`() {
        val rows = rows(listOf("1", "440297,988", "4474244,001"), listOf("2", "725695,707", "4372210,140"))

        DecimalDetector.detect(rows, ImportSeparator.SEMICOLON) shouldBe ImportDecimal.COMMA
    }

    @Test
    fun `S028 RF-7 con puntos entre digitos, el decimal es el punto`() {
        DecimalDetector.detect(rows(listOf("1", "440297.988", "4474244.001")), ImportSeparator.TAB) shouldBe ImportDecimal.POINT
    }

    @Test
    fun `S028 RF-7 con comas y puntos a la vez en celdas distintas, el decimal es el punto`() {
        val rows = rows(listOf("1", "440297,988", "4474244.001"))

        DecimalDetector.detect(rows, ImportSeparator.SEMICOLON) shouldBe ImportDecimal.POINT
    }

    @Test
    fun `S028 RF-7 sin ningun numero con decimales propone el punto`() {
        DecimalDetector.detect(rows(listOf("1", "10", "20"), listOf("2", "30", "40")), ImportSeparator.SPACES) shouldBe ImportDecimal.POINT
        DecimalDetector.detect(emptyList(), ImportSeparator.TAB) shouldBe ImportDecimal.POINT
    }

    @Test
    fun `S028 RF-7 no mira la primera columna, que es el nombre`() {
        DecimalDetector.detect(rows(listOf("1,5", "10", "20"), listOf("2,5", "30", "40")), ImportSeparator.SEMICOLON) shouldBe
            ImportDecimal.POINT
    }

    @Test
    fun `S028 RF-7 una cabecera o una descripcion con texto no cuenta`() {
        val rows = rows(listOf("Nombre", "Este", "Norte", "Desc"), listOf("1", "440297,988", "4474244,001", "Pozo, nuevo"))

        DecimalDetector.detect(rows, ImportSeparator.SEMICOLON) shouldBe ImportDecimal.COMMA
    }

    @Test
    fun `S028 RF-7 una celda con puntos de miles y coma decimal no cuenta como ninguno de los dos`() {
        val rows = rows(listOf("E4", "440.290,458", "4372210,140"))

        DecimalDetector.detect(rows, ImportSeparator.SEMICOLON) shouldBe ImportDecimal.COMMA
    }

    @Test
    fun `S028 RF-8 con la coma de separador el decimal es siempre el punto`() {
        val rows = rows(listOf("1", "440297,988", "4474244,001"))

        DecimalDetector.detect(rows, ImportSeparator.COMMA) shouldBe ImportDecimal.POINT
    }

    @Test
    fun `S028 RF-7 solo mira las primeras 200 filas`() {
        val many = (1..300).map { DelimitedRow(it, listOf("$it", "10", "20")) } + DelimitedRow(301, listOf("x", "1,5", "2,5"))

        DecimalDetector.detect(many, ImportSeparator.SEMICOLON) shouldBe ImportDecimal.POINT
    }

    @Test
    fun `S028 RF-7 detecta el decimal de cada fichero de ejemplo`() {
        val comma = setOf("etrs89-utm30-windows1252-puntoycoma.txt", "etrs89-utm30-errores.csv")

        ImportFixtures.samples.forEach { name ->
            val bytes = ImportFixtures.bytes(name)
            val text = TextDecoding.decode(bytes, (TextDecoding.probe(bytes) as TextProbe.Text).encoding)
            val separator = DelimitedReader.detectSeparator(text)
            val read = DelimitedReader.rows(StringReader(text), separator).toList()

            DecimalDetector.detect(read, separator) shouldBe if (name in comma) ImportDecimal.COMMA else ImportDecimal.POINT
        }
    }
}
