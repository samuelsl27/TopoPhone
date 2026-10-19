package es.topophone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.core.model.log.AppLog
import es.topophone.data.log.FileAppLog
import es.topophone.data.log.LogPreferences
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/** El log de la app en `filesDir/log/`, con el tamaño elegido en Ajustes (S014). */
@Module
@InstallIn(SingletonComponent::class)
object LogModule {

    @Provides
    @Singleton
    fun provideFileAppLog(
        @ApplicationContext context: Context,
        preferences: LogPreferences,
        @ApplicationScope scope: CoroutineScope,
    ): FileAppLog = FileAppLog(File(context.filesDir, "log"), preferences.sizeMb, scope)

    @Provides
    fun provideAppLog(log: FileAppLog): AppLog = log
}
