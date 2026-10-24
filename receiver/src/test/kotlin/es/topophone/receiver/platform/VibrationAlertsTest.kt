package es.topophone.receiver.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith

/** Los avisos de una medida: cada uno vibra de forma distinta, para reconocerlo sin mirar (S017/RF-8, RF-24, RF-30; RNF-T8). */
@RunWith(AndroidJUnit4::class)
class VibrationAlertsTest {

    private val alerts = VibrationAlerts(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `S017 RF-8, RF-24 y RF-30 la pausa, la omision y el reinicio vibran distinto de los demas avisos`() {
        val patterns = VibrationAlerts.ALL_PATTERNS_MS.map { it.toList() }

        patterns.size shouldBe 7
        patterns.toSet().size shouldBe patterns.size
        VibrationAlerts.PAUSED_PATTERN_MS.toList() shouldBe listOf(0L, 250L, 120L, 250L)
    }

    @Test
    fun `S017 RF-8, RF-24 y RF-30 los tres avisos nuevos se pueden lanzar sin fallar`() {
        alerts.measurementPaused()
        alerts.pointOmitted()
        alerts.measurementRestarted()
    }
}
