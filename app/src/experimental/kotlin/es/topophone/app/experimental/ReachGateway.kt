package es.topophone.app.experimental

import es.topophone.receiver.experimental.LiveTransport
import es.topophone.receiver.experimental.LogsObserver
import es.topophone.receiver.experimental.OkHttpLiveTransport
import es.topophone.receiver.experimental.ReachConfigReader
import es.topophone.receiver.experimental.ReachConfigView
import es.topophone.receiver.experimental.ReachEndpoint
import es.topophone.receiver.experimental.ReachHttpClient
import es.topophone.receiver.experimental.ReachInfoReader
import es.topophone.receiver.experimental.ReachInfoReading
import es.topophone.receiver.experimental.ReachPart
import es.topophone.receiver.experimental.ReachTrail
import es.topophone.receiver.experimental.ReachWriter
import es.topophone.receiver.experimental.WriteResult
import es.topophone.receiver.platform.NetworkRouter
import es.topophone.receiver.platform.WifiSocketFactory

/**
 * Todo lo que la app pide al receptor por su red, en un solo sitio (S044/RF-11, RF-14, RF-18, RF-20): así la sesión y las pantallas
 * se prueban con una pasarela falsa y la de verdad solo hace de cola entre ellas y el módulo.
 */
interface ReachGateway {
    suspend fun readInfo(endpoint: ReachEndpoint): ReachInfoReading

    suspend fun readConfig(endpoint: ReachEndpoint): ReachPart<ReachConfigView>

    suspend fun setAntennaHeight(endpoint: ReachEndpoint, valueM: Double): WriteResult<Double>

    suspend fun setLogs(endpoint: ReachEndpoint, started: Boolean, live: LogsObserver): WriteResult<Boolean>

    /** El directo del receptor; se crea al abrir cada conexión, no al construirlo. */
    fun transport(endpoint: ReachEndpoint): LiveTransport
}

/**
 * La pasarela de verdad: cada socket sale por la red Wi-Fi del móvil aunque no tenga internet (la misma fábrica de sockets de S021) y
 * nunca por la red de datos (S044/RF-23). **No** enlaza el proceso a la red Wi-Fi, a diferencia de la vista web de S043.
 */
class NetworkReachGateway(router: NetworkRouter, private val trail: ReachTrail) : ReachGateway {

    private val sockets = WifiSocketFactory(router)

    private fun client(endpoint: ReachEndpoint) = ReachHttpClient(endpoint, sockets)

    override suspend fun readInfo(endpoint: ReachEndpoint): ReachInfoReading = ReachInfoReader(client(endpoint)).read()

    override suspend fun readConfig(endpoint: ReachEndpoint): ReachPart<ReachConfigView> = ReachConfigReader(client(endpoint), trail).read()

    override suspend fun setAntennaHeight(endpoint: ReachEndpoint, valueM: Double): WriteResult<Double> =
        ReachWriter(client(endpoint), trail).setAntennaHeight(valueM)

    override suspend fun setLogs(endpoint: ReachEndpoint, started: Boolean, live: LogsObserver): WriteResult<Boolean> =
        ReachWriter(client(endpoint), trail).setLogs(started, live)

    override fun transport(endpoint: ReachEndpoint): LiveTransport = OkHttpLiveTransport(endpoint, sockets)
}
