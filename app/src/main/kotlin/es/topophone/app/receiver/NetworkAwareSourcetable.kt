package es.topophone.app.receiver

import es.topophone.core.ntrip.SourcetableFailure
import es.topophone.core.ntrip.SourcetableResult
import es.topophone.core.ntrip.SourcetableSource
import es.topophone.receiver.platform.NoInternetException

/**
 * La tabla de fuentes del caster por la red que toca (S021/RF-9): con un receptor Wi-Fi, [routed] (sockets en una red con internet, no en
 * la del receptor); con Bluetooth o sin receptor, [default], sin cambios. Sin red con internet no se puede contactar con el caster.
 */
class NetworkAwareSourcetable(
    private val default: SourcetableSource,
    private val routed: SourcetableSource,
    private val wifiReceiver: () -> Boolean,
) : SourcetableSource {
    override suspend fun fetch(host: String, port: Int, username: String, password: String, useTls: Boolean): SourcetableResult {
        if (!wifiReceiver()) return default.fetch(host, port, username, password, useTls)
        return try {
            routed.fetch(host, port, username, password, useTls)
        } catch (_: NoInternetException) {
            SourcetableResult.Failed(SourcetableFailure.UNREACHABLE) // S021/RF-10
        }
    }
}
