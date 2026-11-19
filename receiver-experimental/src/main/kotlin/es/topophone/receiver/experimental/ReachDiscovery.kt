package es.topophone.receiver.experimental

import es.topophone.core.model.guide.PrivateAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Un receptor visto en la red: el nombre con el que se anuncia y su dirección IP (S044/RF-7). */
data class FoundReceiver(val name: String, val host: String)

/** En qué punto está la búsqueda: [Empty] es «ninguno» (S044/RF-9) y [Done] termina con al menos un resultado. */
enum class DiscoveryPhase { Idle, Searching, Done, Empty }

/** Quien anuncia los servicios de la red: la implementación de Android usa `NsdManager`; los tests, un navegador falso. */
interface ServiceBrowser {
    fun start(listener: Listener)

    /** Idempotente. */
    fun stop()

    interface Listener {
        /** Un servicio ya resuelto: su nombre y su dirección. Puede llamarse desde cualquier hilo. */
        fun onResolved(name: String, host: String)

        /** La búsqueda no pudo empezar o se cayó. */
        fun onFailed()
    }
}

/**
 * Busca receptores en la red durante 10 s (S044/RF-7, RF-9, RF-10; plan D8, A5): junta los resultados según llegan, quita los repetidos
 * (mismo nombre y misma dirección), descarta toda dirección que no sea privada o de enlace local y para sola a los 10 s o al salir.
 */
class ReachDiscovery(private val browser: ServiceBrowser, private val scope: CoroutineScope) {

    private val mutableFound = MutableStateFlow<List<FoundReceiver>>(emptyList())
    val found: StateFlow<List<FoundReceiver>> = mutableFound.asStateFlow()

    private val mutablePhase = MutableStateFlow(DiscoveryPhase.Idle)
    val phase: StateFlow<DiscoveryPhase> = mutablePhase.asStateFlow()

    private var timer: Job? = null

    /** Empieza una búsqueda de cero (también como «Reintentar»). */
    fun start() {
        stopBrowsing()
        mutableFound.value = emptyList()
        mutablePhase.value = DiscoveryPhase.Searching
        browser.start(
            object : ServiceBrowser.Listener {
                override fun onResolved(name: String, host: String) = add(name, host)

                override fun onFailed() = finish()
            },
        )
        timer = scope.launch {
            delay(SEARCH_MS)
            finish()
        }
    }

    /** Al salir de la pantalla: para la búsqueda y deja el estado en reposo. Los resultados ya vistos se conservan hasta la siguiente. */
    fun stop() {
        stopBrowsing()
        mutablePhase.value = DiscoveryPhase.Idle
    }

    private fun stopBrowsing() {
        timer?.cancel()
        timer = null
        if (mutablePhase.value == DiscoveryPhase.Searching) browser.stop()
    }

    private fun finish() {
        if (mutablePhase.value != DiscoveryPhase.Searching) return
        timer?.cancel()
        timer = null
        browser.stop()
        mutablePhase.value = if (mutableFound.value.isEmpty()) DiscoveryPhase.Empty else DiscoveryPhase.Done
    }

    private fun add(name: String, host: String) {
        if (mutablePhase.value != DiscoveryPhase.Searching) return
        val address = PrivateAddress.parse(host) ?: return // S044/RF-10
        val receiver = FoundReceiver(name, address.host)
        mutableFound.update { current -> if (receiver in current) current else current + receiver }
    }

    private companion object {
        const val SEARCH_MS = 10_000L
    }
}
