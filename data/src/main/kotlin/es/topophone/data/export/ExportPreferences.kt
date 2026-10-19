package es.topophone.data.export

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import es.topophone.core.formats.csv.ColumnSeparator
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.formats.csv.DecimalSeparator
import es.topophone.core.formats.csv.ExportColumn
import es.topophone.core.formats.csv.ExportFormat
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Último formato, separador, signo decimal y columnas de la exportación, para todos los proyectos (S012/RF-21). */
interface ExportPreferences {
    val options: Flow<CsvOptions>

    suspend fun save(options: CsvOptions)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ExportDataStoreQualifier

@Singleton
class DataStoreExportPreferences @Inject constructor(@param:ExportDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    ExportPreferences {

    override val options: Flow<CsvOptions> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            val factory = CsvOptions() // S012/RF-13: todas las columnas con coma y punto
            CsvOptions(
                format = ExportFormat.entries.firstOrNull { it.name == p[FORMAT] } ?: factory.format,
                separator = ColumnSeparator.entries.firstOrNull { it.name == p[SEPARATOR] } ?: factory.separator,
                decimal = DecimalSeparator.entries.firstOrNull { it.name == p[DECIMAL] } ?: factory.decimal,
                customColumns = p[CUSTOM]?.mapNotNull { name -> ExportColumn.entries.firstOrNull { it.name == name } }?.toSet()
                    ?: factory.customColumns,
            )
        }.distinctUntilChanged()

    override suspend fun save(options: CsvOptions) {
        dataStore.edit {
            it[FORMAT] = options.format.name
            it[SEPARATOR] = options.separator.name
            it[DECIMAL] = options.decimal.name
            it[CUSTOM] = options.customColumns.map { column -> column.name }.toSet()
        }
    }

    private companion object {
        val FORMAT = stringPreferencesKey("format")
        val SEPARATOR = stringPreferencesKey("separator")
        val DECIMAL = stringPreferencesKey("decimal")
        val CUSTOM = stringSetPreferencesKey("custom_columns")
    }
}
