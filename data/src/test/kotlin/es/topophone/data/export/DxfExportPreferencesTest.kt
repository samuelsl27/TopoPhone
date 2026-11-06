package es.topophone.data.export

import es.topophone.core.formats.dxf.export.DxfCoordinates
import es.topophone.core.formats.dxf.export.DxfExportOptions
import es.topophone.core.formats.dxf.export.DxfLabel
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

/** Las etiquetas, la altura del texto y el sistema de la exportación a DXF, para todos los proyectos (S029/RF-7, RF-8, RF-20). */
class DxfExportPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "dxf-export.preferences_pb") }

    private fun <T> withPreferences(block: suspend (DataStoreDxfExportPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreDxfExportPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S029 RF-7 RF-8 de fabrica Nombre y Cota, 0,5 m y coordenadas locales`() {
        val factory = withPreferences { it.options.first() }

        factory shouldBe DxfExportOptions()
        factory.labels shouldBe setOf(DxfLabel.NAME, DxfLabel.ELEVATION)
        factory.textHeightM shouldBe 0.5
        factory.coordinates shouldBe DxfCoordinates.LOCAL
    }

    @Test
    fun `S029 RF-20 lo ultimo elegido se recuerda al volver a abrir la app`() {
        val chosen = DxfExportOptions(setOf(DxfLabel.DESCRIPTION), 2.25, DxfCoordinates.GLOBAL)

        withPreferences { it.save(chosen) }

        withPreferences { it.options.first() } shouldBe chosen
    }

    @Test
    fun `S029 RF-7 se puede recordar que no se quiere ninguna etiqueta`() {
        withPreferences { it.save(DxfExportOptions(emptySet())) }

        withPreferences { it.options.first() }.labels shouldBe emptySet()
    }

    @Test
    fun `S029 RF-8 una altura guardada fuera de 0,01 a 100 m vuelve a la de fabrica`() {
        withPreferences { it.save(DxfExportOptions(textHeightM = 500.0)) }
        withPreferences { it.options.first() }.textHeightM shouldBe 0.5

        withPreferences { it.save(DxfExportOptions(textHeightM = 0.001)) }
        withPreferences { it.options.first() }.textHeightM shouldBe 0.5
    }
}
