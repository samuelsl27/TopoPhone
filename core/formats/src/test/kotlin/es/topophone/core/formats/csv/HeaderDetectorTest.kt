package es.topophone.core.formats.csv

import es.topophone.core.model.point.ImportEncoding
import io.kotest.matchers.shouldBe
import java.io.StringReader
import org.junit.jupiter.api.Test

/** Si la primera fila es una cabecera (S028/RF-9). */
class HeaderDetectorTest {

    private fun firstRow(bytes: ByteArray, encoding: ImportEncoding): List<String> {
        val text = TextDecoding.decode(bytes, encoding)
        return DelimitedReader.rows(StringReader(text), DelimitedReader.detectSeparator(text)).first().cells
    }

    @Test
    fun `S028 RF-9 una fila de nombres de columna es una cabecera`() {
        HeaderDetector.isHeader(listOf("Name", "Easting", "Northing", "Elevation", "Description")) shouldBe true
        HeaderDetector.isHeader(listOf("Punto", "Este", "Norte", "Altura")) shouldBe true
    }

    @Test
    fun `S028 RF-9 una fila con coordenadas numericas no es una cabecera`() {
        HeaderDetector.isHeader(listOf("1", "440297.988", "4474244.001", "646.377", "Esquina")) shouldBe false
        HeaderDetector.isHeader(listOf("P1", "440297,988", "4474244,001")) shouldBe false
    }

    @Test
    fun `S028 RF-9 basta un numero en una de las dos columnas de coordenadas para que no sea cabecera`() {
        HeaderDetector.isHeader(listOf("1", "440297.988", "Norte")) shouldBe false
        HeaderDetector.isHeader(listOf("1", "Este", "4474244.001")) shouldBe false
    }

    @Test
    fun `S028 RF-9 un numero en el nombre o en la altura no cambia nada`() {
        HeaderDetector.isHeader(listOf("1", "Este", "Norte", "12")) shouldBe true
    }

    @Test
    fun `S028 RF-9 mira las columnas asignadas cuando se le dan`() {
        HeaderDetector.isHeader(listOf("1", "Norte", "Este", "12"), listOf(1, 2)) shouldBe true
        HeaderDetector.isHeader(listOf("Name", "Altura", "Este", "440297.988"), listOf(2, 3)) shouldBe false
    }

    @Test
    fun `S028 RF-9 una fila que no llega a las columnas de coordenadas no es una cabecera`() {
        HeaderDetector.isHeader(listOf("Nombre")) shouldBe false
        HeaderDetector.isHeader(emptyList()) shouldBe false
        HeaderDetector.isHeader(listOf("1", "440297.988")) shouldBe false
        HeaderDetector.isHeader(listOf("Nombre", "Este")) shouldBe true
    }

    @Test
    fun `S028 RF-9 los ficheros de ejemplo con cabecera y sin ella se distinguen`() {
        val withoutHeader = setOf("etrs89-utm30-tabulador-sin-cabecera.txt", "etrs89-utm30-espacios-pnezd.txt")

        ImportFixtures.samples.forEach { name ->
            val bytes = ImportFixtures.bytes(name)
            val encoding = (TextDecoding.probe(bytes) as TextProbe.Text).encoding

            HeaderDetector.isHeader(firstRow(bytes, encoding)) shouldBe (name !in withoutHeader)
        }
    }

    @Test
    fun `S028 RF-9 los CSV que escribe S012 llevan cabecera`() {
        listOf("todas_coma_punto.csv", "todas_puntoycoma_coma.csv", "pnezd_coma_punto.csv").forEach { name ->
            HeaderDetector.isHeader(firstRow(ImportFixtures.s012(name), ImportEncoding.UTF8)) shouldBe true
        }
    }
}
