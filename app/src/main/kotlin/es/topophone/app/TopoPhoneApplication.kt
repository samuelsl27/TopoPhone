package es.topophone.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import es.topophone.app.log.CrashLog
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.data.cad.CadRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class TopoPhoneApplication : Application() {

    @Inject lateinit var log: AppLog

    @Inject lateinit var cad: CadRepository

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(log) // S014/RF-6: lo antes posible, para que ningún cierre se quede sin anotar.
        log.info(LogCategory.APP, "App iniciada: versión ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})")
        // S040/RF-12: un dibujo a medias de una importación que no acabó (la app se cerró) no se queda en la base de datos.
        CoroutineScope(Dispatchers.IO).launch { runCatching { cad.cleanUpUnfinished() } }
    }
}
