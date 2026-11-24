package es.topophone.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import es.topophone.app.ui.projects.ActiveSources
import es.topophone.app.ui.projects.RepositoryActiveSources
import es.topophone.data.alignment.AlignmentRepository
import es.topophone.data.baseshift.BaseShiftRepository
import es.topophone.data.localization.LocalizationRepository
import es.topophone.data.surface.SurfaceRepository
import es.topophone.data.wms.ProjectLayersRepository

/** Lo que está activo en cada proyecto, para «Datos del proyecto» (S059/RF-18). */
@Module
@InstallIn(SingletonComponent::class)
object ProjectInfoModule {
    @Provides
    fun provideActiveSources(
        localization: LocalizationRepository,
        baseShift: BaseShiftRepository,
        surfaces: SurfaceRepository,
        alignments: AlignmentRepository,
        layers: ProjectLayersRepository,
    ): ActiveSources = RepositoryActiveSources(localization, baseShift, surfaces, alignments, layers)
}
