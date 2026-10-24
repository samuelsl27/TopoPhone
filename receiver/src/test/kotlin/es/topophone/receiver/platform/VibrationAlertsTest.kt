package es.topophone.receiver.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Los avisos de una medida y del replanteo: cada uno vibra de forma distinta, para reconocerlo sin mirar
 * (S017/RF-8, RF-24, RF-30; S018/RF-20, RF-21; S019/RF-9; RNF-T8).
 */
@RunWith(AndroidJUnit4::class)
class VibrationAlertsTest {

    private val alerts = VibrationAlerts(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `S017 RF-8, RF-24 y RF-30 la pausa, la omision y el reinicio vibran distinto de los demas avisos`() {
        val patterns = VibrationAlerts.ALL_PATTERNS_MS.map { it.toList() }

        patterns.size shouldBe 12
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
    fun `S019 RF-9 entrar en cota y salir de ella vibran distinto entre si y de los demas avisos`() {
        val patterns = VibrationAlerts.ALL_PATTERNS_MS.map { it.toList() }
        val arrived = VibrationAlerts.LEVEL_ARRIVED_PATTERN_MS.toList()
        val left = VibrationAlerts.LEVEL_LEFT_PATTERN_MS.toList()

        arrived shouldBe listOf(0L, 90L, 50L, 90L, 50L, 200L)
        left shouldBe listOf(0L, 60L, 40L, 60L, 40L, 60L, 40L, 60L)
        arrived shouldNotBe left
        arrived shouldNotBe VibrationAlerts.ARRIVED_PATTERN_MS.toList()
        left shouldNotBe VibrationAlerts.LEFT_PATTERN_MS.toList()
        patterns.count { it == arrived } shouldBe 1
        patterns.count { it == left } shouldBe 1
        patterns.toSet().size shouldBe patterns.size
    }

    @Test
    fun `S019 RF-9 los dos avisos del nivel se pueden lanzar sin fallar`() {
        alerts.stakeoutLevelArrived()
        alerts.stakeoutLevelLeft()
    }

    @Test
    fun `S019 RF-9 un Alerts que no los implementa no hace nada, como los fakes de los tests`() {
        val fake = object : Alerts {
            override fun connectionLost() = Unit

            override fun correctionsLost() = Unit

            override fun pointSaved() = Unit

            override fun solutionLost() = Unit

            override fun tiltLost() = Unit
        }

        fake.stakeoutLevelArrived()
        fake.stakeoutLevelLeft()
    }

    @Test
    fun `S018 RF-20 y RF-21 los tres avisos del replanteo se pueden lanzar sin fallar`() {
        alerts.stakeoutApproach()
        alerts.stakeoutArrived()
        alerts.stakeoutLeft()
    }
}
