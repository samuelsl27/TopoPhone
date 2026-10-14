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
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindSettingsRepository(implementation: DataStoreSettingsRepository): SettingsRepository
}
