package es.topophone.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import es.topophone.app.log.CrashLog
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.data.cad.CadRepository
import es.topophone.data.gpkg.GeoPackageReader
import es.topophone.data.kml.KmlReader
import es.topophone.data.photo.PhotoRepository
import es.topophone.data.shp.ShapefileReader
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@HiltAndroidApp
class TopoPhoneApplication : Application() {

    @Inject lateinit var log: AppLog

    @Inject lateinit var cad: CadRepository

    @Inject lateinit var gpkgReader: GeoPackageReader

    @Inject lateinit var shapefileReader: ShapefileReader

    @Inject lateinit var kmlReader: KmlReader

    @Inject lateinit var photos: PhotoRepository

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(log) // S014/RF-6: lo antes posible, para que ningún cierre se quede sin anotar.
        log.info(LogCategory.APP, "App iniciada: versión ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})")
        // S040/RF-12: un dibujo a medias de una importación que no acabó (la app se cerró) no se queda en la base de datos.
        CoroutineScope(Dispatchers.IO).launch { runCatching { cad.cleanUpUnfinished() } }
        // S048/RNF-T5: la copia temporal de un GeoPackage de una importación interrumpida tampoco se queda en la caché.
        CoroutineScope(Dispatchers.IO).launch { runCatching { gpkgReader.cleanUpStale() } }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { shapefileReader.cleanUpStale() }
        } // S030/RF-30: carpetas de una importación interrumpida
        // S031/RF-17: la carpeta temporal de un KML o KMZ de una importación interrumpida tampoco se queda en la caché.
        CoroutineScope(Dispatchers.IO).launch { runCatching { kmlReader.cleanUpStale() } }
        // S017b/A4: los ficheros de fotos sin fila y las fotos en espera de una medida que no acabó (la app se cerró) se borran.
        CoroutineScope(Dispatchers.IO).launch { runCatching { photos.sweep() } }
        // Al borrar un punto, un proyecto o una foto sus ficheros se quedan sin fila: se barren sin tocar lo que espera a su punto ni lo recién movido.
        CoroutineScope(Dispatchers.IO).launch {
            photos.rowCount().distinctUntilChanged().drop(1).collect {
                runCatching { photos.sweep(pendingInUse = null, minAgeMs = SWEEP_MIN_AGE_MS) }
            }
        }
    }

    private companion object {
        const val SWEEP_MIN_AGE_MS = 60_000L
    }
}
