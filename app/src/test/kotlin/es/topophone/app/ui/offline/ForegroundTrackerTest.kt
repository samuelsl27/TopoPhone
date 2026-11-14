package es.topophone.app.ui.offline

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Cuándo la app pasa a segundo plano: cuando se para la última pantalla visible, no al girar el móvil (S037/RF-18). */
class ForegroundTrackerTest {

    private var backgrounded = 0
    private val tracker = ForegroundTracker { backgrounded++ }

    @Test
    fun `S037 RF-18 al pararse la unica pantalla la app pasa a segundo plano`() {
        val screen = Any()
        tracker.started(screen)

        tracker.stopped(screen, changingConfigurations = false)

        backgrounded shouldBe 1
    }

    @Test
    fun `S037 RF-18 con dos pantallas solo cuenta cuando se para la ultima`() {
        val first = Any()
        val second = Any()
        tracker.started(first)
        tracker.started(second) // la segunda se muestra antes de que la primera se pare

        tracker.stopped(first, changingConfigurations = false)
        backgrounded shouldBe 0
        tracker.stopped(second, changingConfigurations = false)

        backgrounded shouldBe 1
    }

    @Test
    fun `S037 RF-18 girar el movil no pausa la descarga`() {
        val before = Any()
        val after = Any()
        tracker.started(before)

        tracker.stopped(before, changingConfigurations = true)
        tracker.started(after)
        tracker.destroyed(before)

        backgrounded shouldBe 0
        tracker.stopped(after, changingConfigurations = false)
        backgrounded shouldBe 1
    }

    @Test
    fun `S037 RF-18 una pantalla que no se habia mostrado no cuenta`() {
        tracker.stopped(Any(), changingConfigurations = false)

        backgrounded shouldBe 0
    }

    @Test
    fun `S037 RF-18 volver a la app y salir otra vez la pausa otra vez`() {
        val screen = Any()
        tracker.started(screen)
        tracker.stopped(screen, changingConfigurations = false)

        tracker.started(screen)
        tracker.stopped(screen, changingConfigurations = false)

        backgrounded shouldBe 2
    }
}
