package es.topophone.data.backup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.MapViewportEntity
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.settings.SettingsDataStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Cambios sin copiar: los datos y los ajustes sí, mover el mapa no (S013/RF-18). */
@RunWith(AndroidJUnit4::class)
class BackupChangesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var preferences: DataStoreAutoBackupPreferences
    private lateinit var changes: BackupChanges
    private lateinit var projectId: String

    @Before
    fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        projectId = (RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")) as SaveResult.Saved).id
        settings = TestSettings(folder.newFolder("settings"))
        preferences = DataStoreAutoBackupPreferences(SettingsDataStore.create(scope) { java.io.File(folder.root, "backup.preferences_pb") })
        changes = BackupChanges(db, settings.stores.values, preferences)
        changes.start(scope)
        delay(300) // Que empiece a escuchar antes de cambiar nada.
    }

    @After
    fun close() {
        scope.cancel()
        settings.close()
        db.close()
    }

    private fun pending() = runBlocking { preferences.state.first().pendingChanges }

    private fun awaitPending() = runBlocking { withTimeout(5_000) { preferences.state.first { it.pendingChanges } } }

    @Test
    fun `S013 RF-18 un proyecto nuevo deja cambios sin copiar`() {
        runBlocking { RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Puerto")) }

        awaitPending().pendingChanges shouldBe true
    }

    @Test
    fun `S013 RF-18 un ajuste cambiado deja cambios sin copiar`() {
        runBlocking { settings.stores.getValue("collector").edit { it[intPreferencesKey("collector_averaging_seconds")] = 30 } }

        awaitPending().pendingChanges shouldBe true
    }

    @Test
    fun `S013 RF-18 el idioma cambiado deja cambios sin copiar`() {
        runBlocking { changes.languageChanged() }

        pending() shouldBe true
    }

    @Test
    fun `S013 RF-18 mover el mapa no es un cambio de datos`() {
        runBlocking {
            db.mapViewports().upsert(MapViewportEntity(projectId, 40.4, -3.7, 17.0))
            delay(1_000)
        }

        pending() shouldBe false
    }
}
