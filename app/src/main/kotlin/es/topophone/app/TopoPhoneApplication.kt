package es.topophone.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import es.topophone.app.log.CrashLog
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import javax.inject.Inject

@HiltAndroidApp
class TopoPhoneApplication : Application() {

    @Inject lateinit var log: AppLog

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(log) // S014/RF-6: lo antes posible, para que ningún cierre se quede sin anotar.
        log.info(LogCategory.APP, "App iniciada: versión ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT})")
    }
}
