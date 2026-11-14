package es.topophone.app.ui.offline

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Qué red hay para descargar (S037/RF-13, RF-14, RNF-T4): solo cuenta una red con internet comprobado, nunca la Wi-Fi sin salida del receptor. */
class DeviceInfoTest {

    @Test
    fun `S037 RF-13 sin ninguna red no hay internet`() {
        NetworkKinds.of(hasNetwork = false, hasInternet = false, validated = false, cellular = false) shouldBe NetworkKind.NONE
    }

    @Test
    fun `S037 RNF-T4 una Wi-Fi sin salida a internet, como la del receptor, no cuenta`() {
        NetworkKinds.of(hasNetwork = true, hasInternet = false, validated = false, cellular = false) shouldBe NetworkKind.NONE
        NetworkKinds.of(hasNetwork = true, hasInternet = true, validated = false, cellular = false) shouldBe NetworkKind.NONE
    }

    @Test
    fun `S037 RF-14 los datos moviles con internet comprobado son MOBILE`() {
        NetworkKinds.of(hasNetwork = true, hasInternet = true, validated = true, cellular = true) shouldBe NetworkKind.MOBILE
    }

    @Test
    fun `S037 RF-13 una Wi-Fi con internet comprobado es una red sin tarifa de datos`() {
        NetworkKinds.of(hasNetwork = true, hasInternet = true, validated = true, cellular = false) shouldBe NetworkKind.UNMETERED
    }

    @Test
    fun `S037 RF-12 el espacio libre de prueba se puede fijar para las comprobaciones previas`() {
        FakeStorageInfo(12_345L).freeBytes() shouldBe 12_345L
        FakeNetworkInfo(NetworkKind.MOBILE).kind() shouldBe NetworkKind.MOBILE
    }
}
