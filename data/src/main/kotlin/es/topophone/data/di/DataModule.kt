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
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.DataStoreProjectPreferences
import es.topophone.data.project.ProjectDataStoreQualifier
import es.topophone.data.project.ProjectPreferences
import es.topophone.data.project.ProjectRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.receiver.DataStoreReceiverPreferences
import es.topophone.data.receiver.ReceiverDataStoreQualifier
import es.topophone.data.receiver.ReceiverPreferences
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
    @ReceiverDataStoreQualifier
    fun provideReceiverDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        SettingsDataStore.create(context, name = "receiver")
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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindSettingsRepository(implementation: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindReceiverPreferences(implementation: DataStoreReceiverPreferences): ReceiverPreferences

    @Binds
    abstract fun bindProjectPreferences(implementation: DataStoreProjectPreferences): ProjectPreferences
}
