package es.topophone.receiver.platform

import android.content.Context
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

/** Avisos al usuario además del texto: vibración al perder el receptor (S002/RF-9, RNF-T3). */
fun interface Alerts {
    fun connectionLost()
}

class VibrationAlerts(private val context: Context) : Alerts {
    override fun connectionLost() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(LOST_PATTERN_MS, -1))
    }

    private companion object {
        val LOST_PATTERN_MS = longArrayOf(0, 300, 150, 300)
    }
}
