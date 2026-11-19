package es.topophone.receiver.experimental

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address

/**
 * La búsqueda de verdad, con `NsdManager` de la plataforma (S044/RF-7; plan D8): sin dependencias nuevas. El tipo de servicio
 * [SERVICE_TYPE] sale de la documentación recopilada y **no está verificado** contra un receptor real; se confirma en el campo.
 * Android solo resuelve un servicio a la vez, así que los encontrados se encolan.
 */
class NsdServiceBrowser(context: Context, private val serviceType: String = SERVICE_TYPE) : ServiceBrowser {

    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    private var discovery: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    override fun start(listener: ServiceBrowser.Listener) {
        stop()
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) { pending += serviceInfo }
                resolveNext(listener)
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = listener.onFailed()

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String?) = Unit

            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }
        synchronized(lock) { discovery = discoveryListener }
        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (_: RuntimeException) {
            synchronized(lock) { discovery = null }
            listener.onFailed()
        }
    }

    override fun stop() {
        val active = synchronized(lock) {
            pending.clear()
            discovery.also { discovery = null }
        } ?: return
        try {
            manager.stopServiceDiscovery(active)
        } catch (_: IllegalArgumentException) {
            // Ya parada o nunca arrancada: nada que hacer.
        }
    }

    @Suppress("DEPRECATION") // resolveService sigue funcionando en todas las versiones desde la 26; su sustituto empieza en la 34.
    private fun resolveNext(listener: ServiceBrowser.Listener) {
        val next = synchronized(lock) {
            if (resolving) return
            val info = pending.removeFirstOrNull() ?: return
            resolving = true
            info
        }
        try {
            manager.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = resolved(null, listener)

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) = resolved(serviceInfo, listener)
                },
            )
        } catch (_: RuntimeException) {
            resolved(null, listener)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolved(info: NsdServiceInfo?, listener: ServiceBrowser.Listener) {
        synchronized(lock) { resolving = false }
        val host = (info?.host as? Inet4Address)?.hostAddress
        if (info != null && host != null) listener.onResolved(info.serviceName, host)
        resolveNext(listener)
    }

    companion object {
        /** Tipo de servicio que anuncia el receptor (sin verificar, plan D8). */
        const val SERVICE_TYPE = "_reach._tcp."
    }
}
