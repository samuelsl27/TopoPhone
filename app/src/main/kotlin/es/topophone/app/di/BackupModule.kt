package es.topophone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.app.BuildConfig
import es.topophone.app.backup.AppCompatLanguageStore
import es.topophone.app.backup.AutoBackupScheduler
import es.topophone.data.backup.AppLanguageStore
import es.topophone.data.backup.AutoBackupPreferences
import es.topophone.data.backup.BackupChanges
import es.topophone.data.backup.BackupFolder
import es.topophone.data.backup.BackupRepository
import es.topophone.data.backup.Backups
import es.topophone.data.backup.DocumentsBackupFolder
import es.topophone.data.backup.SettingsArchive
import es.topophone.data.db.TopoPhoneDatabase
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Ámbito de corrutinas que vive lo que el proceso: lo que no debe pararse al cerrar una pantalla (S013). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Copias de seguridad, paquetes y copias automáticas (S013). */
@Module
@InstallIn(SingletonComponent::class)
object BackupModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideAppLanguageStore(): AppLanguageStore = AppCompatLanguageStore()

    @Provides
    @Singleton
    fun provideBackupRepository(
        @ApplicationContext context: Context,
        database: TopoPhoneDatabase,
        settings: SettingsArchive,
    ): BackupRepository = BackupRepository(context, database, settings, appVersion = BuildConfig.VERSION_NAME)

    @Provides
    fun provideBackups(repository: BackupRepository): Backups = repository

    @Provides
    @Singleton
    fun provideBackupFolder(@ApplicationContext context: Context): BackupFolder = DocumentsBackupFolder(context.contentResolver)

    @Provides
    @Singleton
    fun provideAutoBackupScheduler(
        @ApplicationContext context: Context,
        repository: BackupRepository,
        folder: BackupFolder,
        preferences: AutoBackupPreferences,
        changes: BackupChanges,
        @ApplicationScope scope: CoroutineScope,
    ): AutoBackupScheduler = AutoBackupScheduler(
        createBackup = { repository.createBackupFile(it) },
        folder = folder,
        preferences = preferences,
        workDir = File(context.cacheDir, "backup-auto"),
        scope = scope,
        onStart = { changes.start(it) },
    )
}
