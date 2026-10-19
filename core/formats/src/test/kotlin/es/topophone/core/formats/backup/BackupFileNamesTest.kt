package es.topophone.core.formats.backup

import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

/** Nombres propuestos de la copia, del paquete, de las copias automáticas y de las de antes de restaurar (S013/RF-4, RF-7, RF-19). */
class BackupFileNamesTest {

    private val at = Instant.parse("2026-10-19T06:02:31Z")
    private val madrid = ZoneId.of("Europe/Madrid")

    @Test
    fun `S013 RF-4 la copia se llama TopoPhone con la fecha y hora locales`() {
        BackupFileNames.backup(at, madrid) shouldBe "TopoPhone_20261019-0802.zip"
        BackupFileNames.backup(at, ZoneId.of("Atlantic/Canary")) shouldBe "TopoPhone_20261019-0702.zip"
    }

    @Test
    fun `S013 RF-4 el paquete lleva el proyecto con las reglas de S012`() {
        BackupFileNames.projectPackage("Calle Mayor 12", at, madrid) shouldBe "Calle Mayor 12_20261019-0802.topoproj"
        BackupFileNames.projectPackage("Obra: \"A/B\"", at, madrid) shouldBe "Obra_ _A_B__20261019-0802.topoproj"
        BackupFileNames.projectPackage(" .. ", at, madrid) shouldBe "TopoPhone_20261019-0802.topoproj"
        BackupFileNames.projectPackage("é".repeat(150), at, madrid) shouldBe "é".repeat(100) + "_20261019-0802.topoproj"
    }

    @Test
    fun `S013 RF-7 y RF-19 las copias automaticas y las de antes de restaurar tienen su nombre`() {
        BackupFileNames.automatic(at, madrid) shouldBe "TopoPhone_auto_20261019-0802.zip"
        BackupFileNames.beforeRestore(at, madrid) shouldBe "TopoPhone_antes-de-restaurar_20261019-0802.zip"
    }

    @Test
    fun `S013 RF-19 solo las copias automaticas se reconocen como tales`() {
        BackupFileNames.isAutomatic("TopoPhone_auto_20261019-0802.zip") shouldBe true
        // Algunos proveedores añaden " (1)" si el nombre ya existe.
        BackupFileNames.isAutomatic("TopoPhone_auto_20261019-0802 (1).zip") shouldBe true
        BackupFileNames.isAutomatic("TopoPhone_20261019-0802.zip") shouldBe false
        BackupFileNames.isAutomatic("TopoPhone_antes-de-restaurar_20261019-0802.zip") shouldBe false
        BackupFileNames.isAutomatic("TopoPhone_auto_20261019-0802.zip.txt") shouldBe false
        BackupFileNames.isAutomatic("mis fotos.zip") shouldBe false
    }

    @Test
    fun `S014 RF-16 el informe de sistema se llama TopoPhone_informe con la fecha y hora locales`() {
        BackupFileNames.systemReport(at, madrid) shouldBe "TopoPhone_informe_20261019-0802.zip"
        BackupFileNames.isAutomatic(BackupFileNames.systemReport(at, madrid)) shouldBe false
    }
}
