package es.topophone.app.receiver

import es.topophone.core.model.guide.EquipmentProfile
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.data.equipment.EquipmentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Los datos del receptor en uso (modelo, serie y firmware), resueltos y compartidos por toda la app (S043/RF-19, RF-23; plan D7). Solo
 * hay perfil con un receptor de verdad, el de una dirección Bluetooth o `wifi:<id>`: con el móvil, el virtual, una reproducción o sin
 * receptor es `null`, y los puntos que se midan así no llevarán equipo.
 *
 * @param key la clave del receptor de `CurrentTilt`: su dirección Bluetooth, `wifi:<id>`, `phone`, `virtual`, `replay` o null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentEquipment(key: Flow<String?>, private val repository: EquipmentRepository, scope: CoroutineScope) {

    /** El perfil actual, o `null` si el receptor no tiene o la fuente no lo admite. */
    val state: StateFlow<EquipmentProfile?> = key.distinctUntilChanged()
        .flatMapLatest { receiver -> if (appliesTo(receiver)) repository.profile(receiver!!) else flowOf(null) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    companion object {
        /** ¿Admite la fuente de [key] un perfil de equipo? Solo un receptor real (RF-23). */
        fun appliesTo(key: String?): Boolean = key != null && key != REPLAY_KEY && !ReceiverKey.isBuiltIn(key)

        /** La clave que `CurrentTilt` da a una reproducción (S016/RF-1). */
        const val REPLAY_KEY = "replay"

        /** El valor por defecto de quien no lo recibe por Hilt (tests): sin perfil. */
        val NONE: CurrentEquipment by lazy { CurrentEquipment(flowOf(null), NoEquipment, CoroutineScope(Dispatchers.Unconfined)) }
    }

    private object NoEquipment : EquipmentRepository {
        override fun profile(receiverKey: String): Flow<EquipmentProfile?> = emptyFlow()

        override suspend fun save(receiverKey: String, profile: EquipmentProfile) = Unit

        override fun webAddress(receiverKey: String): Flow<String?> = emptyFlow()

        override suspend fun saveWebAddress(receiverKey: String, address: String?) = Unit
    }
}
