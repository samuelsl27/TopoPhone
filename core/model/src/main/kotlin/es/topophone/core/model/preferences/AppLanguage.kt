package es.topophone.core.model.preferences

/**
 * Idiomas de la interfaz, en el orden en que se ofrecen en Ajustes (S001/RF-16).
 *
 * @property languageTags etiquetas BCP 47 con que se guarda la elección; vacío = idioma del sistema.
 */
enum class AppLanguage(val languageTags: String) {
    SYSTEM(""),
    SPANISH("es"),
    ENGLISH("en"),
    ;

    companion object {
        val DEFAULT = SYSTEM

        /** Interpreta las etiquetas guardadas (p. ej. `es-ES` o `es,en`) por su primer idioma. */
        fun fromLanguageTags(tags: String): AppLanguage {
            val primary = tags.split(',').first().trim().substringBefore('-').lowercase()
            return entries.firstOrNull { it != SYSTEM && it.languageTags == primary } ?: SYSTEM
        }
    }
}
