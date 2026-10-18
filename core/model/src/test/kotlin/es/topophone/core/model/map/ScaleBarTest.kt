package es.topophone.core.model.map

import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.floats.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** Barra de escala del mapa en las unidades del proyecto y ajustes del mapa (S009/RF-6, RF-8, RF-10, RF-12). */
class ScaleBarTest {

    private val es = Locale.forLanguageTag("es-ES")

    @Test
    fun `S009 RF-10 la barra es la mayor longitud redonda que cabe en el ancho maximo`() {
        // 0,3 m por píxel: 100 px son 30 m, así que 20 m.
        val bar = ScaleBar.choose(metresPerPixel = 0.3, maxWidthPx = 100f, unit = LinearUnit.METRE)

        bar.value shouldBe 20.0
        bar.widthPx.toDouble() shouldBe (66.667 plusOrMinus 0.001)
    }

    @Test
    fun `S009 RF-10 la barra va en pies o en pies topograficos si el proyecto los usa`() {
        // 100 px son 98,4 ft: 50 ft.
        val feet = ScaleBar.choose(metresPerPixel = 0.3, maxWidthPx = 100f, unit = LinearUnit.INTERNATIONAL_FOOT)
        feet.value shouldBe 50.0
        feet.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        feet.widthPx.toDouble() shouldBe (50.0 * 0.3048 / 0.3 plusOrMinus 1e-4)

        val usFeet = ScaleBar.choose(metresPerPixel = 3.0, maxWidthPx = 100f, unit = LinearUnit.US_SURVEY_FOOT)
        usFeet.value shouldBe 500.0
    }

    @Test
    fun `S009 RF-10 las longitudes son 1, 2 o 5 por una potencia de 10 y nunca pasan del ancho`() {
        var metresPerPixel = 0.005
        while (metresPerPixel < 50_000.0) {
            LinearUnit.entries.forEach { unit ->
                val bar = ScaleBar.choose(metresPerPixel, 100f, unit)
                bar.widthPx shouldBeLessThanOrEqual 100f
                val mantissa = bar.value / Math.pow(10.0, Math.floor(Math.log10(bar.value)))
                setOf(1, 2, 5).any { kotlin.math.abs(mantissa - it) < 1e-9 } shouldBe true
            }
            metresPerPixel *= 1.37
        }
    }

    @Test
    fun `S009 RF-12 con el zoom maximo la barra baja a 1 m o menos`() {
        // A 27° N y zoom 22, un dp mide 1,66 cm del terreno (WebMercatorTest).
        val bar = ScaleBar.choose(metresPerPixel = 0.0166, maxWidthPx = 100f, unit = LinearUnit.METRE)

        bar.value shouldBeLessThanOrEqual 1.0
    }

    @Test
    fun `S009 RF-10 el valor se escribe sin ceros de sobra y con el separador del idioma`() {
        ScaleBar.choose(0.3, 100f, LinearUnit.METRE).valueText(es) shouldBe "20"
        ScaleBar.choose(0.006, 100f, LinearUnit.METRE).valueText(es) shouldBe "0,5"
        ScaleBar.choose(0.0006, 100f, LinearUnit.METRE).valueText(Locale.US) shouldBe "0.05"
        ScaleBar.choose(300.0, 100f, LinearUnit.METRE).valueText(es) shouldBe "20.000"
    }

    @Test
    fun `S009 RF-6 RF-8 por defecto el mapa del IGN sin Catastro, entre cuatro mapas base`() {
        MapSettings() shouldBe MapSettings(basemap = Basemap.IGN_VECTOR, cadastre = false)
        Basemap.entries shouldBe listOf(Basemap.IGN_VECTOR, Basemap.OSM_VECTOR, Basemap.PNOA_ORTHOPHOTO, Basemap.NONE)
    }
}
