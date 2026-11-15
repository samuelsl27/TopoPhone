package es.topophone.core.model.guide

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El perfil de equipo y los tipos de la guía (S043/RF-4, RF-18, RF-19). */
class GuideModelTest {

    @Test
    fun `S043 RF-18 un perfil sin ningun dato esta en blanco y con uno cualquiera ya no`() {
        EquipmentProfile.EMPTY.isBlank shouldBe true
        EquipmentProfile("  ", "", " ").isBlank shouldBe true
        EquipmentProfile(model = "RS3").isBlank shouldBe false
        EquipmentProfile(serial = "A1").isBlank shouldBe false
        EquipmentProfile(firmware = "32.0").isBlank shouldBe false
    }

    @Test
    fun `S043 RF-18 recortar quita los espacios de los extremos de cada campo y no los de en medio`() {
        EquipmentProfile(" Reach RS3 ", "  RS3-0123 ", "\t32.0 ").trimmed() shouldBe EquipmentProfile("Reach RS3", "RS3-0123", "32.0")
    }

    @Test
    fun `S043 RF-4 un paso es comprobable solo si lleva comprobaciones`() {
        ConfigStep("uno").checkable shouldBe false
        ConfigStep("dos", listOf(CheckId.GGA_RATE)).checkable shouldBe true
    }

    @Test
    fun `S043 RF-9 el resultado de una comprobacion lleva su paso y sus causas`() {
        val check = ReceiverCheck(CheckId.GGA_RATE, CheckStatus.WARNING, "0,5 Hz", listOf(CheckCause.GGA_BELOW_1HZ), "ntrip_rover_nmea")

        check.stepId shouldBe "ntrip_rover_nmea"
        check.causes shouldBe listOf(CheckCause.GGA_BELOW_1HZ)
        ReceiverCheck(CheckId.SOLUTION, CheckStatus.OK).causes shouldBe emptyList()
    }
}
