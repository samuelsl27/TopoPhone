package es.topophone.app.receiver

import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.core.model.receiver.WifiReceiverInput
import es.topophone.data.receiver.WifiReceiverRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Un repositorio sin receptores Wi-Fi, para los sitios que aún no tienen el de verdad (S021). */
object NoWifiReceivers : WifiReceiverRepository {
    override fun receivers(): Flow<List<WifiReceiver>> = flowOf(emptyList())

    override fun observe(id: String): Flow<WifiReceiver?> = flowOf(null)

    override suspend fun byId(id: String): WifiReceiver? = null

    override suspend fun add(input: WifiReceiverInput): String = error("Sin repositorio de receptores Wi-Fi")

    override suspend fun update(id: String, input: WifiReceiverInput): Boolean = false

    override suspend fun delete(id: String): Boolean = false
}
