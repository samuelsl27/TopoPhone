package es.topophone.core.survey.guide

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.rtcm.RtcmStats
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La ventana de 10 s de épocas y de estadísticas RTCM (S043/RF-5, plan D1 y A1). */
class ObservationWindowTest {

    private fun epoch(atMs: Long, utcMs: Int = (atMs / 1000 * 1000).toInt()) = Epoch(
        utcMillisOfDay = utcMs,
        date = null,
        gga = null,
        gst = null,
        rmc = null,
        vtg = null,
        zda = null,
        basePosition = null,
        tilt = null,
        gsa = emptyMap(),
        gsv = emptyMap(),
        completedAtMs = atMs,
    )

    private fun stats(vararg counts: Pair<Int, Long>) = RtcmStats(counts.toMap())

    @Test
    fun `S043 RF-5 guarda las epocas de los ultimos 10 s en orden y olvida las anteriores`() {
        val window = ObservationWindow()
        for (second in 0..15) window.onEpoch(epoch(second * 1_000L))

        val seen = window.snapshot(nowMs = 15_000).epochs.map { it.completedAtMs }

        seen shouldBe (5..15).map { it * 1_000L }
    }

    @Test
    fun `S043 RF-5 una consulta mas tarde recorta lo que ya no cae en la ventana aunque no llegue nada`() {
        val window = ObservationWindow()
        for (second in 0..4) window.onEpoch(epoch(second * 1_000L))

        window.snapshot(nowMs = 4_000).epochs.size shouldBe 5
        window.snapshot(nowMs = 12_500).epochs.map { it.completedAtMs } shouldBe listOf(3_000L, 4_000L)
        window.snapshot(nowMs = 30_000).epochs.size shouldBe 0
    }

    @Test
    fun `S043 RF-5 el delta de RTCM es lo que llego entre la primera y la ultima foto`() {
        val window = ObservationWindow()
        window.onRtcm(stats(1005 to 1, 1077 to 10), nowMs = 1_000)
        window.onRtcm(stats(1005 to 2, 1077 to 15), nowMs = 2_000)
        window.onRtcm(stats(1005 to 3, 1077 to 20, 1087 to 5), nowMs = 3_000)

        val delta = window.snapshot(nowMs = 3_000).rtcmDelta.shouldNotBeNull()

        delta[1005] shouldBe 2
        delta[1077] shouldBe 10
        delta[1087] shouldBe 5
    }

    @Test
    fun `S043 RF-5 un tipo que no ha llegado de nuevo no sale en el delta`() {
        val window = ObservationWindow()
        window.onRtcm(stats(1005 to 4, 1077 to 10), nowMs = 1_000)
        window.onRtcm(stats(1005 to 4, 1077 to 30), nowMs = 5_000)

        val delta = window.snapshot(nowMs = 5_000).rtcmDelta.shouldNotBeNull()

        delta.containsKey(1005) shouldBe false
        delta[1077] shouldBe 20
    }

    @Test
    fun `S043 RF-5 con menos de dos fotos no hay correcciones que mirar`() {
        val window = ObservationWindow()
        window.snapshot(nowMs = 0).rtcmDelta.shouldBeNull()

        window.onRtcm(stats(1005 to 1), nowMs = 1_000)

        window.snapshot(nowMs = 1_000).rtcmDelta.shouldBeNull()
    }

    @Test
    fun `S043 RF-5 como mucho una foto por segundo`() {
        val window = ObservationWindow()
        window.onRtcm(stats(1077 to 1), nowMs = 1_000)
        window.onRtcm(stats(1077 to 2), nowMs = 1_200)
        window.onRtcm(stats(1077 to 3), nowMs = 1_800)

        window.snapshot(nowMs = 1_800).rtcmDelta.shouldBeNull() // las dos últimas se descartaron: solo hay una foto
        window.onRtcm(stats(1077 to 4), nowMs = 2_000)
        window.snapshot(nowMs = 2_000).rtcmDelta?.get(1077) shouldBe 3
    }

    @Test
    fun `S043 RF-5 si el total baja se abrio otro NTRIP y se empieza de nuevo`() {
        val window = ObservationWindow()
        window.onRtcm(stats(1077 to 50), nowMs = 1_000)
        window.onRtcm(stats(1077 to 60), nowMs = 2_000)

        window.onRtcm(stats(1077 to 2), nowMs = 3_000) // reconexión: contadores a cero
        window.onRtcm(stats(1077 to 7), nowMs = 4_000)

        window.snapshot(nowMs = 4_000).rtcmDelta?.get(1077) shouldBe 5
    }

    @Test
    fun `S043 RF-5 las fotos viejas tambien se olvidan`() {
        val window = ObservationWindow()
        window.onRtcm(stats(1005 to 1), nowMs = 1_000)
        window.onRtcm(stats(1005 to 2), nowMs = 2_000)

        window.snapshot(nowMs = 30_000).rtcmDelta.shouldBeNull()
    }

    @Test
    fun `S043 RF-5 al desconectar se borra todo y sin NTRIP solo se borran las RTCM`() {
        val window = ObservationWindow()
        window.onEpoch(epoch(1_000))
        window.onRtcm(stats(1005 to 1), nowMs = 1_000)
        window.onRtcm(stats(1005 to 2), nowMs = 2_000)

        window.clearRtcm()
        window.snapshot(nowMs = 2_000).epochs.size shouldBe 1
        window.snapshot(nowMs = 2_000).rtcmDelta.shouldBeNull()

        window.reset()
        window.snapshot(nowMs = 2_000).epochs.size shouldBe 0
    }

    @Test
    fun `S043 RF-5 una ventana mas corta recorta antes`() {
        val window = ObservationWindow(windowMs = 3_000)
        for (second in 0..10) window.onEpoch(epoch(second * 1_000L))

        window.snapshot(nowMs = 10_000).epochs.map { it.completedAtMs } shouldBe listOf(7_000L, 8_000L, 9_000L, 10_000L)
    }
}
