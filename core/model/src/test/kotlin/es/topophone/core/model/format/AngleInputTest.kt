package es.topophone.core.model.format

import es.topophone.core.model.project.AngularUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** Latitud y longitud escritas en la unidad angular del proyecto (S011/RF-17). */
class AngleInputTest {

    private val lat = 40.0 + 25.0 / 60 + 7.24408 / 3600
    private val lon = -(3.0 + 42.0 / 60 + 13.64444 / 3600)

    @Test
    fun `S011 RF-17 en DMS acepta espacios, simbolos y la letra del hemisferio`() {
        AngleInput.parse("40 25 7,24408", AngularUnit.DMS, AngleAxis.LATITUDE).shouldNotBeNull() shouldBe (lat plusOrMinus 1e-12)
        AngleInput.parse("40°25'07.24408\" N", AngularUnit.DMS, AngleAxis.LATITUDE).shouldNotBeNull() shouldBe (lat plusOrMinus 1e-12)
        AngleInput.parse("003° 42′ 13,64444″ W", AngularUnit.DMS, AngleAxis.LONGITUDE).shouldNotBeNull() shouldBe (lon plusOrMinus 1e-12)
        AngleInput.parse("3 42 13,64444 O", AngularUnit.DMS, AngleAxis.LONGITUDE).shouldNotBeNull() shouldBe (lon plusOrMinus 1e-12)
        AngleInput.parse("W 3 42 13,64444", AngularUnit.DMS, AngleAxis.LONGITUDE).shouldNotBeNull() shouldBe (lon plusOrMinus 1e-12)
        AngleInput.parse("-3 42 13,64444", AngularUnit.DMS, AngleAxis.LONGITUDE).shouldNotBeNull() shouldBe (lon plusOrMinus 1e-12)
        AngleInput.parse("28 30 0 s", AngularUnit.DMS, AngleAxis.LATITUDE) shouldBe -28.5
    }

    @Test
    fun `S011 RF-17 en DMS se pueden omitir los segundos o los minutos`() {
        AngleInput.parse("40 25,5", AngularUnit.DMS, AngleAxis.LATITUDE).shouldNotBeNull() shouldBe (40.425 plusOrMinus 1e-12)
        AngleInput.parse("40", AngularUnit.DMS, AngleAxis.LATITUDE) shouldBe 40.0
        AngleInput.parse("40º 25′", AngularUnit.DMS, AngleAxis.LATITUDE).shouldNotBeNull() shouldBe (40.0 + 25.0 / 60 plusOrMinus 1e-12)
    }

    @Test
    fun `S011 RF-17 en grados decimales y en gon, con signo o letra`() {
        AngleInput.parse("40,4168012345°", AngularUnit.DECIMAL_DEGREES, AngleAxis.LATITUDE) shouldBe 40.4168012345
        AngleInput.parse("-3.7037", AngularUnit.DECIMAL_DEGREES, AngleAxis.LONGITUDE) shouldBe -3.7037
        AngleInput.parse("3,7037 W", AngularUnit.DECIMAL_DEGREES, AngleAxis.LONGITUDE) shouldBe -3.7037
        AngleInput.parse("44,9075568 gon", AngularUnit.GON, AngleAxis.LATITUDE).shouldNotBeNull() shouldBe (40.41680112 plusOrMinus 1e-12)
        AngleInput.parse("-4,1153 g", AngularUnit.GON, AngleAxis.LONGITUDE).shouldNotBeNull() shouldBe (-3.70377 plusOrMinus 1e-12)
    }

    @Test
    fun `S011 RF-17 lee lo que escribe la interfaz en cada unidad e idioma`() {
        val failures = mutableListOf<String>()
        for (locale in listOf(Locale.forLanguageTag("es-ES"), Locale.US)) {
            for (unit in AngularUnit.entries) {
                for (value in listOf(40.416801234567, -3.703712345678, 28.4636, -16.2518, 0.0, 89.9999999)) {
                    val asLat = AngleFormat.latitude(value, unit, locale)
                    val asLon = AngleFormat.longitude(value, unit, locale)
                    val backLat = AngleInput.parse(asLat, unit, AngleAxis.LATITUDE)
                    val backLon = AngleInput.parse(asLon, unit, AngleAxis.LONGITUDE)
                    // La interfaz da 0,000001″ o 1e-10 de grado o gon: menos de 3e-10°.
                    if (backLat == null || kotlin.math.abs(backLat - value) > 3e-10) failures += "$unit $locale $asLat → $backLat"
                    if (backLon == null || kotlin.math.abs(backLon - value) > 3e-10) failures += "$unit $locale $asLon → $backLon"
                }
            }
        }
        failures shouldBe emptyList()
    }

    @Test
    fun `S011 RF-17 lo que no es un angulo valido no vale`() {
        val dms = listOf(
            "", "N", "abc", "40 60 0", "40 25 60", "40,5 25 10", "40 25,5 10", "91", "40 25 0 E", "-40 S", "40 25 0 N S", "40 25 0 12",
        )
        dms.forEach { AngleInput.parse(it, AngularUnit.DMS, AngleAxis.LATITUDE).shouldBeNull() }
        AngleInput.parse("181", AngularUnit.DECIMAL_DEGREES, AngleAxis.LONGITUDE).shouldBeNull()
        AngleInput.parse("3 N", AngularUnit.DECIMAL_DEGREES, AngleAxis.LONGITUDE).shouldBeNull()
        AngleInput.parse("100,1 gon", AngularUnit.GON, AngleAxis.LATITUDE).shouldBeNull()
        AngleInput.parse("40 25", AngularUnit.DECIMAL_DEGREES, AngleAxis.LATITUDE).shouldBeNull()
    }
}
