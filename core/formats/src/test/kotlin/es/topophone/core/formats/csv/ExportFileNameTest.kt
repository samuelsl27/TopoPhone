package es.topophone.core.formats.csv

import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

/** Nombre propuesto para el fichero exportado (S012/RF-19). */
class ExportFileNameTest {

    private val at = Instant.parse("2026-10-18T18:09:31Z")
    private val madrid = ZoneId.of("Europe/Madrid")

    @Test
    fun `S012 RF-19 el nombre lleva el proyecto, el formato y la fecha y hora locales`() {
        ExportFileName.of("Calle Mayor 12", "PENZD", at, madrid) shouldBe "Calle Mayor 12_PENZD_20261018-2009.csv"
        ExportFileName.of("Puerto", "Todas", at, ZoneId.of("Atlantic/Canary")) shouldBe "Puerto_Todas_20261018-1909.csv"
    }

    @Test
    fun `S012 RF-19 los caracteres que no valen en un nombre de fichero se cambian por guiones bajos`() {
        ExportFileName.of("Obra: \"A/B\" <1>|*?\\", "PNEZD", at, madrid) shouldBe "Obra_ _A_B_ _1______PNEZD_20261018-2009.csv"
        ExportFileName.of("línea\tuno\nfin", "PNEZD", at, madrid) shouldBe "línea_uno_fin_PNEZD_20261018-2009.csv"
    }

    @Test
    fun `S012 RF-19 sin puntos ni espacios en los extremos y con un nombre si queda vacio`() {
        ExportFileName.of("  . obra .  ", "PENZD", at, madrid) shouldBe "obra_PENZD_20261018-2009.csv"
        ExportFileName.of(" ... ", "PENZD", at, madrid) shouldBe "TopoPhone_PENZD_20261018-2009.csv"
    }

    @Test
    fun `S012 RF-19 un nombre largo se corta en 100 caracteres sin partir ninguno`() {
        ExportFileName.of("é".repeat(150), "PENZD", at, madrid) shouldBe "é".repeat(100) + "_PENZD_20261018-2009.csv"
        val globe = "🌍"
        ExportFileName.of(globe.repeat(120), "PENZD", at, madrid) shouldBe globe.repeat(100) + "_PENZD_20261018-2009.csv"
    }
}
