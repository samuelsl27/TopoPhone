package es.topophone.app.receiver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Permiso de notificaciones, que Android exige desde la versión 13 (S004/RF-5, RF-6). */
interface NotificationPermission {
    /** En Android 12 o anterior las notificaciones no necesitan permiso. */
    val required: Boolean

    fun isGranted(): Boolean

    companion object {
        /** Android 12 o anterior: no hay nada que pedir. */
        val NotRequired: NotificationPermission = object : NotificationPermission {
            override val required = false

            override fun isGranted() = true
        }
    }
}

class AndroidNotificationPermission(private val context: Context) : NotificationPermission {
    override val required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override fun isGranted(): Boolean = !required ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/** Ajustes de notificaciones de la app, donde se concede el permiso tras denegarlo (S004/RF-6). */
fun notificationSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
