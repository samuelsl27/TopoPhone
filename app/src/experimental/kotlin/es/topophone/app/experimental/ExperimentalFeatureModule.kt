package es.topophone.app.experimental

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** S044/RF-1: el flavor `experimental` enlaza las ranuras a la implementación real. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExperimentalFeatureModule {
    @Binds
    abstract fun experimentalFeature(impl: ExperimentalFeatureImpl): ExperimentalFeature
}
