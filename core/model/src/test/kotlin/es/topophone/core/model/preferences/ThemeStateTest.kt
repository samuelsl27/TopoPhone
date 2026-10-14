package es.topophone.core.model.preferences

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Temas y modo sol (S001/RF-9, S001/RF-12, S001/RF-13, S001/RF-15). */
class ThemeStateTest {

    @Test
    fun `S001 RF-9 hay cuatro temas en el orden Sistema, Claro, Oscuro y Sol`() {
        ThemeMode.entries shouldBe listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SUN)
    }

    @Test
    fun `S001 RF-9 el tema por defecto es Sistema`() {
        ThemeState.DEFAULT.mode shouldBe ThemeMode.SYSTEM
        ThemeState.DEFAULT.beforeSun shouldBe ThemeMode.SYSTEM
    }

    @ParameterizedTest
    @EnumSource(ThemeMode::class, names = ["SYSTEM", "LIGHT", "DARK"])
    fun `S001 RF-12 activar el modo sol recuerda el tema que estaba activo`(previous: ThemeMode) {
        val state = ThemeState.DEFAULT.select(previous).toggleSun()

        state.mode shouldBe ThemeMode.SUN
        state.beforeSun shouldBe previous
    }

    @ParameterizedTest
    @EnumSource(ThemeMode::class, names = ["SYSTEM", "LIGHT", "DARK"])
    fun `S001 RF-12 desactivar el modo sol vuelve al tema que estaba activo antes`(previous: ThemeMode) {
        val state = ThemeState.DEFAULT.select(previous).toggleSun().toggleSun()

        state.mode shouldBe previous
    }

    @Test
    fun `S001 RF-12 alternar dos veces seguidas deja el estado como estaba`() {
        val dark = ThemeState.DEFAULT.select(ThemeMode.DARK)

        dark.toggleSun().toggleSun() shouldBe dark
    }

    @Test
    fun `S001 RF-12 elegir Sol en Ajustes tambien recuerda el tema anterior`() {
        val state = ThemeState.DEFAULT.select(ThemeMode.LIGHT).select(ThemeMode.SUN)

        state shouldBe ThemeState(mode = ThemeMode.SUN, beforeSun = ThemeMode.LIGHT)
        state.toggleSun().mode shouldBe ThemeMode.LIGHT
    }

    @Test
    fun `S001 RF-12 elegir Sol estando ya en Sol no pierde el tema anterior`() {
        val state = ThemeState.DEFAULT.select(ThemeMode.DARK).toggleSun().select(ThemeMode.SUN)

        state.beforeSun shouldBe ThemeMode.DARK
    }

    @Test
    fun `S001 RF-12 elegir otro tema en Ajustes estando en Sol sale del modo sol`() {
        val state = ThemeState.DEFAULT.toggleSun().select(ThemeMode.DARK)

        state shouldBe ThemeState(mode = ThemeMode.DARK, beforeSun = ThemeMode.DARK)
    }

    @Test
    fun `S001 RF-13 Sistema sigue al modo oscuro del dispositivo`() {
        ThemeState.DEFAULT.effective(systemInDarkTheme = false) shouldBe EffectiveTheme.LIGHT
        ThemeState.DEFAULT.effective(systemInDarkTheme = true) shouldBe EffectiveTheme.DARK
    }

    @Test
    fun `S001 RF-13 Claro y Oscuro no dependen del dispositivo`() {
        val light = ThemeState.DEFAULT.select(ThemeMode.LIGHT)
        val dark = ThemeState.DEFAULT.select(ThemeMode.DARK)

        light.effective(systemInDarkTheme = true) shouldBe EffectiveTheme.LIGHT
        dark.effective(systemInDarkTheme = false) shouldBe EffectiveTheme.DARK
    }

    @Test
    fun `S001 RF-13 el tema Sol no cambia con el modo oscuro del dispositivo`() {
        val sun = ThemeState.DEFAULT.toggleSun()

        sun.effective(systemInDarkTheme = false) shouldBe EffectiveTheme.SUN
        sun.effective(systemInDarkTheme = true) shouldBe EffectiveTheme.SUN
    }

    @Test
    fun `S001 RF-15 el estado guardado se restaura igual, tambien si se activo con el acceso rapido`() {
        val stored = ThemeState.DEFAULT.select(ThemeMode.DARK).toggleSun()

        ThemeState.restore(stored.mode.name, stored.beforeSun.name) shouldBe stored
    }

    @Test
    fun `S001 RF-15 valores guardados ausentes o corruptos vuelven a los valores por defecto`() {
        ThemeState.restore(null, null) shouldBe ThemeState.DEFAULT
        ThemeState.restore("VIOLETA", "SUN") shouldBe ThemeState.DEFAULT
        ThemeState.restore("DARK", "???") shouldBe ThemeState(mode = ThemeMode.DARK, beforeSun = ThemeMode.SYSTEM)
    }

    @Test
    fun `S001 RF-12 el tema anterior al modo sol nunca puede ser Sol`() {
        val corrupted = ThemeState.restore("SUN", "SUN")

        corrupted.beforeSun shouldBe ThemeMode.SYSTEM
        corrupted.toggleSun().mode shouldBe ThemeMode.SYSTEM
    }
}
