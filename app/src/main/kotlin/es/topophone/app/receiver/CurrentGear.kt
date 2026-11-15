package es.topophone.app.receiver

import es.topophone.core.model.point.PoleKind
import es.topophone.core.survey.ActiveGear
import es.topophone.core.survey.AntennaProfile
import es.topophone.core.survey.GearResolver
import es.topophone.core.survey.PoleHeight
import es.topophone.core.survey.SavedPole
import es.topophone.data.gear.GearRepository
import es.topophone.data.gear.GearResult
import java.math.BigDecimal
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * El equipo (perfil de antena y jalón activo) del receptor en uso, resuelto y compartido por toda la app (S046/RF-7, RF-13,
 * RF-15, RF-23; plan A3). Sin receptor ni reproducción se usa [NO_RECEIVER]: el equipo por defecto.
 *
 * @param key la clave del receptor de `CurrentTilt`: su dirección Bluetooth, `wifi:<id>` (S021/RF-18), `replay` o null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentGear(key: Flow<String?>, private val repository: GearRepository, scope: CoroutineScope) {

    private val receiverKey: Flow<String> = key.map { it ?: NO_RECEIVER }.distinctUntilChanged()

    private val keyState: StateFlow<String> = receiverKey.stateIn(scope, SharingStarted.Eagerly, NO_RECEIVER)

    /** El equipo actual; hasta que llegue de la base de datos, el de un receptor sin configurar (RS3 y 1,8 m). */
    val state: StateFlow<ActiveGear> = receiverKey.flatMapLatest { repository.gear(it) }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT)

    /** Los jalones guardados para el selector (S046/RF-14); hasta que lleguen, el de por defecto. */
    val poles: StateFlow<List<SavedPole>> = repository.poles().stateIn(scope, SharingStarted.Eagerly, listOf(DEFAULT_POLE))

    /** El jalón activo pasa a tener esta altura escrita (S046/RF-10); el tipo, el nombre y el perfil no cambian. */
    suspend fun editActivePoleHeight(enteredM: Double) {
        val pole = state.value.pole ?: return
        val text = BigDecimal(enteredM.toString()).toPlainString()
        repository.savePole(pole.id, pole.name, pole.kind, text)
    }

    /** S046/RF-13, RF-14. */
    suspend fun setActivePole(poleId: String): GearResult = repository.setActivePole(keyState.value, poleId)

    /** S046/RF-7, RF-8. */
    suspend fun setProfile(profileRef: String): GearResult = repository.setProfile(keyState.value, profileRef)

    companion object {
        /** Sin receptor: el equipo de un receptor que no se ha configurado. */
        const val NO_RECEIVER = "none"

        /** RS3 con un jalón vertical de 1,8 m: lo de antes de S046. */
        private val DEFAULT_POLE = SavedPole("default", "Jalón estándar", PoleKind.VERTICAL, PoleHeight.DEFAULT_M)

        val DEFAULT: ActiveGear = GearResolver.resolve(null, emptyList(), "default", listOf(DEFAULT_POLE))

        /** El valor por defecto de quien no lo recibe por Hilt (tests): siempre el equipo por defecto, sin cambios. */
        val NONE: CurrentGear by lazy { CurrentGear(flowOf(null), NoGearRepository, CoroutineScope(Dispatchers.Unconfined)) }
    }

    private object NoGearRepository : GearRepository {
        override fun profiles(): Flow<List<AntennaProfile>> = emptyFlow()

        override fun poles(): Flow<List<SavedPole>> = emptyFlow()

        override fun gear(receiverKey: String): Flow<ActiveGear> = flowOf(DEFAULT)

        override suspend fun saveProfile(id: String?, name: String, offsetText: String, radiusText: String) = GearResult.NotFound

        override suspend fun deleteProfile(id: String) = GearResult.NotFound

        override suspend fun savePole(id: String?, name: String, kind: PoleKind, heightText: String) = GearResult.NotFound

        override suspend fun deletePole(id: String) = GearResult.NotFound

        override suspend fun setProfile(receiverKey: String, profileRef: String) = GearResult.NotFound

        override suspend fun setActivePole(receiverKey: String, poleId: String) = GearResult.NotFound
    }
}
