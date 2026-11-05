package es.topophone.data.geometry

import es.topophone.core.model.geometry.DistanceMode
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

/** La distancia de la información geométrica se recuerda para todos los proyectos (S026/RF-9). */
class GeometryPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "geometry.preferences_pb") }

    private fun <T> withPreferences(block: suspend (DataStoreGeometryPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreGeometryPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S026 RF-9 de fabrica se usa la cuadricula`() {
        withPreferences { it.distanceMode.first() } shouldBe DistanceMode.GRID
    }

    @Test
    fun `S026 RF-9 el terreno elegido se recuerda al volver a abrir la app`() {
        withPreferences { it.setDistanceMode(DistanceMode.GROUND) }

        withPreferences { it.distanceMode.first() } shouldBe DistanceMode.GROUND
    }

    @Test
    fun `S026 RF-9 volver a la cuadricula tambien se recuerda`() {
        withPreferences { it.setDistanceMode(DistanceMode.GROUND) }
        withPreferences { it.setDistanceMode(DistanceMode.GRID) }

        withPreferences { it.distanceMode.first() } shouldBe DistanceMode.GRID
    }
}
