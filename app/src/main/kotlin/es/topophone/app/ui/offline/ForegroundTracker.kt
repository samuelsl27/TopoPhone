package es.topophone.app.ui.offline

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Cuándo la app pasa a segundo plano (S037/RF-18): cuando se para la última pantalla visible, que es también lo que pasa al bloquear el móvil. Girar el móvil
 * recrea la pantalla (se para la vieja y se muestra la nueva) y no cuenta. Sin la dependencia de `lifecycle-process`, que el proyecto no tiene.
 */
class ForegroundTracker(private val onBackground: () -> Unit) {
    private val visible = mutableSetOf<Any>()

    fun started(owner: Any) {
        visible += owner
    }

    fun stopped(owner: Any, changingConfigurations: Boolean) {
        if (changingConfigurations) return
        if (visible.remove(owner) && visible.isEmpty()) onBackground()
    }

    fun destroyed(owner: Any) {
        visible.remove(owner)
    }
}

/** Engancha un [ForegroundTracker] a las pantallas de la app. */
object AppBackgroundWatcher {
    fun install(application: Application, tracker: ForegroundTracker) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = tracker.started(activity)

                override fun onActivityStopped(activity: Activity) = tracker.stopped(activity, activity.isChangingConfigurations)

                override fun onActivityDestroyed(activity: Activity) = tracker.destroyed(activity)

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            },
        )
    }
}
