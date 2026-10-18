package es.topophone.app.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import es.topophone.app.ui.projects.MapSlot
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.project.LinearUnit
import es.topophone.data.map.MapPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Doble de [MapController] para los tests de la interfaz: MapLibre no funciona en Robolectric (código nativo). */
class FakeMapController : MapController {
    val settings = mutableListOf<MapSettings>()
    var unit: LinearUnit? = null

    override fun setSettings(settings: MapSettings) {
        this.settings += settings
    }

    override fun setLinearUnit(unit: LinearUnit) {
        this.unit = unit
    }
}

/** Hueco del mapa con el doble: una caja vacía que entrega [controller] nada más aparecer. */
fun fakeMap(controller: FakeMapController): MapSlot = { modifier, onReady ->
    Box(modifier)
    LaunchedEffect(Unit) { onReady(controller) }
}

/** Preferencias del mapa en memoria (S009/RF-8). */
class FakeMapPreferences(initial: MapSettings = MapSettings()) : MapPreferences {
    override val settings = MutableStateFlow(initial)

    override suspend fun save(settings: MapSettings) {
        this.settings.value = settings
    }
}
