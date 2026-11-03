package es.topophone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.app.codes.DefaultCodeLibraries
import es.topophone.data.codes.DefaultLibrarySource
import javax.inject.Singleton

/** Las bibliotecas de códigos que vienen con la app (S023/RF-3). */
@Module
@InstallIn(SingletonComponent::class)
object CodesModule {
    @Provides
    @Singleton
    fun provideDefaultLibrarySource(@ApplicationContext context: Context): DefaultLibrarySource = DefaultCodeLibraries(context)
}
