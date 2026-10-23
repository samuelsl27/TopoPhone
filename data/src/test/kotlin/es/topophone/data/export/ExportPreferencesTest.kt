package es.topophone.data.export

import es.topophone.core.formats.csv.ColumnSeparator
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.DecimalSeparator
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.csv.ExportFormat
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

/** Último formato, separador, signo decimal y columnas de la exportación, para todos los proyectos (S012/RF-13, RF-21). */
class ExportPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "export.preferences_pb") }

    private fun <T> withPreferences(block: suspend (DataStoreExportPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreExportPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S012 RF-13 de fabrica todas las columnas con coma y punto`() {
        withPreferences { it.options.first() } shouldBe CsvOptions()
    }

    @Test
    fun `S012 RF-21 lo ultimo elegido se recuerda al volver a abrir la app`() {
        val chosen = CsvOptions(
            format = ExportFormat.CUSTOM,
            separator = ColumnSeparator.SEMICOLON,
            decimal = DecimalSeparator.COMMA,
            customColumns = setOf(ExportColumn.NAME, ExportColumn.LATITUDE, ExportColumn.LONGITUDE),
        )

        withPreferences { it.save(chosen) }

        withPreferences { it.options.first() } shouldBe chosen
    }

    @Test
    fun `S015 RF-22 un personalizado guardado antes deja las columnas de la base sin marcar`() {
        val before = CsvOptions(format = ExportFormat.CUSTOM, customColumns = setOf(ExportColumn.NAME, ExportColumn.EASTING))
        withPreferences { it.save(before) }

        val read = withPreferences { it.options.first() }

        read.customColumns shouldBe setOf(ExportColumn.NAME, ExportColumn.EASTING)
        read.columns.none { it.name.startsWith("BASE") } shouldBe true
    }

    @Test
    fun `S016 RF-24 un personalizado guardado antes deja las columnas de la inclinacion sin marcar`() {
        val before = CsvOptions(format = ExportFormat.CUSTOM, customColumns = setOf(ExportColumn.NAME, ExportColumn.BASELINE))
        withPreferences { it.save(before) }

        val read = withPreferences { it.options.first() }

        read.columns.none { it.name.contains("TILT") || it == ExportColumn.HEADING } shouldBe true
    }
}
