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

    /** Punto guardado (S010/RF-19). */
    fun pointSaved()

    /** Se ha perdido el FIX durante un promediado (S010/RF-10). */
    fun solutionLost()

    /** Se descartan épocas de una medida por la inclinación (S016/RF-19). */
    fun tiltLost()

    /** Un promediado o el modo automático se pausan por un corte (S017/RF-8, RF-28). Sin hacer nada por defecto, para los fakes. */
    fun measurementPaused() = Unit

    /** El modo automático omite un punto: una vez hasta que se guarda otro (S017/RF-24). */
    fun pointOmitted() = Unit

    /** Un promediado empieza de nuevo porque cambió la base (S017/RF-30). */
    fun measurementRestarted() = Unit

    /** La punta entra en la zona de ayuda del punto de diseño, a 40 cm o menos (S018/RF-21). */
    fun stakeoutApproach() = Unit

    /** La punta entra en la tolerancia del punto de diseño (S018/RF-20). */
    fun stakeoutArrived() = Unit

    /** La punta sale de la tolerancia del punto de diseño (S018/RF-20). */
    fun stakeoutLeft() = Unit

    /** La punta entra en cota: dentro de la tolerancia vertical de la cota de diseño (S019/RF-9). */
    fun stakeoutLevelArrived() = Unit

    /** La punta sale de la cota de diseño, más allá de la tolerancia vertical (S019/RF-9). */
    fun stakeoutLevelLeft() = Unit
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

    override fun pointSaved() {
        tones?.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MS)
        vibrate(SAVED_PATTERN_MS)
    }

    override fun solutionLost() {
        tones?.startTone(ToneGenerator.TONE_SUP_ERROR, TONE_MS)
        vibrate(LOST_PATTERN_MS)
    }

    override fun tiltLost() {
        tones?.startTone(ToneGenerator.TONE_PROP_NACK, TONE_MS)
        vibrate(TILT_PATTERN_MS)
    }

    override fun measurementPaused() {
        tones?.startTone(ToneGenerator.TONE_SUP_PIP, TONE_MS)
        vibrate(PAUSED_PATTERN_MS)
    }

    override fun pointOmitted() {
        tones?.startTone(ToneGenerator.TONE_PROP_PROMPT, TONE_MS)
        vibrate(OMITTED_PATTERN_MS)
    }

    override fun measurementRestarted() {
        tones?.startTone(ToneGenerator.TONE_SUP_CONGESTION, TONE_MS)
        vibrate(RESTARTED_PATTERN_MS)
    }

    override fun stakeoutApproach() {
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS)
        vibrate(APPROACH_PATTERN_MS)
    }

    override fun stakeoutArrived() {
        tones?.startTone(ToneGenerator.TONE_CDMA_CONFIRM, TONE_MS)
        vibrate(ARRIVED_PATTERN_MS)
    }

    override fun stakeoutLeft() {
        tones?.startTone(ToneGenerator.TONE_CDMA_REORDER, TONE_MS)
        vibrate(LEFT_PATTERN_MS)
    }

    override fun stakeoutLevelArrived() {
        tones?.startTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, TONE_MS)
        vibrate(LEVEL_ARRIVED_PATTERN_MS)
    }

    override fun stakeoutLevelLeft() {
        tones?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, TONE_MS)
        vibrate(LEVEL_LEFT_PATTERN_MS)
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

    internal companion object {
        val LOST_PATTERN_MS = longArrayOf(0, 300, 150, 300)
        val SAVED_PATTERN_MS = longArrayOf(0, 80)
        val CORRECTIONS_PATTERN_MS = longArrayOf(0, 150, 100, 150, 100, 150)
        val TILT_PATTERN_MS = longArrayOf(0, 120, 80, 120)
        val PAUSED_PATTERN_MS = longArrayOf(0, 250, 120, 250)
        val OMITTED_PATTERN_MS = longArrayOf(0, 60, 60, 60)
        val RESTARTED_PATTERN_MS = longArrayOf(0, 400, 150, 100, 150, 400)
        val APPROACH_PATTERN_MS = longArrayOf(0, 40, 80, 40)
        val ARRIVED_PATTERN_MS = longArrayOf(0, 200)
        val LEFT_PATTERN_MS = longArrayOf(0, 100, 60, 100, 60, 100)
        val LEVEL_ARRIVED_PATTERN_MS = longArrayOf(0, 90, 50, 90, 50, 200) // S019/RF-9
        val LEVEL_LEFT_PATTERN_MS = longArrayOf(0, 60, 40, 60, 40, 60, 40, 60)

        /** Todas las vibraciones, para comprobar que cada aviso se distingue de los demás. */
        val ALL_PATTERNS_MS = listOf(
            LOST_PATTERN_MS,
            SAVED_PATTERN_MS,
            CORRECTIONS_PATTERN_MS,
            TILT_PATTERN_MS,
            PAUSED_PATTERN_MS,
            OMITTED_PATTERN_MS,
            RESTARTED_PATTERN_MS,
            APPROACH_PATTERN_MS,
            ARRIVED_PATTERN_MS,
            LEFT_PATTERN_MS,
            LEVEL_ARRIVED_PATTERN_MS,
            LEVEL_LEFT_PATTERN_MS,
        )
        const val TONE_VOLUME = 100
        const val TONE_MS = 600
    }
}
