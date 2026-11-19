package es.topophone.app.experimental

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.app.receiver.CurrentGear
import es.topophone.app.receiver.CurrentTilt
import es.topophone.core.model.log.AppLog
import es.topophone.data.equipment.EquipmentRepository
import es.topophone.data.receiver.WifiReceiverRepository
import es.topophone.data.settings.SettingsDataStore
import es.topophone.receiver.experimental.NsdServiceBrowser
import es.topophone.receiver.experimental.ReachAudit
import es.topophone.receiver.experimental.ServiceBrowser
import es.topophone.receiver.platform.NetworkRouter
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

/** S044/RF-1: el flavor `experimental` enlaza las ranuras a la implementación real. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExperimentalFeatureModule {
    @Binds
    abstract fun experimentalFeature(impl: ExperimentalFeatureImpl): ExperimentalFeature

    companion object {
        /** La pasarela de verdad: sockets por la red Wi-Fi del receptor y rastro en el log de la app (S044/RF-23, RF-24). */
        @Provides
        @Singleton
        fun reachGateway(router: NetworkRouter, log: AppLog): ReachGateway = NetworkReachGateway(router, ReachAudit(log))

        /** La búsqueda de receptores de verdad, con `NsdManager` (S044/RF-7); uno por pantalla, sin compartir. */
        @Provides
        fun serviceBrowser(@ApplicationContext context: Context): ServiceBrowser = NsdServiceBrowser(context)

        /** El modo de inclinación y el jalón activo del receptor en uso, para proponer la altura de antena (S044/RF-18). */
        @Provides
        @Singleton
        fun antennaContext(currentTilt: CurrentTilt, currentGear: CurrentGear): AntennaContext =
            CurrentAntennaContext(currentTilt, currentGear, CoroutineScope(SupervisorJob() + Dispatchers.Default))

        /** Una sola sesión del módulo para toda la app (S044/RF-5, RF-11, RF-16). */
        @Provides
        @Singleton
        fun experimentalSession(
            preferences: ExperimentalPreferences,
            currentTilt: CurrentTilt,
            wifi: WifiReceiverRepository,
            equipment: EquipmentRepository,
            gateway: ReachGateway,
        ): ExperimentalSession = ExperimentalSession(
            active = preferences.acceptedAtMs.map { it != null },
            key = currentTilt.state.map { it.key },
            wifi = wifi,
            equipment = equipment,
            gateway = gateway,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

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
