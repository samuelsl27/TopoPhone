package es.topophone.app.receiver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.session.ReceiverSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Acceso a la sesión del receptor desde el servicio (y desde sus tests). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReceiverServiceEntryPoint {
    fun session(): ReceiverSession

    fun alerts(): Alerts
}

/**
 * Mantiene la conexión con el receptor, el NTRIP y la grabación en segundo plano, con una
 * notificación permanente (S004/RF-7, RF-8; RNF-T2). Se detiene en cuanto no hay receptor
 * conectado ni reconectándose (S004/RF-11), también al pulsar "Desconectar" en la notificación (S004/RF-10).
 *
 * Con la pantalla apagada, un wakelock parcial mantiene la CPU despierta (S004/RF-7). Se toma con un
 * plazo de 10 min que se renueva cada 5 min: si algo fallase sin soltarlo, caduca solo.
 */
class ReceiverService : Service() {

    private lateinit var session: ReceiverSession
    private lateinit var alerts: Alerts
    private var alarmCollector: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var collector: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val renewWakeLock = object : Runnable {
        override fun run() {
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            main.postDelayed(this, WAKE_LOCK_RENEW_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, ReceiverServiceEntryPoint::class.java)
        session = entryPoint.session()
        alerts = entryPoint.alerts()
        ReceiverNotifications.createChannel(this)
    }

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            session.disconnect() // S004/RF-10: cierra el receptor y el NTRIP
            stop()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            ReceiverNotifications.NOTIFICATION_ID,
            ReceiverNotifications.build(this, session.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        holdWakeLock()
        if (collector == null) {
            // RNF de la spec: la notificación se actualiza como mucho una vez por segundo.
            collector = scope.launch {
                merge(flowOf(session.state.value), session.state.sample(UPDATE_PERIOD_MS)).collect { snapshot ->
                    if (needsService(snapshot)) {
                        ReceiverNotifications.notify(this@ReceiverService, snapshot)
                    } else {
                        main.post(::stop) // S004/RF-11
                    }
                }
            }
        }
        if (alarmCollector == null) {
            // S007/RF-29: sonido y vibración desde el servicio, también con la app en segundo plano.
            alarmCollector = scope.launch { correctionsAlarmRises(session.state).collect { alerts.correctionsLost() } }
        }
        // Si Android mata el proceso, la sesión se pierde con él: no tiene sentido recrear el servicio solo.
        return START_NOT_STICKY
    }

    private fun holdWakeLock() {
        if (wakeLock != null) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        renewWakeLock.run()
    }

    private fun releaseWakeLock() {
        main.removeCallbacks(renewWakeLock)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun stop() {
        collector?.cancel()
        collector = null
        alarmCollector?.cancel()
        alarmCollector = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val UPDATE_PERIOD_MS = 1_000L
        private const val WAKE_LOCK_TAG = "TopoPhone:receiver"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        private const val WAKE_LOCK_RENEW_MS = 5 * 60_000L

        /** Acción "Desconectar" de la notificación (S004/RF-10). */
        const val ACTION_DISCONNECT = "es.topophone.app.action.DISCONNECT_RECEIVER"

        /** Hace falta el servicio mientras hay un receptor real conectado o reconectándose. */
        fun needsService(snapshot: ReceiverSnapshot): Boolean {
            val connection = snapshot.connection
            val active = connection is ConnectionState.Connecting ||
                connection is ConnectionState.Receiving ||
                connection is ConnectionState.NoData ||
                connection is ConnectionState.Reconnecting
            return active && snapshot.replay == null
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
        }
    }
}
