package es.topophone.data.point

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Las frases NMEA de un promediado largo van en tramos que caben en una fila del cursor de Android (S017/RF-12; plan A6). */
class NmeaChunksTest {

    private fun sentences(count: Int) =
        (0 until count).joinToString("\n") { "\$GNGGA,$it,4025.0080000,N,00342.2280000,W,4,28,0.5,650.123,M,51.234,M,1.0,0001*41" }

    @Test
    fun `S017 RF-12 un texto corto es un solo tramo`() {
        NmeaChunks.split("\$GNGGA,1*00\n\$GNGST,1*00", maxChars = 100) shouldBe listOf("\$GNGGA,1*00\n\$GNGST,1*00")
        NmeaChunks.split("", maxChars = 100) shouldBe listOf("")
    }

    @Test
    fun `S017 RF-12 se parte siempre tras un salto de linea y ningun tramo pasa del maximo`() {
        val text = sentences(2_000)

        val chunks = NmeaChunks.split(text, maxChars = 5_000)

        chunks.size shouldBeGreaterThan 30
        chunks.forEach { it.length shouldBeLessThanOrEqual 5_000 }
        // Cada tramo acaba en una frase entera, y todos menos el último, en su salto de línea.
        chunks.dropLast(1).forEach { it.endsWith("*41\n") shouldBe true }
        chunks.forEach { chunk -> chunk.trimEnd('\n').lines().forEach { it.startsWith("\$GNGGA,") shouldBe true } }
    }

    @Test
    fun `S017 RF-12 unir los tramos devuelve el texto exacto`() {
        for (count in listOf(1, 2, 59, 60, 61, 2_000)) {
            val text = sentences(count)
            NmeaChunks.join(NmeaChunks.split(text, maxChars = 5_000)) shouldBe text
        }
    }

    @Test
    fun `S017 RF-12 una frase mas larga que el maximo se parte donde haga falta sin perder nada`() {
        val text = "x".repeat(250)

        val chunks = NmeaChunks.split(text, maxChars = 100)

        chunks shouldHaveSize 3
        NmeaChunks.join(chunks) shouldBe text
    }

    @Test
    fun `S017 RF-12 el maximo de las filas son 256000 caracteres, mucho menos que la ventana del cursor`() {
        NmeaChunks.MAX_CHARS shouldBe 256_000
    }
}
