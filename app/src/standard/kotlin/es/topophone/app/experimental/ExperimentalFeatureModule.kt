package es.topophone.app.experimental

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** S044/RF-1: el flavor `standard` enlaza las ranuras a una implementación que no hace nada. */
@Module
@InstallIn(SingletonComponent::class)
object ExperimentalFeatureModule {
    @Provides
    @Singleton
    fun experimentalFeature(): ExperimentalFeature = NoExperimentalFeature()
}
