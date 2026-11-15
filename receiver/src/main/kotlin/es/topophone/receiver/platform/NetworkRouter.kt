package es.topophone.receiver.platform

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** El móvil no está en ninguna red Wi-Fi: no se puede llegar a un receptor Wi-Fi (S021/RF-14). */
class WifiUnavailableException : IOException("Wi-Fi no disponible")

/** No hay ninguna red con internet para el caster NTRIP (S021/RF-10). */
class NoInternetException : IOException("Sin red con internet")

/**
 * Una red del móvil a la que se pueden atar sockets; en Android es una `Network`, que va en [native] para poder enlazar el proceso a
 * ella (S043/RF-15).
 */
class RoutedNetwork(val id: String, val socketFactory: SocketFactory, val native: Any? = null)

enum class NetworkKind { WIFI, INTERNET }

/** Una red de [kind] ha aparecido o se ha perdido (S021/RNF-T4). */
data class NetworkChange(val kind: NetworkKind, val available: Boolean)

/**
 * Por qué red va cada tráfico (S021/RF-8, RF-9, plan D7 y D8): el receptor Wi-Fi, por el Wi-Fi aunque no tenga internet (Android no
 * suele dejarlo como red por defecto), y el caster NTRIP por una red con internet validada, que puede ser otra (los datos móviles).
 * No se ata el proceso a ninguna red: cada socket se crea en la suya.
 */
interface NetworkRouter {
    /** La red Wi-Fi del móvil, esperando un momento si aún no la ha dado el sistema; [WifiUnavailableException] si no hay. */
    suspend fun wifiNetwork(): RoutedNetwork

    /** Una red con internet validada, o null si ahora no hay ninguna. */
    fun internetNetwork(): RoutedNetwork?

    /** Cada vez que una red aparece o se pierde, al momento (S021/RNF-T4). */
    val changes: Flow<NetworkChange>

    /** Si ahora hay una red con internet; esperar a que sea cierto no pierde un aviso que llegue justo antes (S021/RF-10). */
    val internetAvailable: StateFlow<Boolean>

    /**
     * Enlaza todo el tráfico del proceso a la red Wi-Fi del móvil, aunque no tenga internet, hasta que se cierre lo que devuelve (S043/RF-15,
     * plan D11): la vista web del receptor (un `WebView` no deja elegir red) llega así a la IP del receptor. [WifiUnavailableException] si
     * no hay Wi-Fi o no se puede enlazar. La posición y el NTRIP de un receptor Wi-Fi ya van por su socket explícito y no cambian.
     */
    suspend fun bindProcessToWifi(): Closeable = throw WifiUnavailableException()

    /** Suelta las peticiones de red: ya no se necesita ninguna. */
    fun release()
}

/** Lo que el sistema da de las redes; la implementación de Android envuelve el `ConnectivityManager` y los tests usan una falsa. */
interface NetworkSource {
    interface Listener {
        fun onAvailable(network: RoutedNetwork)

        fun onLost(network: RoutedNetwork)
    }

    /** Pide una red Wi-Fi sin exigir que tenga internet y la mantiene mientras no se cierre. */
    fun watchWifi(listener: Listener): Closeable

    /** Observa las redes con internet validado. */
    fun watchInternet(listener: Listener): Closeable

    /**
     * Enlaza el proceso a [network], o lo desenlaza con `null` (S043/RF-15); `false` si no se pudo. Por defecto no se puede: quien no lo
     * necesita no tiene que implementarlo.
     */
    fun bindProcess(network: RoutedNetwork?): Boolean = false
}

class SourceNetworkRouter(private val source: NetworkSource, private val wifiWaitMs: Long = WIFI_WAIT_MS) : NetworkRouter {
    private val wifi = MutableStateFlow<RoutedNetwork?>(null)
    private val internet = MutableStateFlow<RoutedNetwork?>(null)
    private val mutableChanges = MutableSharedFlow<NetworkChange>(extraBufferCapacity = CHANGE_BUFFER)
    override val changes: Flow<NetworkChange> = mutableChanges.asSharedFlow()
    private val hasInternet = MutableStateFlow(false)
    override val internetAvailable: StateFlow<Boolean> = hasInternet.asStateFlow()

    private val watches = listOf(
        source.watchWifi(listenerFor(wifi, NetworkKind.WIFI)),
        source.watchInternet(listenerFor(internet, NetworkKind.INTERNET)),
    )

    private fun listenerFor(holder: MutableStateFlow<RoutedNetwork?>, kind: NetworkKind) = object : NetworkSource.Listener {
        override fun onAvailable(network: RoutedNetwork) {
            holder.value = network // la última manda
            if (kind == NetworkKind.INTERNET) hasInternet.value = true
            mutableChanges.tryEmit(NetworkChange(kind, available = true))
        }

        override fun onLost(network: RoutedNetwork) {
            // Perder una red vieja no quita la nueva.
            if (holder.value?.id == network.id) {
                holder.value = null
                if (kind == NetworkKind.INTERNET) hasInternet.value = false
                mutableChanges.tryEmit(NetworkChange(kind, available = false))
            }
        }
    }

