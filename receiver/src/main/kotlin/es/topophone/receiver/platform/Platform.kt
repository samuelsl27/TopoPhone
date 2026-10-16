package es.topophone.receiver.platform

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** ¿Tiene el móvil conexión a internet? (S002/RF-23). */
fun interface NetworkStatus {
    fun hasInternet(): Boolean
}

class AndroidNetworkStatus(private val context: Context) : NetworkStatus {
    override fun hasInternet(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** Avisos al usuario además del texto (RNF-T3, RNF-T8). */
interface Alerts {
    /** Vibración al perder el receptor (S002/RF-9). */
    fun connectionLost()

    /** Sonido y vibración cuando dejan de llegar correcciones, también en segundo plano (S007/RF-29). */
    fun correctionsLost()
}

class VibrationAlerts(private val context: Context) : Alerts {
    /** Un solo generador de tonos para toda la vida de la app: crear uno por aviso agota los del sistema. */
    private val tones: ToneGenerator? by lazy { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME) }.getOrNull() }

    override fun connectionLost() {
        vibrate(LOST_PATTERN_MS)
    }

    override fun correctionsLost() {
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_MS)
        vibrate(CORRECTIONS_PATTERN_MS)
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private companion object {
        val LOST_PATTERN_MS = longArrayOf(0, 300, 150, 300)
        val CORRECTIONS_PATTERN_MS = longArrayOf(0, 150, 100, 150, 100, 150)
        const val TONE_VOLUME = 100
        const val TONE_MS = 600
    }
}
