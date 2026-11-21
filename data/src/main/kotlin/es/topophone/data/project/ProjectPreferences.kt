package es.topophone.data.project

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.SpainCrs
import java.io.IOException
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Valores por defecto de los proyectos nuevos, que se eligen en Ajustes (S006/RF-6, RF-13). */
interface ProjectPreferences {
    val defaults: Flow<ProjectDefaults>

    suspend fun saveDefaults(defaults: ProjectDefaults)

    companion object {
        /** Siempre los valores por defecto de fábrica, sin guardar nada: para quien solo los lee y no tiene Ajustes. */
        val FACTORY: ProjectPreferences = object : ProjectPreferences {
            override val defaults: Flow<ProjectDefaults> = flowOf(ProjectDefaults())

            override suspend fun saveDefaults(defaults: ProjectDefaults) = Unit
        }
    }
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
            crs = crsOf(p),
            linearUnit = enumOr(p[LINEAR_UNIT], factory.linearUnit),
            angularUnit = enumOr(p[ANGULAR_UNIT], factory.angularUnit),
            correctionsDatum = enumOr(p[CORRECTIONS_DATUM], factory.correctionsDatum),
            heightSystem = enumOr(p[HEIGHT_SYSTEM], factory.heightSystem), // S008/RF-10
            codeLibraryId = p[CODE_LIBRARY], // S023/RF-19
        )
    }.distinctUntilChanged()

    override suspend fun saveDefaults(defaults: ProjectDefaults) {
        dataStore.edit {
            it[AUTHOR] = defaults.author.trim()
            it[CRS] = defaults.crs.name
            // S034/RF-1: un SC de la biblioteca por defecto guarda su definición y su geoide; uno de España, nada más que el nombre.
            (defaults.crs as? LibraryCrs)?.let { library ->
                it[CRS_DEFINITION] = library.definition.toText()
                library.geoidGrid?.let { grid -> it[GEOID] = grid } ?: it.remove(GEOID)
            } ?: run {
                it.remove(CRS_DEFINITION)
                it.remove(GEOID)
            }
            it[LINEAR_UNIT] = defaults.linearUnit.name
            it[ANGULAR_UNIT] = defaults.angularUnit.name
            it[CORRECTIONS_DATUM] = defaults.correctionsDatum.name
            it[HEIGHT_SYSTEM] = defaults.heightSystem.name
            defaults.codeLibraryId?.let { id -> it[CODE_LIBRARY] = id } ?: it.remove(CODE_LIBRARY)
        }
    }

    /** El SC por defecto: el de la biblioteca si hay definición legible, y si no el de España guardado por su nombre (S034/RF-1). */
    private fun crsOf(p: Preferences): ProjectCrs {
        val definition = p[CRS_DEFINITION]?.let { CrsDefinition.parseOrNull(it) }
        val corrections = enumOr(p[CORRECTIONS_DATUM], CorrectionsDatum.ETRS89)
        return if (definition != null) LibraryCrs(definition, corrections, p[GEOID]) else enumOr<SpainCrs>(p[CRS], SpainCrs.ETRS89_UTM30)
    }

    private companion object {
        val AUTHOR = stringPreferencesKey("default_author")
        val CRS = stringPreferencesKey("default_crs")
        val CRS_DEFINITION = stringPreferencesKey("default_crs_definition")
        val GEOID = stringPreferencesKey("default_geoid_grid")
        val LINEAR_UNIT = stringPreferencesKey("default_linear_unit")
        val ANGULAR_UNIT = stringPreferencesKey("default_angular_unit")
        val CORRECTIONS_DATUM = stringPreferencesKey("default_corrections_datum")
        val HEIGHT_SYSTEM = stringPreferencesKey("default_height_system")
        val CODE_LIBRARY = stringPreferencesKey("default_code_library")

        /** Un valor desconocido (de otra versión o dañado) cuenta como el de fábrica. */
        inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
