package es.topophone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.app.BuildConfig
import es.topophone.app.ui.receiver.ReceiverViewModel
import es.topophone.core.ntrip.NtripClient
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.platform.AndroidNetworkStatus
import es.topophone.receiver.platform.NetworkStatus
import es.topophone.receiver.platform.SessionFiles
import es.topophone.receiver.platform.VibrationAlerts
import es.topophone.receiver.platform.asSessionFiles
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.SessionLogStore
import es.topophone.receiver.transport.AndroidBluetoothDevices
import es.topophone.receiver.transport.BluetoothDevices
import java.io.File
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Dependencias de la cadena receptor → NTRIP → coordenadas (spec 002). La sesión es de la app. */
@Module
@InstallIn(SingletonComponent::class)
object ReceiverModule {

    @Provides
    @Singleton
    fun sessionLogStore(@ApplicationContext context: Context): SessionLogStore = SessionLogStore(File(context.filesDir, SESSIONS_DIR))

    @Provides
    fun sessionFiles(store: SessionLogStore): SessionFiles = store.asSessionFiles()

    @Provides
    @Singleton
    fun receiverSession(store: SessionLogStore): ReceiverSession = ReceiverSession(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ntripConnector = NtripClient(userAgent = "NTRIP TopoPhone/${BuildConfig.VERSION_NAME}"),
        recorder = store,
    )

    @Provides
    @Singleton
    fun bluetoothDevices(@ApplicationContext context: Context): BluetoothDevices = AndroidBluetoothDevices(context)

    @Provides
    @Singleton
    fun groundPointCalculator(): GroundPointCalculator = GroundPointCalculator()

    @Provides
    fun networkStatus(@ApplicationContext context: Context): NetworkStatus = AndroidNetworkStatus(context)

    @Provides
    fun alerts(@ApplicationContext context: Context): Alerts = VibrationAlerts(context)

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
