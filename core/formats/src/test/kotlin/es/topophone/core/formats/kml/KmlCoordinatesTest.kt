package es.topophone.core.formats.kml

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import org.junit.jupiter.api.Test

class KmlCoordinatesTest {

    private fun parse(text: String): KmlVertices = KmlCoordinates.parse(text).shouldBeInstanceOf<KmlVertices>()

    @Test
    fun `S031 RF-7 las tuplas son longitud y latitud separadas por comas y entre si por espacios`() {
        val v = parse("-3.7038,40.4168 -3.7028,40.4178")

        v.size shouldBe 2
        v.lon.toList() shouldBe listOf(-3.7038, -3.7028)
        v.lat.toList() shouldBe listOf(40.4168, 40.4178)
        v.alt shouldBe null
    }

    @Test
    fun `S031 RF-7 las tuplas pueden separarse con saltos de linea y tabuladores y con espacios en los extremos`() {
        val v = parse("\n   1,2\n\t3,4\r\n5,6  \n")

        v.lon.toList() shouldBe listOf(1.0, 3.0, 5.0)
        v.lat.toList() shouldBe listOf(2.0, 4.0, 6.0)
    }

    @Test
    fun `S031 RF-13 la altitud solo cuenta si todos los vertices la traen`() {
        parse("1,2,10 3,4,20").alt?.toList() shouldBe listOf(10.0, 20.0)
        parse("1,2 3,4,20").alt shouldBe null
        parse("1,2,10 3,4").alt shouldBe null
    }

    @Test
    fun `S031 RF-13 una altitud de cero es una altitud`() {
        parse("1,2,0").alt?.toList() shouldBe listOf(0.0)
    }

    @Test
    fun `S031 RF-7 un cuarto valor de una tupla se ignora`() {
        val v = parse("1,2,3,4")

        v.size shouldBe 1
        v.alt?.toList() shouldBe listOf(3.0)
    }

    @Test
    fun `S031 RF-7 un texto sin tuplas es una lista vacia`() {
        parse("").size shouldBe 0
        parse("  \n\t ").size shouldBe 0
    }

    @Test
    fun `S031 RF-9 una tupla ilegible deja ilegible toda la lista`() {
        KmlCoordinates.parse("abc,def") shouldBe null
        KmlCoordinates.parse("1,2 3") shouldBe null
        KmlCoordinates.parse("1") shouldBe null
        KmlCoordinates.parse("1,2,x") shouldBe null
        KmlCoordinates.parse("1,2,") shouldBe null
        KmlCoordinates.parse(",2") shouldBe null
        KmlCoordinates.parse("1, 2") shouldBe null
    }

    @Test
    fun `S031 RF-9 solo valen numeros decimales corrientes`() {
        KmlCoordinates.parse("NaN,1") shouldBe null
        KmlCoordinates.parse("Infinity,1") shouldBe null
        KmlCoordinates.parse("1.5f,2") shouldBe null
        KmlCoordinates.parse("1.5d,2") shouldBe null
        KmlCoordinates.parse("0x10,1") shouldBe null
        KmlCoordinates.parse("--1,2") shouldBe null
        KmlCoordinates.parse("1.2.3,2") shouldBe null
        KmlCoordinates.parse("1e,2") shouldBe null
    }

    @Test
    fun `S031 RF-7 se admiten signos, punto inicial y notacion cientifica`() {
        val v = parse("+1.5,-2 .5,5. 1e-3,2E2")

        v.lon.toList() shouldBe listOf(1.5, 0.5, 0.001)
        v.lat.toList() shouldBe listOf(-2.0, 5.0, 200.0)
    }

    @Test
    fun `S031 RF-7 la longitud y la latitud conservan toda su precision`() {
        val v = parse("-3.703800000000123,40.416800000000456")

        v.lon[0] shouldBe -3.703800000000123
        v.lat[0] shouldBe 40.416800000000456
    }

    @Test
    fun `S031 RF-7 una linea de cien mil vertices se lee en menos de un segundo`() {
        val text = buildString { repeat(100_000) { append(-3.7 + it * 1e-7).append(',').append(40.4 + it * 1e-7).append(",600.5\n") } }

        val elapsed = measureTime { parse(text).size shouldBe 100_000 }

        (elapsed < 1.seconds) shouldBe true
    }
}
