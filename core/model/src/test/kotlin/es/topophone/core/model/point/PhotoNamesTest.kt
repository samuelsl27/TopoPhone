package es.topophone.core.model.point

import io.kotest.matchers.shouldBe
import java.time.Instant
import org.junit.jupiter.api.Test

class PhotoNamesTest {

    private val at = Instant.parse("2026-10-31T09:05:07Z").toEpochMilli()

    @Test
    fun `S017b RF-11 el nombre lleva el punto y la fecha en UTC`() {
        PhotoNames.of("12", at, emptyList()) shouldBe "12_20261031-090507.jpg"
    }

    @Test
    fun `S017b RF-23 dos fotos en el mismo segundo tienen nombres distintos`() {
        val first = PhotoNames.of("12", at, emptyList())
        val second = PhotoNames.of("12", at + 400, listOf(first))
        val third = PhotoNames.of("12", at + 800, listOf(first, second))

        second shouldBe "12_20261031-090507_2.jpg"
        third shouldBe "12_20261031-090507_3.jpg"
    }

    @Test
    fun `S017b RF-21 la misma foto anadida dos veces cuenta como dos`() {
        val existing = listOf(PhotoNames.of("V-1", at, emptyList()))
        PhotoNames.of("V-1", at, existing) shouldBe "V-1_20261031-090507_2.jpg"
    }

    @Test
    fun `S017b RF-27 las estadisticas sin fotos son cero`() {
        PhotoStats.NONE shouldBe PhotoStats(0, 0L)
    }
}
