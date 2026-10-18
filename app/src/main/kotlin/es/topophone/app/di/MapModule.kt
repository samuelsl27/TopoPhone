package es.topophone.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import es.topophone.app.BuildConfig
import es.topophone.app.ui.map.MapHttp
import es.topophone.app.ui.map.MapHttpClient
import es.topophone.app.ui.map.NetworkFailureMonitor
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** Mapa del proyecto (S009). */
@Module
@InstallIn(SingletonComponent::class)
object MapModule {

    @Provides
    @Singleton
    @MapHttp
    fun provideMapHttpClient(monitor: NetworkFailureMonitor): OkHttpClient = MapHttpClient.create(BuildConfig.VERSION_NAME, monitor)
}
