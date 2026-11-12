package es.topophone.data.export

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.formats.shp.export.ShpCoordinates
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

/** El sistema de coordenadas de la exportación a Shapefile, para todos los proyectos (S055/RF-23). */
class ShpExportPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "shp-export.preferences_pb") }

    private fun <T> withPreferences(block: suspend (DataStoreShpExportPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreShpExportPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S055 RF-23 de fabrica se exportan las coordenadas proyectadas`() {
        withPreferences { it.coordinates.first() } shouldBe ShpCoordinates.PROJECTED
    }

    @Test
    fun `S055 RF-23 el sistema elegido se recuerda al volver a abrir la app`() {
        withPreferences { it.save(ShpCoordinates.GEOGRAPHIC) }

        withPreferences { it.coordinates.first() } shouldBe ShpCoordinates.GEOGRAPHIC
    }

    @Test
    fun `S055 RF-23 se puede volver a las proyectadas despues de elegir las geograficas`() {
        withPreferences { it.save(ShpCoordinates.GEOGRAPHIC) }
        withPreferences { it.save(ShpCoordinates.PROJECTED) }

        withPreferences { it.coordinates.first() } shouldBe ShpCoordinates.PROJECTED
    }

    @Test
    fun `S055 RF-23 un valor guardado que no es un sistema conocido vuelve al de fabrica`() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = SettingsDataStore.create(scope) { file }
                store.edit { it[stringPreferencesKey("coordinates")] = "ECEF" }

                DataStoreShpExportPreferences(store).coordinates.first() shouldBe ShpCoordinates.PROJECTED
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
    }
}
