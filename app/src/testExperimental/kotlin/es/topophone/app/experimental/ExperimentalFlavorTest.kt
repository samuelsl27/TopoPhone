package es.topophone.app.experimental

import es.topophone.data.settings.SettingsDataStore
import es.topophone.receiver.experimental.ReachModule
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** S044/RF-1: el flavor `experimental` sí lleva el módulo y enlaza las ranuras a la implementación real. */
class ExperimentalFlavorTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `S044 RF-1 el classpath de experimental tiene el modulo`() {
        ReachModule.NAME shouldBe "receiver-experimental"
    }

    @Test
    fun `S044 RF-1 las ranuras de experimental estan disponibles`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val preferences = ExperimentalPreferences(SettingsDataStore.create(scope) { File(folder.root, "e.preferences_pb") })

            ExperimentalFeatureImpl(preferences).available shouldBe true
        } finally {
            scope.cancel()
        }
    }
}
