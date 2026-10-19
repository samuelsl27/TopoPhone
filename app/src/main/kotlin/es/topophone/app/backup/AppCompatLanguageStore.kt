package es.topophone.app.backup

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import es.topophone.data.backup.AppLanguageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Idioma de la app para la copia (S013/RF-2, RF-8): el que guarda AppCompat (S001/RF-16), en etiquetas BCP 47; vacío
 * es el del sistema. Aplicarlo recrea la actividad si cambia.
 */
class AppCompatLanguageStore : AppLanguageStore {
    override fun current(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags()

    override suspend fun apply(tag: String) = withContext(Dispatchers.Main) {
        if (current() != tag) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
