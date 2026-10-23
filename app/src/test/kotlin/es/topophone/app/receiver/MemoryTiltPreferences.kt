package es.topophone.app.receiver

import es.topophone.data.receiver.ReceiverTiltSettings
import es.topophone.data.receiver.TiltPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** El modo de inclinación de cada receptor en memoria (S016/RF-1). */
class MemoryTiltPreferences(initial: Map<String, ReceiverTiltSettings> = emptyMap()) : TiltPreferences {
    val values = MutableStateFlow(initial)

    override fun settings(key: String): Flow<ReceiverTiltSettings> = values.map { it[key] ?: ReceiverTiltSettings.NONE }

    override suspend fun save(key: String, settings: ReceiverTiltSettings) {
        values.value = values.value + (key to settings)
    }
}

/** Un [CurrentTilt] con un receptor fijo [address] (o una reproducción) y sus modos en memoria. */
fun currentTilt(
    scope: CoroutineScope,
    preferences: MemoryTiltPreferences = MemoryTiltPreferences(),
    address: String? = null,
    replaying: Boolean = false,
) = CurrentTilt(flowOf(replaying), flowOf(address), preferences, scope)
