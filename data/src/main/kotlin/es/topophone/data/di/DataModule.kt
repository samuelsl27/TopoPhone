package es.topophone.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.core.model.log.AppLog
import es.topophone.data.backup.AutoBackupPreferences
import es.topophone.data.backup.BackupDataStoreQualifier
import es.topophone.data.backup.DataStoreAutoBackupPreferences
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.export.DataStoreExportPreferences
import es.topophone.data.export.ExportDataStoreQualifier
import es.topophone.data.export.ExportPreferences
import es.topophone.data.log.DataStoreLogPreferences
import es.topophone.data.log.LogPreferences
import es.topophone.data.map.DataStoreMapPreferences
import es.topophone.data.map.MapDataStoreQualifier
import es.topophone.data.map.MapPreferences
import es.topophone.data.map.MapViewportRepository
import es.topophone.data.map.RoomMapViewportRepository
import es.topophone.data.ntrip.NtripLegacyImport
import es.topophone.data.ntrip.NtripProfileRepository
import es.topophone.data.ntrip.RoomNtripProfileRepository
import es.topophone.data.point.CollectorDataStoreQualifier
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.DataStoreCollectorPreferences
import es.topophone.data.point.PointRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.DataStoreProjectPreferences
import es.topophone.data.project.ProjectDataStoreQualifier
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.receiver.DataStoreReceiverPreferences
import es.topophone.data.receiver.DataStoreTiltPreferences
import es.topophone.data.receiver.ReceiverDataStoreQualifier
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.data.receiver.TiltPreferences
import es.topophone.data.secret.AesGcmCipher
import es.topophone.data.secret.AndroidKeystoreKeyProvider
import es.topophone.data.settings.DataStoreSettingsRepository
import es.topophone.data.settings.SettingsDataStore
import es.topophone.data.settings.SettingsDataStoreQualifier
import es.topophone.data.settings.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    @SettingsDataStoreQualifier
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> = SettingsDataStore.create(context)

    @Provides
    @Singleton
    @ProjectDataStoreQualifier
    fun provideProjectDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SettingsDataStore.create(context, name = "projects")

    @Provides
    @Singleton
    @CollectorDataStoreQualifier
    fun provideCollectorDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SettingsDataStore.create(context, name = "collector")

    @Provides
    @Singleton
    @ExportDataStoreQualifier
    fun provideExportDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SettingsDataStore.create(context, name = "export")

    @Provides
    @Singleton
    @MapDataStoreQualifier
    fun provideMapDataStore(@ApplicationContext context: Context): DataStore<Preferences> = SettingsDataStore.create(context, name = "map")

    @Provides
    @Singleton
    @ReceiverDataStoreQualifier
    fun provideReceiverDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SettingsDataStore.create(context, name = "receiver")

    /** Copias automáticas de este móvil (S013): no viaja en la copia. */
    @Provides
    @Singleton
    @BackupDataStoreQualifier
    fun provideBackupDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SettingsDataStore.create(context, name = "backup")
}

/** Base de datos de proyectos (ADR-8) y su repositorio (S006). */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TopoPhoneDatabase = TopoPhoneDatabase.build(context)

    @Provides
    @Singleton
    fun provideProjectRepository(database: TopoPhoneDatabase): ProjectRepository = RoomProjectRepository(database)

    /** Perfiles NTRIP con la contraseña cifrada con una clave del Android Keystore (S007, RNF-T11). */
    @Provides
    @Singleton
    fun provideNtripProfileRepository(database: TopoPhoneDatabase): NtripProfileRepository =
        RoomNtripProfileRepository(database, AesGcmCipher(AndroidKeystoreKeyProvider()))

    /** Puntos recolectados (S010). */
    @Provides
    @Singleton
    fun providePointRepository(database: TopoPhoneDatabase, log: AppLog): PointRepository = RoomPointRepository(database, log = log)

    /** Último encuadre del mapa de cada proyecto (S009/RF-21). */
    @Provides
    @Singleton
    fun provideMapViewportRepository(database: TopoPhoneDatabase): MapViewportRepository = RoomMapViewportRepository(database)

    @Provides
    fun provideNtripLegacyImport(preferences: ReceiverPreferences, profiles: NtripProfileRepository): NtripLegacyImport =
        NtripLegacyImport(preferences, profiles)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindSettingsRepository(implementation: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindReceiverPreferences(implementation: DataStoreReceiverPreferences): ReceiverPreferences

    @Binds
    abstract fun bindTiltPreferences(implementation: DataStoreTiltPreferences): TiltPreferences

    @Binds
    abstract fun bindProjectPreferences(implementation: DataStoreProjectPreferences): ProjectPreferences

    @Binds
    abstract fun bindMapPreferences(implementation: DataStoreMapPreferences): MapPreferences

    @Binds
    abstract fun bindCollectorPreferences(implementation: DataStoreCollectorPreferences): CollectorPreferences

    @Binds
    abstract fun bindExportPreferences(implementation: DataStoreExportPreferences): ExportPreferences

    @Binds
    abstract fun bindAutoBackupPreferences(implementation: DataStoreAutoBackupPreferences): AutoBackupPreferences

    @Binds
    abstract fun bindLogPreferences(implementation: DataStoreLogPreferences): LogPreferences
}