    override suspend fun wifiNetwork(): RoutedNetwork =
        wifi.value ?: withTimeoutOrNull(wifiWaitMs) { wifi.filterNotNull().first() } ?: throw WifiUnavailableException()

    override fun internetNetwork(): RoutedNetwork? = internet.value

    override suspend fun bindProcessToWifi(): Closeable {
        val network = wifiNetwork()
        if (!source.bindProcess(network)) throw WifiUnavailableException()
        val unbound = AtomicBoolean(false)
        return Closeable { if (unbound.compareAndSet(false, true)) source.bindProcess(null) }
    }

    override fun release() {
        watches.forEach { runCatching { it.close() } }
    }

    companion object {
        /** Lo que se espera a que el sistema dé la red Wi-Fi antes de decir que no hay. */
        const val WIFI_WAIT_MS = 3_000L
        private const val CHANGE_BUFFER = 16
    }
}

/**
 * Sockets para el caster NTRIP y la tabla de fuentes con un receptor Wi-Fi: cada uno se crea en una red con internet y, si no hay
 * ninguna, falla con [NoInternetException] (S021/RF-9, RF-10). Con Bluetooth no se usa: queda la fábrica de siempre.
 */
class RoutedSocketFactory(private val router: NetworkRouter) : SocketFactory() {
    override fun createSocket(): Socket = (router.internetNetwork() ?: throw NoInternetException()).socketFactory.createSocket()

    override fun createSocket(host: String?, port: Int): Socket =
        (router.internetNetwork() ?: throw NoInternetException()).socketFactory.createSocket(host, port)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        (router.internetNetwork() ?: throw NoInternetException()).socketFactory.createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        (router.internetNetwork() ?: throw NoInternetException()).socketFactory.createSocket(host, port)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        (router.internetNetwork() ?: throw NoInternetException()).socketFactory.createSocket(address, port, localAddress, localPort)
}

/**
 * Sockets del receptor Wi-Fi: cada uno se crea en la red Wi-Fi del móvil aunque no tenga internet (S021/RF-8). Se pide la red al abrir,
 * no al construir el transporte, así que una reconexión con el Wi-Fi apagado falla con [WifiUnavailableException] y se reintenta
 * (S021/RF-15). Bloquea hasta 3 s: se llama desde el hilo de E/S de la apertura.
 */
class WifiSocketFactory(private val router: NetworkRouter) : SocketFactory() {
    private fun network() = runBlocking { router.wifiNetwork() }.socketFactory

    override fun createSocket(): Socket = network().createSocket()

    override fun createSocket(host: String?, port: Int): Socket = network().createSocket(host, port)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        network().createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket = network().createSocket(host, port)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        network().createSocket(address, port, localAddress, localPort)
}

/** Las redes de Android: una petición de Wi-Fi sin exigir internet y una observación de las redes con internet validado. */
@SuppressLint("MissingPermission") // CHANGE_NETWORK_STATE y ACCESS_NETWORK_STATE están en el manifiesto del módulo
class AndroidNetworkSource(context: Context) : NetworkSource {
    private val manager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    override fun watchWifi(listener: NetworkSource.Listener): Closeable {
        val manager = manager ?: return Closeable { }
        val callback = callbackFor(listener)
        // Sin NET_CAPABILITY_INTERNET: el hotspot de un receptor no tiene internet y aun así es la red que se necesita (plan D7).
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        manager.requestNetwork(request, callback)
        return Closeable { runCatching { manager.unregisterNetworkCallback(callback) } }
    }

    override fun watchInternet(listener: NetworkSource.Listener): Closeable {
        val manager = manager ?: return Closeable { }
        val callback = callbackFor(listener)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        manager.registerNetworkCallback(request, callback)
        return Closeable { runCatching { manager.unregisterNetworkCallback(callback) } }
    }

    private fun callbackFor(listener: NetworkSource.Listener) = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = listener.onAvailable(network.routed())

        override fun onLost(network: Network) = listener.onLost(network.routed())
    }

    private fun Network.routed() = RoutedNetwork(networkHandle.toString(), socketFactory, this)

    override fun bindProcess(network: RoutedNetwork?): Boolean {
        val manager = manager ?: return false
        return runCatching { manager.bindProcessToNetwork(network?.native as Network?) }.getOrDefault(false)
    }
}

/** El enrutador de la app: las redes de Android. */
class AndroidNetworkRouter(context: Context) : NetworkRouter by SourceNetworkRouter(AndroidNetworkSource(context))
