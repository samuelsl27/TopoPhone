package es.topophone.receiver.platform

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** La red por la que va cada tráfico: el receptor por el Wi-Fi aunque no tenga internet y el caster por otra (S021/RF-8, RF-9, RNF-T4). */
class NetworkRouterTest {

    /** Una fuente de redes de mentira: el test decide cuándo aparece o se pierde cada una. */
    private class FakeNetworkSource : NetworkSource {
        var wifiListener: NetworkSource.Listener? = null
        var internetListener: NetworkSource.Listener? = null
        var wifiClosed = false
        var internetClosed = false

        override fun watchWifi(listener: NetworkSource.Listener): Closeable {
            wifiListener = listener
            return Closeable { wifiClosed = true }
        }

        override fun watchInternet(listener: NetworkSource.Listener): Closeable {
            internetListener = listener
            return Closeable { internetClosed = true }
        }
    }

    private class FakeFactory(val name: String) : SocketFactory() {
        var created = 0

        override fun createSocket(): Socket {
            created++
            return Socket()
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()

        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            throw UnsupportedOperationException()
    }

    private val source = FakeNetworkSource()
    private val routers = mutableListOf<SourceNetworkRouter>()
    private val wifi = RoutedNetwork("wifi-1", FakeFactory("wifi"))
    private val mobile = RoutedNetwork("mobile-1", FakeFactory("mobile"))

    @After
    fun tearDown() = routers.forEach { it.release() }

    private fun router(wifiWaitMs: Long = 150) = SourceNetworkRouter(source, wifiWaitMs).also { routers += it }

    @Test
    fun `S021 RF-8 el Wi-Fi sin internet se usa para el receptor y el caster pide otra red`() = runBlocking<Unit> {
        val router = router()
        source.wifiListener!!.onAvailable(wifi) // el Wi-Fi del receptor: sin internet
        source.internetListener!!.onAvailable(mobile) // los datos moviles

        router.wifiNetwork() shouldBe wifi
        router.internetNetwork() shouldBe mobile
        (router.wifiNetwork().socketFactory as FakeFactory).name shouldBe "wifi"
        (router.internetNetwork()!!.socketFactory as FakeFactory).name shouldBe "mobile"
    }

    @Test
    fun `S021 RF-8 sin Wi-Fi tras la espera se lanza WifiUnavailableException`() = runBlocking<Unit> {
        val router = router(wifiWaitMs = 100)

        shouldThrow<WifiUnavailableException> { router.wifiNetwork() }
    }

    @Test
    fun `S021 RF-8 un Wi-Fi que aparece durante la espera se usa`() = runBlocking<Unit> {
        val router = router(wifiWaitMs = 2_000)

        val pending = async { router.wifiNetwork() }
        kotlinx.coroutines.delay(100)
        source.wifiListener!!.onAvailable(wifi)

        withTimeout(3_000) { pending.await() } shouldBe wifi
    }

    @Test
    fun `S021 RF-8 al perder el Wi-Fi ya no hay red y se avisa del cambio en menos de 3 segundos`() = runBlocking<Unit> {
        val router = router(wifiWaitMs = 50)
        source.wifiListener!!.onAvailable(wifi)
        router.wifiNetwork() shouldBe wifi
        val seen = async { router.changes.first { it == NetworkChange(NetworkKind.WIFI, available = false) } }
        kotlinx.coroutines.yield()

        val start = System.nanoTime()
        source.wifiListener!!.onLost(wifi)
        withTimeout(3_000) { seen.await() }

        ((System.nanoTime() - start) / 1_000_000) shouldBeLessThan 3_000L
        shouldThrow<WifiUnavailableException> { router.wifiNetwork() }
    }

    @Test
    fun `S021 RF-9 sin red con internet no hay red para el caster y al aparecer una se avisa`() = runBlocking<Unit> {
        val router = router()
        router.internetNetwork().shouldBeNull()
        val appeared = async { router.changes.first { it == NetworkChange(NetworkKind.INTERNET, available = true) } }
        kotlinx.coroutines.yield()

        source.internetListener!!.onAvailable(mobile)

        withTimeout(3_000) { appeared.await() }
        router.internetNetwork() shouldBe mobile
    }

    @Test
    fun `S021 RF-10 internetAvailable sigue a la red con internet y no pierde un aviso anterior a esperarlo`() = runBlocking<Unit> {
        val router = router()
        router.internetAvailable.value shouldBe false

        source.internetListener!!.onAvailable(mobile) // llega antes de que nadie espere
        withTimeout(3_000) { router.internetAvailable.first { it } }

        router.internetAvailable.value shouldBe true
        source.internetListener!!.onLost(mobile)
        router.internetAvailable.value shouldBe false
    }

    @Test
    fun `S021 RF-9 al perder la red con internet se avisa y deja de haber`() = runBlocking<Unit> {
        val router = router()
        source.internetListener!!.onAvailable(mobile)
        router.internetNetwork() shouldBe mobile
        val lost = async { router.changes.first { it == NetworkChange(NetworkKind.INTERNET, available = false) } }
        kotlinx.coroutines.yield()

        source.internetListener!!.onLost(mobile)

        withTimeout(3_000) { lost.await() }
        router.internetNetwork().shouldBeNull()
    }

    @Test
    fun `S021 RF-8 perder una red vieja no quita la nueva`() = runBlocking<Unit> {
        val router = router()
        val other = RoutedNetwork("wifi-2", FakeFactory("wifi2"))
        source.wifiListener!!.onAvailable(wifi)
        source.wifiListener!!.onAvailable(other)

        source.wifiListener!!.onLost(wifi)

        router.wifiNetwork() shouldBe other
    }

    @Test
    fun `S021 RNF-T4 soltar el enrutador cierra las dos peticiones de red`() {
        val router = router()

        router.release()

        source.wifiClosed shouldBe true
        source.internetClosed shouldBe true
    }

    @Test
    fun `S021 RF-9 la fabrica de sockets del caster crea en la red con internet y sin ella falla`() = runBlocking<Unit> {
        val router = router()
        val factory = RoutedSocketFactory(router)

        shouldThrow<NoInternetException> { factory.createSocket() }
        source.internetListener!!.onAvailable(mobile)
        factory.createSocket().close()

        (mobile.socketFactory as FakeFactory).created shouldBe 1
        (wifi.socketFactory as FakeFactory).created shouldBe 0
        (NoInternetException() is IOException) shouldBe true
    }

    @Test
    fun `S021 RF-8 los cambios se emiten a varios observadores`() = runBlocking<Unit> {
        val router = router()
        val a = async { router.changes.first() }
        val b = async { router.changes.first() }
        kotlinx.coroutines.delay(50)

        source.internetListener!!.onAvailable(mobile)

        withTimeout(3_000) { a.await() } shouldBe NetworkChange(NetworkKind.INTERNET, available = true)
        withTimeout(3_000) { b.await() } shouldBe NetworkChange(NetworkKind.INTERNET, available = true)
    }
}
