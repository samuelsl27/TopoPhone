package es.topophone.data.project

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.project.ProjectDefaults
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Valores por defecto de los proyectos nuevos, que se eligen en Ajustes (S006/RF-6, RF-13). */
interface ProjectPreferences {
    val defaults: Flow<ProjectDefaults>

    suspend fun saveDefaults(defaults: ProjectDefaults)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProjectDataStoreQualifier

@Singleton
class DataStoreProjectPreferences @Inject constructor(@param:ProjectDataStoreQualifier private val dataStore: DataStore<Preferences>) :
    ProjectPreferences {

    private val data: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    override val defaults: Flow<ProjectDefaults> = data.map { p ->
        val factory = ProjectDefaults()
        ProjectDefaults(
            author = p[AUTHOR].orEmpty(),
            crs = enumOr(p[CRS], factory.crs),
            linearUnit = enumOr(p[LINEAR_UNIT], factory.linearUnit),
            angularUnit = enumOr(p[ANGULAR_UNIT], factory.angularUnit),
            correctionsDatum = enumOr(p[CORRECTIONS_DATUM], factory.correctionsDatum),
            heightSystem = enumOr(p[HEIGHT_SYSTEM], factory.heightSystem), // S008/RF-10
        )
    }.distinctUntilChanged()

    override suspend fun saveDefaults(defaults: ProjectDefaults) {
        dataStore.edit {
            it[AUTHOR] = defaults.author.trim()
            it[CRS] = defaults.crs.name
            it[LINEAR_UNIT] = defaults.linearUnit.name
            it[ANGULAR_UNIT] = defaults.angularUnit.name
            it[CORRECTIONS_DATUM] = defaults.correctionsDatum.name
            it[HEIGHT_SYSTEM] = defaults.heightSystem.name
        }
    }

    private companion object {
        val AUTHOR = stringPreferencesKey("default_author")
        val CRS = stringPreferencesKey("default_crs")
        val LINEAR_UNIT = stringPreferencesKey("default_linear_unit")
        val ANGULAR_UNIT = stringPreferencesKey("default_angular_unit")
        val CORRECTIONS_DATUM = stringPreferencesKey("default_corrections_datum")
        val HEIGHT_SYSTEM = stringPreferencesKey("default_height_system")

        /** Un valor desconocido (de otra versión o dañado) cuenta como el de fábrica. */
        inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
