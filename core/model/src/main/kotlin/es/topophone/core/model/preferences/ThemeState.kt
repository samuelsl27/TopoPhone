package es.topophone.core.model.preferences

/** Temas que el usuario puede elegir en Ajustes, en este orden (S001/RF-9). */
enum class ThemeMode { SYSTEM, LIGHT, DARK, SUN }

/** Tema que se pinta realmente en pantalla. */
enum class EffectiveTheme { LIGHT, DARK, SUN }

/**
 * Tema activo y tema al que volver al salir del modo sol (S001/RF-12, S001/RF-15).
 *
 * @property mode tema activo, elegido en Ajustes o con el acceso rápido.
 * @property beforeSun último tema distinto de Sol; nunca es [ThemeMode.SUN].
 */
data class ThemeState(val mode: ThemeMode = ThemeMode.SYSTEM, val beforeSun: ThemeMode = ThemeMode.SYSTEM) {
    init {
        require(beforeSun != ThemeMode.SUN) { "El tema anterior al modo sol no puede ser Sol" }
    }

    /** Acceso rápido de la barra superior: entra en Sol o vuelve al tema anterior (S001/RF-12). */
    fun toggleSun(): ThemeState = if (mode == ThemeMode.SUN) {
        copy(mode = beforeSun)
    } else {
        ThemeState(mode = ThemeMode.SUN, beforeSun = mode)
    }

    /** Elección en Ajustes (S001/RF-9). Elegir Sol también recuerda el tema anterior. */
    fun select(newMode: ThemeMode): ThemeState = when {
        newMode != ThemeMode.SUN -> ThemeState(mode = newMode, beforeSun = newMode)
        mode == ThemeMode.SUN -> this
        else -> ThemeState(mode = ThemeMode.SUN, beforeSun = mode)
    }

    /** Tema a pintar. Sol es un tema claro de alto contraste que no sigue al modo oscuro (S001/RF-13). */
    fun effective(systemInDarkTheme: Boolean): EffectiveTheme = when (mode) {
        ThemeMode.SYSTEM -> if (systemInDarkTheme) EffectiveTheme.DARK else EffectiveTheme.LIGHT
        ThemeMode.LIGHT -> EffectiveTheme.LIGHT
        ThemeMode.DARK -> EffectiveTheme.DARK
        ThemeMode.SUN -> EffectiveTheme.SUN
    }

    companion object {
        val DEFAULT = ThemeState()

        /** Reconstruye el estado guardado; los valores ausentes o corruptos vuelven al valor por defecto (S001/RF-15). */
        fun restore(modeName: String?, beforeSunName: String?): ThemeState {
            val mode = ThemeMode.entries.firstOrNull { it.name == modeName } ?: return DEFAULT
            val beforeSun = ThemeMode.entries.firstOrNull { it.name == beforeSunName && it != ThemeMode.SUN } ?: ThemeMode.SYSTEM
            return ThemeState(mode = mode, beforeSun = beforeSun)
        }
    }
}
