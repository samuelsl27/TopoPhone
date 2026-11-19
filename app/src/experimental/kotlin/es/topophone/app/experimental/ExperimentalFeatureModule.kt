package es.topophone.app.experimental

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.data.settings.SettingsDataStore
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** S044/RF-1: el flavor `experimental` enlaza las ranuras a la implementación real. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExperimentalFeatureModule {
    @Binds
    abstract fun experimentalFeature(impl: ExperimentalFeatureImpl): ExperimentalFeature

    companion object {
        /** Fuera de las copias: `noBackupFilesDir` (S044/RF-4, plan D4). */
        @Provides
        @Singleton
        fun experimentalPreferences(@ApplicationContext context: Context): ExperimentalPreferences = ExperimentalPreferences(
            SettingsDataStore.create(CoroutineScope(Dispatchers.IO + SupervisorJob())) {
                ExperimentalPreferences.file(context.noBackupFilesDir)
            },
        )
    }
}
