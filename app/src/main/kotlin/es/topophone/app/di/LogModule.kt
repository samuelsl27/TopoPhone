package es.topophone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.app.log.AndroidDeviceInfo
import es.topophone.app.log.ReceiverLogRecorder
import es.topophone.app.log.SystemReportWriter
import es.topophone.app.log.ZipSystemReportWriter
import es.topophone.core.model.log.AppLog
import es.topophone.data.backup.SettingsArchive
import es.topophone.data.log.FileAppLog
import es.topophone.data.log.LogPreferences
import es.topophone.data.ntrip.NtripProfileRepository
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.SessionLogStore
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

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

    /** El informe de sistema con las fuentes reales (S014/RF-10 a RF-17). */
    @Provides
    @Singleton
    fun provideSystemReportWriter(
        @ApplicationContext context: Context,
        receiver: ReceiverPreferences,
        settings: SettingsArchive,
        profiles: NtripProfileRepository,
        store: SessionLogStore,
        log: FileAppLog,
    ): SystemReportWriter {
        val device = AndroidDeviceInfo(context, receiver)
        return ZipSystemReportWriter(
            device = device::read,
            settings = settings::export,
            profiles = { profiles.profiles().first() },
            sessions = store::sessions,
            activeSessionId = store::activeSessionId,
            logFiles = log::files,
            flushLog = log::flushNow,
        )
    }

    @Provides
    @Singleton
    fun provideReceiverLogRecorder(session: ReceiverSession, log: AppLog, @ApplicationScope scope: CoroutineScope): ReceiverLogRecorder =
        ReceiverLogRecorder(session.state, log, scope)
}
