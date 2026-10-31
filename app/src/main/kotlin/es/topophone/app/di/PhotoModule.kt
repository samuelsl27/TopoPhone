package es.topophone.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import es.topophone.app.ui.photos.PhotoImages
import es.topophone.data.photo.PhotoRepository
import javax.inject.Singleton

/** La carga y la caché de las fotos de los puntos, una para toda la app (S017b plan A8). */
@Module
@InstallIn(SingletonComponent::class)
object PhotoModule {
    @Provides
    @Singleton
    fun providePhotoImages(photos: PhotoRepository): PhotoImages = PhotoImages(photos::file)
}
