package es.topophone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.app.BuildConfig
import es.topophone.app.receiver.AndroidNotificationPermission
import es.topophone.app.receiver.CorrectionsController
import es.topophone.app.receiver.NotificationPermission
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.receiver.ReceiverService
import es.topophone.app.ui.receiver.ReceiverViewModel
import es.topophone.core.model.log.AppLog
import es.topophone.core.ntrip.NtripClient
import es.topophone.core.ntrip.SourcetableClient
import es.topophone.core.ntrip.SourcetableSource
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.ntrip.NtripProfileRepository
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.data.receiver.SessionSpace
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.platform.AndroidNetworkStatus
import es.topophone.receiver.platform.NetworkStatus
import es.topophone.receiver.platform.VibrationAlerts
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.session.SessionLogStore
import es.topophone.receiver.transport.AndroidBluetoothDevices
import es.topophone.receiver.transport.AndroidReceiverPairing
import es.topophone.receiver.transport.BluetoothDevices
import es.topophone.receiver.transport.ReceiverPairing
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hilo para el trabajo con ficheros (importar sesiones…). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dependencias de la cadena receptor → NTRIP → coordenadas (spec 002). La sesión es de la app. */
@Module
@InstallIn(SingletonComponent::class)
object ReceiverModule {

    /** El límite de espacio sale de Ajustes y, al cambiarlo, se aplica al momento (S003/RF-25, S003/RF-26). */
    @Provides
    @Singleton
    fun sessionLogStore(@ApplicationContext context: Context, preferences: ReceiverPreferences): SessionLogStore {
        val limitMb = AtomicInteger(SessionSpace.DEFAULT_MB)
        val store = SessionLogStore(File(context.filesDir, SESSIONS_DIR), spaceLimitBytes = { limitMb.get() * SessionSpace.BYTES_PER_MB })
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            preferences.sessionSpaceMb.collect { mb ->
                limitMb.set(mb)
                store.applySpaceLimit()
            }
        }
        return store
    }

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    fun receiverSession(store: SessionLogStore): ReceiverSession = ReceiverSession(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ntripConnector = NtripClient(userAgent = "NTRIP TopoPhone/${BuildConfig.VERSION_NAME}"),
        recorder = store,
    )

    /** Único punto para conectar con un receptor real (S004). */
    @Provides
    @Singleton
    fun receiverConnections(
        @ApplicationContext context: Context,
        session: ReceiverSession,
        bluetooth: BluetoothDevices,
        preferences: ReceiverPreferences,
    ): ReceiverConnections = ReceiverConnections(session, bluetooth, preferences, CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
        ReceiverService.start(context) // S004/RF-7
    }

    /** Tabla de fuentes del caster, con la misma identificación que la conexión NTRIP (S007/RF-14). */
    @Provides
    fun sourcetableSource(): SourcetableSource = SourcetableClient(userAgent = "NTRIP TopoPhone/${BuildConfig.VERSION_NAME}")

    /** Conexión NTRIP con perfiles (S007): vive con la app para abrirse sola al conectar el receptor. */
    @Provides
    @Singleton
    fun correctionsController(
        session: ReceiverSession,
        profiles: NtripProfileRepository,
        preferences: ReceiverPreferences,
        log: AppLog,
    ): CorrectionsController =
        CorrectionsController(session, profiles, preferences, CoroutineScope(SupervisorJob() + Dispatchers.Default), log)

    @Provides
    @Singleton
    fun replayController(session: ReceiverSession, store: SessionLogStore): ReplayController =
        ReplayController(session) { store.sessions() }

    @Provides
    @Singleton
    fun bluetoothDevices(@ApplicationContext context: Context): BluetoothDevices = AndroidBluetoothDevices(context)

    @Provides
    fun receiverPairing(@ApplicationContext context: Context): ReceiverPairing = AndroidReceiverPairing(context)

    @Provides
    @Singleton
    fun groundPointCalculator(): GroundPointCalculator = GroundPointCalculator()

    /** Posición en el SC del proyecto (S006/RF-29); el geoide se carga la primera vez que se usa. */
    @Provides
    @Singleton
    fun projectPositionCalculator(): ProjectPositionCalculator = ProjectPositionCalculator()

    @Provides
    fun networkStatus(@ApplicationContext context: Context): NetworkStatus = AndroidNetworkStatus(context)

    @Provides
    fun alerts(@ApplicationContext context: Context): Alerts = VibrationAlerts(context)

    @Provides
    fun notificationPermission(@ApplicationContext context: Context): NotificationPermission = AndroidNotificationPermission(context)

    @Provides
    @Named(ReceiverViewModel.MONOTONIC_CLOCK)
    fun monotonicClock(): () -> Long = { System.nanoTime() / 1_000_000 }

    /** RNF-T13: la interfaz se refresca como mucho a 5 Hz. */
    @Provides
    @Named(ReceiverViewModel.UI_SAMPLE_MS)
    fun uiSamplePeriodMs(): Long = 200L

    /** Carpeta de sesiones, compartida con el FileProvider (`res/xml/file_paths.xml`). */
    const val SESSIONS_DIR = "sessions"
}
