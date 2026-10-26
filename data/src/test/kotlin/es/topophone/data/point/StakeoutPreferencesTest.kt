package es.topophone.data.point

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.data.settings.SettingsDataStore
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

/** El modo de guía, el origen del rumbo y la tolerancia del replanteo, para todos los proyectos (S018/RF-5, RF-8, RF-18). */
class StakeoutPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "stakeout.preferences_pb") }

    private fun <T> withPreferences(block: suspend (DataStoreStakeoutPreferences, DataStore<Preferences>) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SettingsDataStore.create(scope) { file }
            block(DataStoreStakeoutPreferences(store), store)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S018 RF-5 y RF-8 de fabrica guia por direccion, con el rumbo de marcha y 2,5 cm`() {
        withPreferences { prefs, _ -> prefs.settings.first() } shouldBe StakeoutSettings()
    }

    @Test
    fun `S018 RF-5, RF-8 y RF-18 lo elegido se recuerda al volver a abrir la app`() {
        val chosen = StakeoutSettings(StakeMode.BY_DISTANCE, HeadingChoice.IMU, toleranceM = 0.05)

        withPreferences { prefs, _ -> prefs.save(chosen) }

        withPreferences { prefs, _ -> prefs.settings.first() } shouldBe chosen
    }

    @Test
    fun `S041 RF-3 de fabrica el modo de linea es A la linea y el ultimo elegido se recuerda`() {
        withPreferences { prefs, _ -> prefs.settings.first() }.lineMode shouldBe LineStakeoutMode.TO_LINE

        withPreferences { prefs, _ -> prefs.save(StakeoutSettings(lineMode = LineStakeoutMode.INTERVALS)) }

        withPreferences { prefs, _ -> prefs.settings.first() }.lineMode shouldBe LineStakeoutMode.INTERVALS
    }

    @Test
    fun `S041 RF-3 un modo guardado que ya no existe vuelve a A la linea`() {
        withPreferences { prefs, store ->
            store.edit { it[stringPreferencesKey("stakeout_line_mode")] = "OTRO" }
            prefs.settings.first().lineMode shouldBe LineStakeoutMode.TO_LINE
        }
    }

    @Test
    fun `S018 RF-18 una tolerancia fuera de rango se guarda corregida`() {
        withPreferences { prefs, _ -> prefs.save(StakeoutSettings(toleranceM = 7.0)) }
        withPreferences { prefs, _ -> prefs.settings.first() }.toleranceM shouldBe 1.0

        withPreferences { prefs, _ -> prefs.save(StakeoutSettings(toleranceM = 0.0001)) }
        withPreferences { prefs, _ -> prefs.settings.first() }.toleranceM shouldBe 0.01
    }

    @Test
    fun `S019 RF-10 de fabrica la tolerancia vertical es de 3 cm`() {
        withPreferences { prefs, _ -> prefs.settings.first() }.toleranceVM shouldBe 0.03
    }

    @Test
    fun `S019 RF-10 la tolerancia vertical se recuerda al volver a abrir la app, aparte de la horizontal`() {
        val chosen = StakeoutSettings(toleranceM = 0.05, toleranceVM = 0.02)

        withPreferences { prefs, _ -> prefs.save(chosen) }

        val read = withPreferences { prefs, _ -> prefs.settings.first() }
        read shouldBe chosen
        read.toleranceM shouldBe 0.05
        read.toleranceVM shouldBe 0.02
    }

    @Test
    fun `S019 RF-10 una tolerancia vertical fuera de rango se guarda corregida`() {
        withPreferences { prefs, _ -> prefs.save(StakeoutSettings(toleranceVM = 7.0)) }
        withPreferences { prefs, _ -> prefs.settings.first() }.toleranceVM shouldBe 1.0

        withPreferences { prefs, _ -> prefs.save(StakeoutSettings(toleranceVM = 0.0001)) }
        withPreferences { prefs, _ -> prefs.settings.first() }.toleranceVM shouldBe 0.01
    }

    @Test
    fun `S019 RF-10 unos ajustes guardados antes de S019 sin tolerancia vertical la tienen de 3 cm`() {
        withPreferences { prefs, store ->
            // Lo que escribía S018: sin la clave de la tolerancia vertical.
            store.edit {
                it[stringPreferencesKey("stakeout_mode")] = "BY_DISTANCE"
                it[doublePreferencesKey("stakeout_tolerance_m")] = 0.05
            }
            val read = prefs.settings.first()

            read.mode shouldBe StakeMode.BY_DISTANCE
            read.toleranceM shouldBe 0.05
            read.toleranceVM shouldBe 0.03
        }
    }

    @Test
    fun `S018 RF-5 un valor guardado que no existe vuelve al de fabrica`() {
        withPreferences { prefs, _ -> prefs.save(StakeoutSettings(StakeMode.BY_DISTANCE, HeadingChoice.IMU, 0.05)) }
        withPreferences { prefs, store ->
            // Un valor de una versión futura o estropeado.
            store.edit { it[stringPreferencesKey("stakeout_mode")] = "OTRO" }
            prefs.settings.first().mode shouldBe StakeMode.BY_DIRECTION
        }
    }
}
