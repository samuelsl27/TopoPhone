package es.topophone.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.app.codes.DefaultCodeLibraries
import es.topophone.app.ui.collector.CollectorCodeSource
import es.topophone.app.ui.collector.CollectorLines
import es.topophone.app.ui.collector.RoomCollectorCodeSource
import es.topophone.app.ui.collector.RoomCollectorLines
import es.topophone.data.codes.CodeLibraryRepository
import es.topophone.data.codes.DefaultLibrarySource
import es.topophone.data.line.LineRepository
import es.topophone.data.point.PointRepository
import javax.inject.Singleton

/** Las bibliotecas de códigos que vienen con la app (S023/RF-3). */
@Module
@InstallIn(SingletonComponent::class)
object CodesModule {
    @Provides
    @Singleton
    fun provideDefaultLibrarySource(@ApplicationContext context: Context): DefaultLibrarySource = DefaultCodeLibraries(context)

    /** Los códigos que ofrece el recolector (S024/RF-1). */
    @Provides
    fun provideCollectorCodeSource(codes: CodeLibraryRepository, points: PointRepository): CollectorCodeSource =
        RoomCollectorCodeSource(codes, points)

    /** Las líneas que ve el recolector: la activa y las abiertas (S025/RF-2 a RF-9). */
    @Provides
    fun provideCollectorLines(lines: LineRepository): CollectorLines = RoomCollectorLines(lines)
}
