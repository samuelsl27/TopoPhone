package es.topophone.app.ui.offline

import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.Test

/** Cómo se escriben los tamaños, las teselas y las distancias de las zonas sin conexión (S037/RF-11, RNF-T7). */
class OfflineFormatTest {

    private val es = Locale.forLanguageTag("es-ES")
    private val en = Locale.US

    private fun mb(value: Double) = (value * 1024 * 1024).toLong()

    @Test
    fun `S037 RF-11 el tamano sale en KB, MB o GB con el separador del idioma`() {
        OfflineFormat.bytes(25L * 1024, es) shouldBe "25 KB"
        OfflineFormat.bytes(mb(1.5), es) shouldBe "1,5 MB"
        OfflineFormat.bytes(mb(1.5), en) shouldBe "1.5 MB"
        OfflineFormat.bytes(mb(123.4), es) shouldBe "123 MB"
        OfflineFormat.bytes(2L * 1024 * 1024 * 1024, es) shouldBe "2 GB"
        OfflineFormat.bytes(mb(1536.0), es) shouldBe "1,5 GB"
    }

    @Test
    fun `S037 RF-11 los tamanos pequenos y el cero no se redondean a nada raro`() {
        OfflineFormat.bytes(0, es) shouldBe "0 KB"
        OfflineFormat.bytes(500, es) shouldBe "1 KB"
        OfflineFormat.bytes(mb(0.5), es) shouldBe "512 KB"
    }

    @Test
    fun `S037 RF-11 las teselas de cinco cifras salen con separador de miles`() {
        OfflineFormat.count(46_500, es) shouldBe "46.500"
        OfflineFormat.count(46_500, en) shouldBe "46,500"
        OfflineFormat.count(12, es) shouldBe "12"
    }

    @Test
    fun `S037 RF-3 las distancias salen en metros hasta 1 km y en kilometros desde ahi`() {
        OfflineFormat.distance(500.0, es) shouldBe "500 m"
        OfflineFormat.distance(100.0, es) shouldBe "100 m"
        OfflineFormat.distance(1_000.0, es) shouldBe "1 km"
        OfflineFormat.distance(2_500.0, es) shouldBe "2,5 km"
        OfflineFormat.distance(2_500.0, en) shouldBe "2.5 km"
        OfflineFormat.distance(20_000.0, es) shouldBe "20 km"
    }
}
