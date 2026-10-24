package es.topophone.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import es.topophone.core.formats.backup.BackupSettings
import es.topophone.core.formats.backup.SettingValue
import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.HeadingChoice
import es.topophone.core.model.point.StakeMode
import es.topophone.core.model.point.StakeoutSettings
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.preferences.ThemeMode
import es.topophone.data.point.DataStoreCollectorPreferences
import es.topophone.data.point.DataStoreStakeoutPreferences
import es.topophone.data.receiver.DataStoreReceiverPreferences
import es.topophone.data.receiver.DataStoreTiltPreferences
import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.data.receiver.SavedReceiver
import es.topophone.data.settings.DataStoreSettingsRepository
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

/** Ajustes que viajan en la copia; los propios del móvil no salen y no se tocan al restaurar (S013/RF-2, RF-3, RF-8). */
class SettingsArchiveTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeLanguage(var tag: String = "") : AppLanguageStore {
        override fun current(): String = tag

        override suspend fun apply(tag: String) {
            this.tag = tag
        }
    }

    private fun <T> withStores(dir: String, block: suspend (Map<String, DataStore<Preferences>>) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val stores = SettingsArchive.PORTABLE.keys.associateWith { name ->
                SettingsDataStore.create(scope) { File(folder.root, "$dir/$name.preferences_pb") }
            }
            block(stores)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private suspend fun fill(stores: Map<String, DataStore<Preferences>>) {
        DataStoreSettingsRepository(stores.getValue("settings")).selectTheme(ThemeMode.DARK)
        stores.getValue("collector").edit { it[intPreferencesKey("collector_averaging_seconds")] = 20 }
        stores.getValue("export").edit { it[stringSetPreferencesKey("custom_columns")] = setOf("NAME", "LATITUDE") }
        val receiver = DataStoreReceiverPreferences(stores.getValue("receiver"))
        receiver.savePoleHeight(2.0 + 1e-12)
        receiver.saveSessionSpaceMb(1_000)
        receiver.saveLastReceiver(SavedReceiver("00:11:22:33:44:55", "RS3 Ana"))
        receiver.markNotificationsAsked()
        receiver.saveNtripSession("ntrip-ign", active = true)
        DataStoreTiltPreferences(stores.getValue("receiver")).save("00:11:22:33:44:55", ReceiverTiltSettings(TiltMode.APP, verified = true))
    }

    @Test
    fun `S013 RF-2 viajan los ajustes de la app con su tipo y el idioma`() {
        val exported = withStores("a") { stores ->
            fill(stores)
            SettingsArchive(stores, FakeLanguage("en")).export()
        }

        exported.appLanguage shouldBe "en"
        exported.stores.getValue("settings").getValue("theme_mode") shouldBe SettingValue.Text("DARK")
        exported.stores.getValue("collector").getValue("collector_averaging_seconds") shouldBe SettingValue.Int32(20)
        exported.stores.getValue("export").getValue("custom_columns") shouldBe SettingValue.Texts(setOf("NAME", "LATITUDE"))
        exported.stores.getValue("receiver").getValue("pole_height_m") shouldBe SettingValue.Float64(2.0 + 1e-12)
        exported.stores.getValue("receiver").getValue("session_space_mb") shouldBe SettingValue.Int32(1_000)
    }

    @Test
    fun `S018 RF-5, RF-8 y RF-18 el modo de guia, el origen del rumbo y la tolerancia viajan en la copia`() {
        val exported = withStores("stakeout") { stores ->
            DataStoreStakeoutPreferences(stores.getValue("stakeout"))
                .save(StakeoutSettings(StakeMode.BY_DISTANCE, HeadingChoice.IMU, toleranceM = 0.04))
            SettingsArchive(stores, FakeLanguage()).export()
        }

        val stakeout = exported.stores.getValue("stakeout")
        stakeout.getValue("stakeout_mode") shouldBe SettingValue.Text("BY_DISTANCE")
        stakeout.getValue("stakeout_heading") shouldBe SettingValue.Text("IMU")
        stakeout.getValue("stakeout_tolerance_m") shouldBe SettingValue.Float64(0.04)
    }

    @Test
    fun `S019 RF-10 la tolerancia vertical viaja en la copia con el resto de los ajustes del replanteo`() {
        val exported = withStores("stakeout") { stores ->
            DataStoreStakeoutPreferences(stores.getValue("stakeout"))
                .save(StakeoutSettings(toleranceM = 0.04, toleranceVM = 0.02))
            SettingsArchive(stores, FakeLanguage()).export()
        }

        val stakeout = exported.stores.getValue("stakeout")
        stakeout.getValue("stakeout_tolerance_m") shouldBe SettingValue.Float64(0.04)
        stakeout.getValue("stakeout_tolerance_v_m") shouldBe SettingValue.Float64(0.02)
    }

    @Test
    fun `S017 RF-16 y RF-17 el disparo, el intervalo y la distancia del modo automatico viajan en la copia`() {
        val exported = withStores("auto") { stores ->
            DataStoreCollectorPreferences(stores.getValue("collector"))
                .save(CollectorSettings(autoTrigger = AutoTrigger.DISTANCE, autoIntervalS = 30, autoDistanceM = 2.5))
            SettingsArchive(stores, FakeLanguage()).export()
        }

        val collector = exported.stores.getValue("collector")
        collector.getValue("collector_auto_trigger") shouldBe SettingValue.Text("DISTANCE")
        collector.getValue("collector_auto_interval_s") shouldBe SettingValue.Int32(30)
        collector.getValue("collector_auto_distance_m") shouldBe SettingValue.Float64(2.5)
    }

    @Test
    fun `S013 RF-3 S016 RF-1 el receptor, su modo de inclinacion, los permisos y la NTRIP no salen`() {
        val receiver = withStores("b") { stores ->
            fill(stores)
            SettingsArchive(stores, FakeLanguage()).export()
        }.stores.getValue("receiver")

        receiver.keys shouldBe setOf("pole_height_m", "session_space_mb")
    }

    @Test
    fun `S013 RF-8 al restaurar se ponen los de la copia y los del movil no cambian`() {
        val backup = BackupSettings(
            stores = mapOf(
                "settings" to mapOf("theme_mode" to SettingValue.Text("SUN")),
                "receiver" to mapOf(
                    "pole_height_m" to SettingValue.Float64(1.5),
                    "last_receiver_address" to SettingValue.Text("AA:BB:CC:DD:EE:FF"), // No viaja aunque venga.
                ),
            ),
            appLanguage = "es",
        )
        val language = FakeLanguage("en")

        val receiver = withStores("c") { stores ->
            fill(stores)
            SettingsArchive(stores, language).restore(backup)
            stores.getValue("collector").data.first().asMap().size shouldBe 0 // Lo que no está en la copia vuelve a su valor por defecto.
            stores.getValue("settings").data.first()[stringPreferencesKey("theme_mode")] shouldBe "SUN"
            stores.getValue("receiver").data.first()
        }

        receiver[doublePreferencesKey("pole_height_m")] shouldBe 1.5
        receiver[intPreferencesKey("session_space_mb")] shouldBe null
        receiver[stringPreferencesKey("last_receiver_address")] shouldBe "00:11:22:33:44:55"
        receiver[stringPreferencesKey("ntrip_last_profile_id")] shouldBe "ntrip-ign"
        receiver[booleanPreferencesKey("notifications_asked")] shouldBe true
        language.tag shouldBe "es"
    }

    @Test
    fun `S013 RF-2 y RF-8 lo exportado y restaurado en otro movil queda igual`() {
        val exported = withStores("d") { stores ->
            fill(stores)
            SettingsArchive(stores, FakeLanguage("en")).export()
        }

        val again = withStores("e") { stores ->
            val archive = SettingsArchive(stores, FakeLanguage())
            archive.restore(exported)
            archive.export()
        }

        again shouldBe exported
    }
}
