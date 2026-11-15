package es.topophone.receiver.platform

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.Closeable
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/** Enlazar el proceso al Wi-Fi del receptor para la vista web, con o sin internet (S043/RF-15; plan D11). */
class NetworkRouterWebTest {

    /** Una fuente de redes de mentira que anota a qué red se enlaza el proceso y cuándo se suelta. */
    private class FakeSource(var canBind: Boolean = true) : NetworkSource {
        var wifiListener: NetworkSource.Listener? = null
        var internetListener: NetworkSource.Listener? = null
        val binds = mutableListOf<String?>()

        override fun watchWifi(listener: NetworkSource.Listener): Closeable {
            wifiListener = listener
            return Closeable { }
        }

        override fun watchInternet(listener: NetworkSource.Listener): Closeable {
            internetListener = listener
            return Closeable { }
        }

        override fun bindProcess(network: RoutedNetwork?): Boolean {
            binds += network?.id
            return canBind
        }
    }

    private class NoFactory : SocketFactory() {
        override fun createSocket(): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    private val source = FakeSource()
    private val routers = mutableListOf<SourceNetworkRouter>()

    private fun router(wifiWaitMs: Long = 100): SourceNetworkRouter = SourceNetworkRouter(source, wifiWaitMs).also { routers += it }

    private fun wifi(id: String) = RoutedNetwork(id, NoFactory())

    @After
    fun tearDown() = routers.forEach { it.release() }

    @Test
    fun `S043 RF-15 enlaza la red Wi-Fi aunque no tenga internet y la suelta al cerrar`() {
        val router = router()
        source.wifiListener!!.onAvailable(wifi("hotspot-rs3"))

        val binding = runBlocking { router.bindProcessToWifi() }
        source.binds shouldBe listOf("hotspot-rs3")
        router.internetAvailable.value shouldBe false

        binding.close()

        source.binds shouldBe listOf("hotspot-rs3", null)
    }

    @Test
    fun `S043 RF-15 cerrar dos veces solo suelta una`() {
        val router = router()
        source.wifiListener!!.onAvailable(wifi("hotspot-rs3"))
        val binding = runBlocking { router.bindProcessToWifi() }

        binding.close()
        binding.close()

        source.binds shouldBe listOf("hotspot-rs3", null)
    }

    @Test
    fun `S043 RF-15 sin Wi-Fi avisa y no enlaza nada`() {
        val router = router(wifiWaitMs = 50)

        shouldThrow<WifiUnavailableException> { runBlocking { router.bindProcessToWifi() } }

        source.binds shouldBe emptyList()
    }

    @Test
    fun `S043 RF-15 si el sistema no deja enlazar avisa tambien`() {
        source.canBind = false
        val router = router()
        source.wifiListener!!.onAvailable(wifi("hotspot-rs3"))

        shouldThrow<WifiUnavailableException> { runBlocking { router.bindProcessToWifi() } }
    }

    @Test
    fun `S043 RF-15 se enlaza la ultima red Wi-Fi que dio el sistema`() {
        val router = router()
        source.wifiListener!!.onAvailable(wifi("vieja"))
        source.wifiListener!!.onAvailable(wifi("nueva"))

        runBlocking { router.bindProcessToWifi() }

        source.binds shouldBe listOf("nueva")
    }

    @Test
    fun `S043 RF-15 enlazar no cambia por donde van los sockets del receptor y del caster`() {
        val router = router()
        val receiver = wifi("hotspot-rs3")
        val internet = wifi("moviles")
        source.wifiListener!!.onAvailable(receiver)
        source.internetListener!!.onAvailable(internet)
        runBlocking { router.bindProcessToWifi() }

        runBlocking { router.wifiNetwork() }.id shouldBe "hotspot-rs3"
        router.internetNetwork()?.id shouldBe "moviles"
    }

    @Test
    fun `S043 RF-15 una fuente que no sabe enlazar, como las de antes, no lo consigue`() {
        val plain = object : NetworkSource {
            override fun watchWifi(listener: NetworkSource.Listener): Closeable {
                listener.onAvailable(wifi("hotspot-rs3"))
                return Closeable { }
            }

            override fun watchInternet(listener: NetworkSource.Listener): Closeable = Closeable { }
        }
        val router = SourceNetworkRouter(plain, 50)

        shouldThrow<WifiUnavailableException> { runBlocking { router.bindProcessToWifi() } }
        router.release()
    }
}
