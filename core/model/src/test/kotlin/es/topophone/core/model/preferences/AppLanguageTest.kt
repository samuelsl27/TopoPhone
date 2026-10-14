package es.topophone.core.model.preferences

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Idiomas de la app (S001/RF-16). */
class AppLanguageTest {

    @Test
    fun `S001 RF-16 hay tres opciones de idioma en el orden Sistema, Espanol e English`() {
        AppLanguage.entries shouldBe listOf(AppLanguage.SYSTEM, AppLanguage.SPANISH, AppLanguage.ENGLISH)
    }

    @Test
    fun `S001 RF-16 el idioma por defecto es Sistema`() {
        AppLanguage.DEFAULT shouldBe AppLanguage.SYSTEM
    }

    @Test
    fun `S001 RF-16 cada idioma se guarda con su etiqueta BCP 47 y Sistema sin etiqueta`() {
        AppLanguage.SYSTEM.languageTags shouldBe ""
        AppLanguage.SPANISH.languageTags shouldBe "es"
        AppLanguage.ENGLISH.languageTags shouldBe "en"
    }

    @ParameterizedTest
    @CsvSource(
        "'', SYSTEM",
        "es, SPANISH",
        "es-ES, SPANISH",
        "en, ENGLISH",
        "en-GB, ENGLISH",
        "'es,en', SPANISH",
        "fr, SYSTEM",
        "'  ', SYSTEM",
    )
    fun `S001 RF-16 las etiquetas guardadas se interpretan como uno de los tres idiomas`(tags: String, expected: AppLanguage) {
        AppLanguage.fromLanguageTags(tags) shouldBe expected
    }
}
