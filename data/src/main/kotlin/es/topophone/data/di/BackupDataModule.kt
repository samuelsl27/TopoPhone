package es.topophone.data.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import es.topophone.data.backup.AppLanguageStore
import es.topophone.data.backup.AutoBackupPreferences
import es.topophone.data.backup.BackupChanges
import es.topophone.data.backup.SettingsArchive
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.export.ExportDataStoreQualifier
import es.topophone.data.map.MapDataStoreQualifier
import es.topophone.data.point.CollectorDataStoreQualifier
import es.topophone.data.project.ProjectDataStoreQualifier
import es.topophone.data.receiver.ReceiverDataStoreQualifier
import es.topophone.data.settings.SettingsDataStoreQualifier
import javax.inject.Singleton

/** Los DataStores cuyos ajustes viajan en la copia, por su nombre (S013/RF-2; plan, tabla de DataStores). */
class PortableStores(val all: Map<String, DataStore<Preferences>>)

/** Ajustes que viajan en la copia y cambios sin copiar (S013). El idioma ([AppLanguageStore]) lo pone la app. */
@Module
@InstallIn(SingletonComponent::class)
object BackupDataModule {

    @Provides
    @Singleton
    fun providePortableStores(
        @SettingsDataStoreQualifier settings: DataStore<Preferences>,
        @ProjectDataStoreQualifier projects: DataStore<Preferences>,
        @CollectorDataStoreQualifier collector: DataStore<Preferences>,
        @ExportDataStoreQualifier export: DataStore<Preferences>,
        @MapDataStoreQualifier map: DataStore<Preferences>,
        @ReceiverDataStoreQualifier receiver: DataStore<Preferences>,
    ): PortableStores = PortableStores(
        mapOf(
            "settings" to settings,
            "projects" to projects,
            "collector" to collector,
            "export" to export,
            "map" to map,
            "receiver" to receiver,
        ),
    )

    @Provides
    @Singleton
    fun provideSettingsArchive(stores: PortableStores, language: AppLanguageStore): SettingsArchive = SettingsArchive(stores.all, language)

    @Provides
    @Singleton
    fun provideBackupChanges(database: TopoPhoneDatabase, stores: PortableStores, preferences: AutoBackupPreferences): BackupChanges =
        BackupChanges(database, stores.all.values, preferences)
}
