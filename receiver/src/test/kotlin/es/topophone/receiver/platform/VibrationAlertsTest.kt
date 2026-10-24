package es.topophone.receiver.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los avisos de una medida y del replanteo: cada uno vibra de forma distinta, para reconocerlo sin mirar
 * (S017/RF-8, RF-24, RF-30; S018/RF-20, RF-21; RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
class VibrationAlertsTest {

    private val alerts = VibrationAlerts(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `S017 RF-8, RF-24 y RF-30 la pausa, la omision y el reinicio vibran distinto de los demas avisos`() {
        val patterns = VibrationAlerts.ALL_PATTERNS_MS.map { it.toList() }

        patterns.size shouldBe 10
        patterns.toSet().size shouldBe patterns.size
        VibrationAlerts.PAUSED_PATTERN_MS.toList() shouldBe listOf(0L, 250L, 120L, 250L)
    }

    @Test
    fun `S017 RF-8, RF-24 y RF-30 los tres avisos nuevos se pueden lanzar sin fallar`() {
        alerts.measurementPaused()
        alerts.pointOmitted()
        alerts.measurementRestarted()
    }

    @Test
    fun `S018 RF-20 y RF-21 el aviso de acercarse, el de llegar y el de irse vibran distinto entre si y de los demas`() {
        val patterns = VibrationAlerts.ALL_PATTERNS_MS.map { it.toList() }

        VibrationAlerts.APPROACH_PATTERN_MS.toList() shouldBe listOf(0L, 40L, 80L, 40L)
        VibrationAlerts.ARRIVED_PATTERN_MS.toList() shouldBe listOf(0L, 200L)
        VibrationAlerts.LEFT_PATTERN_MS.toList() shouldBe listOf(0L, 100L, 60L, 100L, 60L, 100L)
        patterns.toSet().size shouldBe patterns.size
    }

    @Test
    fun `S018 RF-20 y RF-21 los tres avisos del replanteo se pueden lanzar sin fallar`() {
        alerts.stakeoutApproach()
        alerts.stakeoutArrived()
        alerts.stakeoutLeft()
    }
}
