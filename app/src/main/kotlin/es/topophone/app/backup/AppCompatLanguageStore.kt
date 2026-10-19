package es.topophone.app.backup

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import es.topophone.data.backup.AppLanguageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Idioma de la app para la copia (S013/RF-2, RF-8): el que guarda AppCompat (S001/RF-16), en etiquetas BCP 47; vacío
 * es el del sistema. Aplicarlo recrea la actividad, así que solo se aplica si cambia. [read] y [write] son AppCompat;
 * los tests los sustituyen porque Robolectric no tiene el gestor de idiomas del sistema (Android 13+).
 */
class AppCompatLanguageStore(
    private val read: () -> String = { AppCompatDelegate.getApplicationLocales().toLanguageTags() },
    private val write: (String) -> Unit = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) },
) : AppLanguageStore {
    override fun current(): String = read()

    override suspend fun apply(tag: String) = withContext(Dispatchers.Main.immediate) {
        if (read() != tag) write(tag)
    }
}
