package es.topophone.core.model.format

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El tiempo de promediado y el intervalo se escriben en minutos y segundos (S017/RF-1, RF-16). */
class DurationTextTest {

    @Test
    fun `S017 RF-1 escribe el tiempo en minutos y segundos`() {
        DurationText.format(0) shouldBe "0:00"
        DurationText.format(5) shouldBe "0:05"
        DurationText.format(65) shouldBe "1:05"
        DurationText.format(750) shouldBe "12:30"
        DurationText.format(3600) shouldBe "60:00"
    }

    @Test
    fun `S017 RF-1 lee minutos y segundos`() {
        DurationText.parse("0:05", 1..3600) shouldBe 5
        DurationText.parse("1:05", 1..3600) shouldBe 65
        DurationText.parse("12:30", 1..3600) shouldBe 750
        DurationText.parse("60:00", 1..3600) shouldBe 3600
        DurationText.parse("  2:00 ", 1..3600) shouldBe 120
    }

    @Test
    fun `S017 RF-1 un entero son segundos`() {
        DurationText.parse("5", 1..3600) shouldBe 5
        DurationText.parse("05", 1..3600) shouldBe 5
        DurationText.parse("90", 1..3600) shouldBe 90
        DurationText.parse("3600", 1..3600) shouldBe 3600
    }

    @Test
    fun `S017 RF-1 fuera de rango o mal escrito no vale`() {
        DurationText.parse("0:00", 1..3600).shouldBeNull()
        DurationText.parse("0", 1..3600).shouldBeNull()
        DurationText.parse("60:01", 1..3600).shouldBeNull()
        DurationText.parse("3601", 1..3600).shouldBeNull()
        DurationText.parse("1:60", 1..3600).shouldBeNull()
        DurationText.parse("1:5", 1..3600).shouldBeNull()
        DurationText.parse("1:05:00", 1..3600).shouldBeNull()
        DurationText.parse("-5", 1..3600).shouldBeNull()
        DurationText.parse("5,5", 1..3600).shouldBeNull()
        DurationText.parse("abc", 1..3600).shouldBeNull()
        DurationText.parse("", 1..3600).shouldBeNull()
        DurationText.parse(":30", 1..3600).shouldBeNull()
        DurationText.parse("99999999999", 1..3600).shouldBeNull()
    }

    @Test
    fun `S017 RF-1 escribir y leer es una ida y vuelta`() {
        for (seconds in listOf(1, 59, 60, 61, 599, 3599, 3600)) {
            DurationText.parse(DurationText.format(seconds), 1..3600) shouldBe seconds
        }
    }
}
