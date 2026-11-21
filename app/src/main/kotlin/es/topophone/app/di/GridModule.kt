package es.topophone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.app.BuildConfig
import es.topophone.core.survey.LibraryResources
import es.topophone.data.grids.GridDownloader
import es.topophone.data.grids.GridStore
import es.topophone.data.grids.OkHttpGridHttp
import es.topophone.data.project.ProjectRepository
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Las rejillas y los geoides de los SC de la biblioteca (S034): las descargadas viven en el almacenamiento privado de la app y el cálculo las lee
 * de ahí (RF-12, RF-24); la descarga va por la red con internet, con la identificación de la app (RNF-T4, ADR-13).
 */
@Module
@InstallIn(SingletonComponent::class)
object GridModule {

    @Provides
    @Singleton
    fun provideGridStore(@ApplicationContext context: Context, projects: ProjectRepository): GridStore {
        val directory = File(context.filesDir, "grids").apply { mkdirs() }
        // Los proyectos que no están en la papelera, para saber qué rejillas usan (RF-23).
        val store = GridStore(directory) { projects.search("").first().map { it.project } }
        LibraryResources.current = store // el cálculo de los SC de la biblioteca lee de aquí
        return store
    }

    @Provides
    @Singleton
    fun provideGridDownloader(store: GridStore): GridDownloader =
        GridDownloader(store, OkHttpGridHttp(OkHttpGridHttp.create("TopoPhone/${BuildConfig.VERSION_NAME}")))
}
