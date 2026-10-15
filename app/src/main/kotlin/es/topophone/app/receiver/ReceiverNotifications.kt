package es.topophone.app.receiver

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import es.topophone.app.R
import es.topophone.app.ui.receiver.nameRes
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import java.util.Locale

/** Notificación permanente del receptor: la solución y la edad de las correcciones (S004/RF-8). */
object ReceiverNotifications {
    const val NOTIFICATION_ID = 1001
    private const val CHANNEL_ID = "receiver"

    fun createChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_receiver))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun build(context: Context, snapshot: ReceiverSnapshot): Notification {
        val description = when (val connection = snapshot.connection) {
            is ConnectionState.Connecting -> connection.description
            is ConnectionState.Receiving -> connection.description
            is ConnectionState.NoData -> connection.description
            is ConnectionState.Reconnecting -> connection.description
            else -> ""
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_receiver)
            .setContentTitle(context.getString(R.string.notification_title, description))
            .setContentText(text(context, snapshot))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Actualiza la notificación; sin permiso de notificaciones no se ve, pero la conexión sigue (S004/RF-6). */
    fun notify(context: Context, snapshot: ReceiverSnapshot) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, snapshot))
    }

    /** "Fija · correcciones 1,0 s", "Sin datos", "Reconectando · intento 3"… */
    fun text(context: Context, snapshot: ReceiverSnapshot): String {
        val gga = snapshot.gga
        return when (val connection = snapshot.connection) {
            is ConnectionState.Connecting -> context.getString(R.string.notification_connecting)
            is ConnectionState.NoData -> context.getString(R.string.notification_no_data)
            is ConnectionState.Reconnecting -> context.getString(R.string.notification_reconnecting, connection.attempt)
            is ConnectionState.Receiving -> when {
                gga == null -> context.getString(R.string.notification_receiving)
                gga.correctionAgeS == null -> context.getString(gga.status.nameRes())
                else -> context.getString(
                    R.string.notification_solution_age,
                    context.getString(gga.status.nameRes()),
                    String.format(locale(context), "%.1f", gga.correctionAgeS),
                )
            }
            else -> ""
        }
    }

    private fun locale(context: Context): Locale = context.resources.configuration.locales[0]
}
