package es.topophone.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import es.topophone.app.BuildConfig
import es.topophone.data.wms.OkHttpWmsHttp
import es.topophone.data.wms.WmsHttp
import es.topophone.data.wms.WmsHttpClient
import es.topophone.data.wms.WmsLibraryRepository
import es.topophone.data.wms.WmsServiceAdder
import javax.inject.Singleton

/** Los servicios WMS (S036): su acceso a la red, con la identificación de la app, y el alta de servicios. */
@Module
@InstallIn(SingletonComponent::class)
object WmsModule {

    @Provides
    @Singleton
    fun provideWmsHttp(): WmsHttp = OkHttpWmsHttp(WmsHttpClient.create("TopoPhone/${BuildConfig.VERSION_NAME}"))

    @Provides
    @Singleton
    fun provideWmsServiceAdder(http: WmsHttp, library: WmsLibraryRepository): WmsServiceAdder = WmsServiceAdder(http, library)
}
