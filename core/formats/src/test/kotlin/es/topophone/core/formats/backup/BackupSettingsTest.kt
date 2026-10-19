package es.topophone.core.formats.backup

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** `settings.json` de la copia: los ajustes con su tipo, para volver exactamente igual (S013/RF-2, RF-8). */
class BackupSettingsTest {

    private val settings = BackupSettings(
        stores = mapOf(
            "settings" to mapOf("theme_mode" to SettingValue.Text("SUN")),
            "collector" to mapOf(
                "collector_averaging_seconds" to SettingValue.Int32(20),
                "collector_fix_only" to SettingValue.Flag(true),
                "collector_horizontal_limit_m" to SettingValue.Float64(0.1 + 0.2),
            ),
            "export" to mapOf("custom_columns" to SettingValue.Texts(setOf("NAME", "LATITUDE"))),
            "other" to mapOf("big" to SettingValue.Int64(9_007_199_254_740_993L), "small" to SettingValue.Float32(0.1f)),
        ),
        appLanguage = "es",
    )

    @Test
    fun `S013 RF-2 los ajustes vuelven con el mismo tipo y el mismo valor`() {
        BackupSettingsJson.decode(BackupSettingsJson.encode(settings)) shouldBe settings
    }

    @Test
    fun `S013 RF-2 un decimal vuelve bit a bit y un entero largo sin perder digitos`() {
        val back = BackupSettingsJson.decode(BackupSettingsJson.encode(settings))!!

        (back.stores.getValue("collector").getValue("collector_horizontal_limit_m") as SettingValue.Float64).value shouldBe
            0.30000000000000004
        (back.stores.getValue("other").getValue("big") as SettingValue.Int64).value shouldBe 9_007_199_254_740_993L
    }

    @Test
    fun `S013 RF-2 el fichero se puede leer y lleva el idioma`() {
        val text = BackupSettingsJson.encode(settings)

        text shouldContain "\"theme_mode\":{\"string\":\"SUN\"}"
        text shouldContain "\"collector_averaging_seconds\":{\"int\":20}"
        text shouldContain "\"appLanguage\":\"es\""
    }

    @Test
    fun `S013 RF-12 unos ajustes que no se entienden no se aplican`() {
        BackupSettingsJson.decode("{no es json") shouldBe null
        BackupSettingsJson.decode("""{"stores":{"collector":{"x":{"int":"veinte"}}},"appLanguage":""}""") shouldBe null
        BackupSettingsJson.decode("""{"stores":[],"appLanguage":""}""") shouldBe null
    }

    @Test
    fun `S013 RF-14 un tipo desconocido de una version futura se ignora`() {
        val text = """{"stores":{"map":{"map_basemap":{"string":"PNOA"},"nuevo":{"color":"#fff"}}},"appLanguage":""}"""

        BackupSettingsJson.decode(text) shouldBe
            BackupSettings(mapOf("map" to mapOf("map_basemap" to SettingValue.Text("PNOA"))), appLanguage = "")
    }
}
