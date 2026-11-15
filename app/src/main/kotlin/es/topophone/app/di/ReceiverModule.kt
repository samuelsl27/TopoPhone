package es.topophone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.app.BuildConfig
import es.topophone.app.receiver.AndroidLocalNetworkPermission
import es.topophone.app.receiver.AndroidNotificationPermission
import es.topophone.app.receiver.CorrectionsController
import es.topophone.app.receiver.CurrentEquipment
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.receiver.CurrentTilt
import es.topophone.app.receiver.LocalNetworkPermission
import es.topophone.app.receiver.NetworkAwareSourcetable
import es.topophone.app.receiver.NotificationPermission
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.receiver.ReceiverService
import es.topophone.app.receiver.ReceiverWindowRecorder
import es.topophone.app.receiver.RepositoryVirtualOrigins
import es.topophone.app.receiver.ResourceSourceLabels
import es.topophone.app.receiver.VirtualOrigins
import es.topophone.app.ui.receiver.ReceiverViewModel
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.ntrip.NtripClient
import es.topophone.core.ntrip.SourcetableClient
import es.topophone.core.ntrip.SourcetableSource
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.equipment.EquipmentRepository
import es.topophone.data.gear.GearRepository
import es.topophone.data.ntrip.NtripProfileRepository
import es.topophone.data.receiver.ReceiverPreferences
import es.topophone.data.receiver.SessionSpace
import es.topophone.data.receiver.TiltPreferences
import es.topophone.data.receiver.WifiReceiverRepository
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.platform.AndroidNetworkRouter
import es.topophone.receiver.platform.AndroidNetworkStatus
import es.topophone.receiver.platform.NetworkRouter
import es.topophone.receiver.platform.NetworkStatus
import es.topophone.receiver.platform.RoutedSocketFactory
import es.topophone.receiver.platform.VibrationAlerts
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReplayController
import es.topophone.receiver.session.SessionLogStore
import es.topophone.receiver.transport.AndroidBluetoothDevices
import es.topophone.receiver.transport.AndroidLocationSource
import es.topophone.receiver.transport.AndroidReceiverPairing
import es.topophone.receiver.transport.BluetoothDevices
import es.topophone.receiver.transport.LocationSource
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    /** Por qué red va cada tráfico con un receptor Wi-Fi: el receptor por el Wi-Fi y el caster por una red con internet (S021/RF-8, RF-9). */
    @Provides
    @Singleton
    fun networkRouter(@ApplicationContext context: Context): NetworkRouter = AndroidNetworkRouter(context)

    @Provides
    @Singleton
    fun receiverSession(store: SessionLogStore, router: NetworkRouter): ReceiverSession {
        val userAgent = "NTRIP TopoPhone/${BuildConfig.VERSION_NAME}"
        return ReceiverSession(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ntripConnector = NtripClient(userAgent = userAgent),
            recorder = store,
            // S021/RF-9: con un receptor Wi-Fi el caster sale por una red con internet; con Bluetooth, sin cambios.
            networkNtripConnector = NtripClient(userAgent = userAgent, socketFactory = RoutedSocketFactory(router)),
            networkRouter = router,
        )
    }

    /** Único punto para conectar con un receptor real (S004). */
    @Provides
    @Singleton
    fun receiverConnections(
        @ApplicationContext context: Context,
        session: ReceiverSession,
        bluetooth: BluetoothDevices,
        preferences: ReceiverPreferences,
        wifiReceivers: WifiReceiverRepository,
        router: NetworkRouter,
        locationSource: LocationSource,
    ): ReceiverConnections = ReceiverConnections(
        session,
        bluetooth,
        preferences,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        wifiReceivers,
        router,
        locationSource = locationSource, // S022/RF-1
        labels = ResourceSourceLabels(context),
    ) { key ->
        ReceiverService.start(context, location = ReceiverKey.isPhone(key)) // S004/RF-7, S022/RF-4
    }

    /** De dónde sale el origen por defecto del receptor virtual: el centro del proyecto más reciente (S022/RF-13). */
    @Provides
    fun virtualOrigins(origins: RepositoryVirtualOrigins): VirtualOrigins = origins

    /** El GNSS del móvil: lo usan la conexión y la petición del permiso (S022/RF-2, RF-3, RF-5). */
    @Provides
    @Singleton
    fun locationSource(@ApplicationContext context: Context): LocationSource = AndroidLocationSource(context)

    /** El receptor en uso y su modo de inclinación, uno para toda la app (S016/RF-1; plan A8). */
    @Provides
    @Singleton
    fun currentTilt(session: ReceiverSession, connections: ReceiverConnections, preferences: TiltPreferences): CurrentTilt = CurrentTilt(
        replaying = session.state.map { it.replay != null }.distinctUntilChanged(),
        address = connections.currentAddress,
        preferences = preferences,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** El equipo (perfil de antena y jalón) del receptor en uso, uno para toda la app (S046/RF-13; plan A3). */
    @Provides
    @Singleton
    fun currentGear(currentTilt: CurrentTilt, repository: GearRepository): CurrentGear = CurrentGear(
        key = currentTilt.state.map { it.key }.distinctUntilChanged(),
        repository = repository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** La ventana de los últimos 10 s de épocas y correcciones, una para toda la app (S043/RF-5; plan D2). */
    @Provides
    @Singleton
    fun receiverWindowRecorder(session: ReceiverSession): ReceiverWindowRecorder =
        ReceiverWindowRecorder(session, CoroutineScope(SupervisorJob() + Dispatchers.Default), monotonicClock())

    /** Los datos del receptor en uso (modelo, serie y firmware), uno para toda la app (S043/RF-19; plan D7). */
    @Provides
    @Singleton
    fun currentEquipment(currentTilt: CurrentTilt, repository: EquipmentRepository): CurrentEquipment = CurrentEquipment(
        key = currentTilt.state.map { it.key }.distinctUntilChanged(),
        repository = repository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** Tabla de fuentes del caster, con la misma identificación que la conexión NTRIP (S007/RF-14). */
    @Provides
    fun sourcetableSource(connections: ReceiverConnections, router: NetworkRouter): SourcetableSource {
        val userAgent = "NTRIP TopoPhone/${BuildConfig.VERSION_NAME}"
        return NetworkAwareSourcetable(
            default = SourcetableClient(userAgent = userAgent),
            routed = SourcetableClient(userAgent = userAgent, socketFactory = RoutedSocketFactory(router)),
            wifiReceiver = { connections.currentAddress.value?.let(ReceiverKey::isWifi) == true }, // S021/RF-9
        )
    }

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

    /** El permiso de red local de Android 17+, que solo pide un receptor Wi-Fi (S021/RF-6). */
    @Provides
    fun localNetworkPermission(@ApplicationContext context: Context): LocalNetworkPermission = AndroidLocalNetworkPermission(context)

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
