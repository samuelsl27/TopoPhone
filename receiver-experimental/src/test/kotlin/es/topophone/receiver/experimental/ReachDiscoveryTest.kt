package es.topophone.receiver.experimental

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

/** S044/RF-7, RF-9, RF-10: buscar receptores en la red durante 10 s, sin repetidos ni direcciones públicas; plan D8, A5. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReachDiscoveryTest {

    private class FakeBrowser : ServiceBrowser {
        var listener: ServiceBrowser.Listener? = null
        var starts = 0
        var stops = 0

        override fun start(listener: ServiceBrowser.Listener) {
            starts++
            this.listener = listener
        }

        override fun stop() {
            stops++
        }

        fun resolved(name: String, host: String) = listener!!.onResolved(name, host)
    }

    private val scope = TestScope()
    private val browser = FakeBrowser()
    private val discovery = ReachDiscovery(browser, scope.backgroundScope)

    private fun advance(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test
    fun `S044 RF-7 al empezar busca durante 10 segundos y los resultados salen segun llegan`() {
        discovery.phase.value shouldBe DiscoveryPhase.Idle
        discovery.start()
        scope.runCurrent()

        discovery.phase.value shouldBe DiscoveryPhase.Searching
        browser.starts shouldBe 1
        browser.resolved("rs3-a", "192.168.1.50")
        discovery.found.value shouldBe listOf(FoundReceiver("rs3-a", "192.168.1.50"))

        advance(9_999)
        discovery.phase.value shouldBe DiscoveryPhase.Searching
        advance(1)
        discovery.phase.value shouldBe DiscoveryPhase.Done
        browser.stops shouldBe 1
        discovery.found.value.size shouldBe 1
    }

    @Test
    fun `S044 RF-7 un resultado que llega tras los 10 segundos ya no se anade`() {
        discovery.start()
        scope.runCurrent()
        advance(10_000)

        browser.resolved("tarde", "192.168.1.60")

        discovery.found.value shouldBe emptyList()
    }

    @Test
    fun `S044 RF-7 el mismo receptor anunciado varias veces sale una sola vez`() {
        discovery.start()
        scope.runCurrent()

        browser.resolved("rs3-a", "192.168.1.50")
        browser.resolved("rs3-a", "192.168.1.50")
        browser.resolved("rs3-b", "192.168.1.51")
        browser.resolved("rs3-a", "192.168.1.52")

        discovery.found.value shouldBe listOf(
            FoundReceiver("rs3-a", "192.168.1.50"),
            FoundReceiver("rs3-b", "192.168.1.51"),
            FoundReceiver("rs3-a", "192.168.1.52"),
        )
    }

    @Test
    fun `S044 RF-10 se descartan las direcciones que no son privadas ni de enlace local`() {
        discovery.start()
        scope.runCurrent()

        listOf("8.8.8.8", "reach.local", "127.0.0.1", "", "fe80::1", "172.32.0.1", "192.169.0.1", "10.0.0").forEach {
            browser.resolved("x", it)
        }
        listOf("192.168.1.50", "10.0.0.7", "172.16.4.2", "169.254.1.2").forEach { browser.resolved("ok-$it", it) }

        discovery.found.value.map { it.host } shouldBe listOf("192.168.1.50", "10.0.0.7", "172.16.4.2", "169.254.1.2")
    }

    @Test
    fun `S044 RF-10 la direccion se guarda sin puerto aunque venga con uno`() {
        discovery.start()
        scope.runCurrent()

        browser.resolved("rs3", "192.168.1.50:80")

        discovery.found.value shouldBe listOf(FoundReceiver("rs3", "192.168.1.50"))
    }

    @Test
    fun `S044 RF-9 sin ningun resultado a los 10 segundos el estado es ninguno`() {
        discovery.start()
        scope.runCurrent()
        browser.resolved("publico", "8.8.8.8")

        advance(10_000)

        discovery.phase.value shouldBe DiscoveryPhase.Empty
        discovery.found.value shouldBe emptyList()
    }

    @Test
    fun `S044 RF-9 un fallo del navegador termina la busqueda como ninguno al momento`() {
        discovery.start()
        scope.runCurrent()

        browser.listener!!.onFailed()

        discovery.phase.value shouldBe DiscoveryPhase.Empty
        browser.stops shouldBe 1
    }

    @Test
    fun `S044 RF-7 salir de la pantalla para la busqueda y no queda nada pendiente`() {
        discovery.start()
        scope.runCurrent()
        browser.resolved("rs3-a", "192.168.1.50")

        discovery.stop()
        advance(20_000)

        browser.stops shouldBe 1
        discovery.phase.value shouldBe DiscoveryPhase.Idle
        browser.resolved("rs3-b", "192.168.1.51")
        discovery.found.value.map { it.name } shouldBe listOf("rs3-a")
    }

    @Test
    fun `S044 RF-9 reintentar empieza de cero con una busqueda nueva`() {
        discovery.start()
        scope.runCurrent()
        browser.resolved("rs3-a", "192.168.1.50")
        advance(10_000)

        discovery.start()
        scope.runCurrent()

        discovery.found.value shouldBe emptyList()
        discovery.phase.value shouldBe DiscoveryPhase.Searching
        browser.starts shouldBe 2
        advance(9_999)
        discovery.phase.value shouldBe DiscoveryPhase.Searching
    }

    @Test
    fun `S044 RF-7 parar dos veces no falla`() {
        discovery.stop()
        discovery.start()
        scope.runCurrent()
        discovery.stop()
        discovery.stop()

        discovery.phase.value shouldBe DiscoveryPhase.Idle
    }
}
