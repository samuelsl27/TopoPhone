package es.topophone.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Persistencia del tema (S001/RF-15). */
class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "settings.preferences_pb") }

    /** Abre un DataStore sobre el fichero, ejecuta [block] y lo cierra, como si la app se cerrara después. */
    private fun <T> withRepository(block: suspend (DataStore<Preferences>, SettingsRepository) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = SettingsDataStore.create(scope) { file }
            block(dataStore, DataStoreSettingsRepository(dataStore))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S001 RF-15 sin nada guardado el tema es el de por defecto`() {
        withRepository { _, repository -> repository.themeState.first() } shouldBe ThemeState.DEFAULT
    }

    @Test
    fun `S001 RF-15 el tema elegido en Ajustes se conserva al volver a abrir la app`() {
        withRepository { _, repository -> repository.selectTheme(ThemeMode.DARK) }

        withRepository { _, repository -> repository.themeState.first() } shouldBe ThemeState(ThemeMode.DARK, ThemeMode.DARK)
    }

    @Test
    fun `S001 RF-15 el modo sol activado con el acceso rapido se conserva con su tema anterior`() {
        withRepository { _, repository ->
            repository.selectTheme(ThemeMode.LIGHT)
            repository.toggleSun()
        }

        val reopened = withRepository { _, repository -> repository.themeState.first() }

        reopened shouldBe ThemeState(mode = ThemeMode.SUN, beforeSun = ThemeMode.LIGHT)
    }

    @Test
    fun `S001 RF-15 tras reabrir, desactivar el modo sol vuelve al tema anterior guardado`() {
        withRepository { _, repository ->
            repository.selectTheme(ThemeMode.DARK)
            repository.toggleSun()
        }

        val afterToggle = withRepository { _, repository ->
            repository.toggleSun()
            repository.themeState.first()
        }

        afterToggle.mode shouldBe ThemeMode.DARK
    }

    @Test
    fun `S001 RF-15 un fichero de ajustes corrupto vuelve a los valores por defecto sin fallar`() {
        file.writeBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37, 0x42))

        withRepository { _, repository -> repository.themeState.first() } shouldBe ThemeState.DEFAULT
        withRepository { _, repository -> repository.selectTheme(ThemeMode.SUN) }
        withRepository { _, repository -> repository.themeState.first().mode } shouldBe ThemeMode.SUN
    }

    @Test
    fun `S001 RF-15 valores guardados desconocidos se interpretan como los de por defecto`() {
        withRepository { dataStore, _ ->
            dataStore.edit {
                it[DataStoreSettingsRepository.THEME_MODE] = "VIOLETA"
                it[DataStoreSettingsRepository.THEME_BEFORE_SUN] = "SUN"
            }
        }

        withRepository { _, repository -> repository.themeState.first() } shouldBe ThemeState.DEFAULT
    }
}
