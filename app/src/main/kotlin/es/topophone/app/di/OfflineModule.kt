package es.topophone.app.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import es.topophone.app.ui.offline.AndroidNetworkInfo
import es.topophone.app.ui.offline.AndroidStorageInfo
import es.topophone.app.ui.offline.DownloadHttpMode
import es.topophone.app.ui.offline.MapLibreOfflineStore
import es.topophone.app.ui.offline.NetworkInfo
import es.topophone.app.ui.offline.OfflineDownloader
import es.topophone.app.ui.offline.OfflineRegionStore
import es.topophone.app.ui.offline.ProjectIds
import es.topophone.app.ui.offline.RepositoryProjectIds
import es.topophone.app.ui.offline.RepositoryZoneInputs
import es.topophone.app.ui.offline.StorageInfo
import es.topophone.app.ui.offline.SystemZoneClock
import es.topophone.app.ui.offline.ZoneClock
import es.topophone.app.ui.offline.ZoneHttp
import es.topophone.app.ui.offline.ZoneInputs
import es.topophone.core.model.log.AppLog
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Mapas sin conexión (S037): las regiones de MapLibre, el espacio, la red, el cliente HTTP y el proyecto detrás de sus contratos, que los tests sustituyen por dobles. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineModule {
    @Binds
    abstract fun bindRegionStore(store: MapLibreOfflineStore): OfflineRegionStore

    @Binds
    abstract fun bindStorageInfo(info: AndroidStorageInfo): StorageInfo

    @Binds
    abstract fun bindNetworkInfo(info: AndroidNetworkInfo): NetworkInfo

    @Binds
    abstract fun bindHttpMode(zoneHttp: ZoneHttp): DownloadHttpMode

    @Binds
    abstract fun bindZoneInputs(inputs: RepositoryZoneInputs): ZoneInputs

    @Binds
    abstract fun bindZoneClock(clock: SystemZoneClock): ZoneClock

    @Binds
    abstract fun bindProjectIds(ids: RepositoryProjectIds): ProjectIds

    companion object {
        /** Una sola descarga a la vez en toda la app: sigue aunque se cierre la pantalla que la empezó y vive tanto como el proceso. */
        @Provides
        @Singleton
        fun provideDownloader(store: OfflineRegionStore, log: AppLog, http: DownloadHttpMode, clock: ZoneClock): OfflineDownloader =
            OfflineDownloader(
                store = store,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                clockMs = clock::nowMs,
                log = log,
                http = http,
            )
    }
}
