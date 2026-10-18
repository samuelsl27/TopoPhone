package es.topophone.data.project

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.project.ProjectDraft
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

/** Valores por defecto de los proyectos nuevos (S006/RF-6, RF-13). */
class ProjectPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "projects.preferences_pb") }

    private fun <T> withPreferences(block: suspend (DataStoreProjectPreferences) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            block(DataStoreProjectPreferences(SettingsDataStore.create(scope) { file }))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `S006 RF-13 de fabrica ETRS89 UTM 30, metros, grados-minutos-segundos y correcciones en ETRS89, sin autor`() {
        withPreferences { it.defaults.first() } shouldBe ProjectDefaults(
            author = "",
            crs = ProjectCrs.ETRS89_UTM30,
            linearUnit = LinearUnit.METRE,
            angularUnit = AngularUnit.DMS,
            correctionsDatum = CorrectionsDatum.ETRS89,
        )
    }

    @Test
    fun `S006 RF-13 los valores por defecto elegidos se recuerdan al volver a abrir la app`() {
        val chosen = ProjectDefaults(
            author = "S. S.",
            crs = ProjectCrs.ETRS89_UTM31,
            linearUnit = LinearUnit.US_SURVEY_FOOT,
            angularUnit = AngularUnit.GON,
            correctionsDatum = CorrectionsDatum.ITRF_WGS84,
        )

        withPreferences { it.saveDefaults(chosen) }

        withPreferences { it.defaults.first() } shouldBe chosen
    }

    @Test
    fun `S006 RF-6 el formulario nuevo parte de los valores por defecto, sin nombre ni descripcion`() {
        val defaults = ProjectDefaults(author = "S. S.", crs = ProjectCrs.ETRS89_UTM29)

        defaults.toDraft() shouldBe ProjectDraft(
            name = "",
            author = "S. S.",
            description = "",
            crs = ProjectCrs.ETRS89_UTM29,
            linearUnit = LinearUnit.METRE,
            angularUnit = AngularUnit.DMS,
            correctionsDatum = CorrectionsDatum.ETRS89,
        )
    }

    @Test
    fun `S006 RF-13 un valor desconocido en el fichero se toma como el de fabrica`() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                SettingsDataStore.create(scope) { file }.edit { it[stringPreferencesKey("default_crs")] = "ETRS89_UTM99" }
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

        withPreferences { it.defaults.first() }.crs shouldBe ProjectCrs.ETRS89_UTM30
    }

    @Test
    fun `S008 RF-10 la altura por defecto de fabrica es la altitud y la elegida se recuerda`() {
        withPreferences { it.defaults.first() }.heightSystem shouldBe HeightSystem.ORTHOMETRIC

        withPreferences { it.saveDefaults(ProjectDefaults(crs = ProjectCrs.ETRS89_GEOGRAPHIC, heightSystem = HeightSystem.ELLIPSOIDAL)) }

        withPreferences { it.defaults.first() }.let { it.crs to it.heightSystem } shouldBe
            (ProjectCrs.ETRS89_GEOGRAPHIC to HeightSystem.ELLIPSOIDAL)
    }
}
