package es.topophone.core.model.surface

import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** La rampa de colores por cota frente a `fixtures/superficies/contornos/rampa.csv`, calculada aparte (S061/RF-10). */
class ElevationRampTest {

    private val rows: List<List<String>> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/superficies/contornos/rampa.csv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { it.split(',') }
    }

    @Test
    fun `S061 RF-10 cada cota da el color de la referencia`() {
        rows.size shouldBe 16
        for ((min, max, z, argb) in rows) {
            val expected = argb.toLong(16).toInt()
            "%08X".format(ElevationRamp(min.toDouble(), max.toDouble()).argb(z.toDouble())) shouldBe "%08X".format(expected)
        }
    }

    @Test
    fun `S061 RF-10 los extremos son la primera y la ultima parada y fuera del rango satura`() {
        val ramp = ElevationRamp(600.0, 720.0)
        ramp.argb(600.0) shouldBe (0xFF1A237E).toInt()
        ramp.argb(720.0) shouldBe (0xFFE53935).toInt()
        ramp.argb(-1e9) shouldBe (0xFF1A237E).toInt()
        ramp.argb(1e9) shouldBe (0xFFE53935).toInt()
    }

    @Test
    fun `S061 RF-10 con minimo igual al maximo todo es del color del centro y sin dato es transparente`() {
        ElevationRamp(600.0, 600.0).argb(10.0) shouldBe (0xFF43A047).toInt()
        ElevationRamp(600.0, 720.0).argb(Double.NaN) shouldBe 0
    }
}
