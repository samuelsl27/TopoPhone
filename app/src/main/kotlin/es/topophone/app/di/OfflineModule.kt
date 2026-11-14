package es.topophone.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import es.topophone.app.ui.offline.AndroidNetworkInfo
import es.topophone.app.ui.offline.AndroidStorageInfo
import es.topophone.app.ui.offline.DownloadHttpMode
import es.topophone.app.ui.offline.MapLibreOfflineStore
import es.topophone.app.ui.offline.NetworkInfo
import es.topophone.app.ui.offline.OfflineRegionStore
import es.topophone.app.ui.offline.StorageInfo
import es.topophone.app.ui.offline.ZoneHttp

/** Mapas sin conexión (S037): las regiones de MapLibre, el espacio, la red y el cliente HTTP detrás de sus contratos, que los tests sustituyen por dobles. */
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
}
