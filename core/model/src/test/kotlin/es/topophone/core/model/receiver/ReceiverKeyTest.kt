package es.topophone.core.model.receiver

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La clave de un receptor: dirección Bluetooth, `replay` o `wifi:<id>` (S021/RF-5, RF-18). */
class ReceiverKeyTest {

    @Test
    fun `S021 RF-5 la clave de un receptor Wi-Fi lleva su identidad y se lee de vuelta`() {
        val key = ReceiverKey.wifi("3f2a-uuid")

        key shouldBe "wifi:3f2a-uuid"
        ReceiverKey.isWifi(key) shouldBe true
        ReceiverKey.wifiId(key) shouldBe "3f2a-uuid"
    }

    @Test
    fun `S021 RF-18 una direccion Bluetooth, replay y una clave vacia no son claves Wi-Fi`() {
        for (key in listOf("00:11:22:AA:BB:CC", "replay", "", "wifi", "WIFI:abc")) {
            ReceiverKey.isWifi(key) shouldBe false
            ReceiverKey.wifiId(key).shouldBeNull()
        }
        ReceiverKey.wifiId("wifi:").shouldBeNull() // sin identidad
    }

    @Test
    fun `S021 RF-18 la identidad no cambia con la direccion ni con el puerto`() {
        val before = WifiReceiver("id-1", "RS3", "192.168.0.20", 9001, null)
        val edited = before.copy(host = "192.168.42.1", positionPort = 9002, correctionsPort = 9003, name = "RS3 campo")

        ReceiverKey.of(before) shouldBe ReceiverKey.of(edited)
        ReceiverKey.of(before) shouldBe "wifi:id-1"
    }

    @Test
    fun `S022 RF-1 las claves del movil y del virtual son fijas y no son de otro tipo`() {
        ReceiverKey.PHONE shouldBe "phone"
        ReceiverKey.VIRTUAL shouldBe "virtual"
        ReceiverKey.isPhone("phone") shouldBe true
        ReceiverKey.isVirtual("virtual") shouldBe true
        for (key in listOf("00:11:22:AA:BB:CC", "replay", "", "wifi:phone", "Phone", "virtual ")) {
            ReceiverKey.isPhone(key) shouldBe false
            ReceiverKey.isVirtual(key) shouldBe false
        }
        ReceiverKey.isWifi("phone") shouldBe false
        ReceiverKey.isWifi("virtual") shouldBe false
    }

    @Test
    fun `S022 RF-12 solo el movil y el virtual son fuentes sin Bluetooth ni Wi-Fi`() {
        ReceiverKey.isBuiltIn("phone") shouldBe true
        ReceiverKey.isBuiltIn("virtual") shouldBe true
        ReceiverKey.isBuiltIn("wifi:abc") shouldBe false
        ReceiverKey.isBuiltIn("00:11:22:AA:BB:CC") shouldBe false
    }
}
