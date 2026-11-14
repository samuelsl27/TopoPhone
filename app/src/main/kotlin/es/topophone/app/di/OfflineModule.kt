package es.topophone.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import es.topophone.app.ui.offline.MapLibreOfflineStore
import es.topophone.app.ui.offline.OfflineRegionStore

/** Mapas sin conexión (S037): las regiones de MapLibre detrás de su contrato, que los tests sustituyen por un doble. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineModule {
    @Binds
    abstract fun bindRegionStore(store: MapLibreOfflineStore): OfflineRegionStore
}
