package es.topophone.data.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import es.topophone.data.backup.DatabaseSnapshot
import es.topophone.data.di.BackupDataModule
import es.topophone.data.settings.SettingsDataStore
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Lo descargado en una zona sin conexión no entra en la copia de seguridad ni en el paquete de proyecto (S037/RF-29). */
class OfflineBackupExclusionTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `S037 RF-29 ninguna tabla de la copia es de zonas sin conexion`() {
        val names = DatabaseSnapshot.TABLES.map { it.name }

        names.filter { it.contains("offline") || it.contains("zone") || it.contains("region") } shouldBe emptyList()
    }

    @Test
    fun `S037 RF-29 los ajustes de las zonas sin conexion no estan entre los que viajan en la copia`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            fun store(name: String): DataStore<Preferences> = SettingsDataStore.create(scope) { File(folder.root, "$name.preferences_pb") }

            val portable = BackupDataModule.providePortableStores(
                store("settings"),
                store("projects"),
                store("collector"),
                store("stakeout"),
                store("export"),
                store("map"),
                store("receiver"),
            )

            portable.all.keys shouldBe setOf("settings", "projects", "collector", "stakeout", "export", "map", "receiver")
        } finally {
            scope.cancel()
        }
    }
}
