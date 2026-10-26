package es.topophone.app.ui.receiver

import es.topophone.app.receiver.CurrentGear
import es.topophone.core.model.point.PoleKind
import es.topophone.core.survey.ActiveGear
import es.topophone.core.survey.AntennaCatalog
import es.topophone.core.survey.AntennaProfile
import es.topophone.core.survey.GearResolver
import es.topophone.core.survey.GearValidation
import es.topophone.core.survey.PoleCheck
import es.topophone.core.survey.ProfileCheck
import es.topophone.core.survey.SavedPole
import es.topophone.data.gear.GearRepository
import es.topophone.data.gear.GearResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Un [GearRepository] en memoria para los tests de interfaz (S046): mismas reglas de nombres y rangos que el real. */
internal class FakeGearRepository(
    poles: List<SavedPole> = listOf(SavedPole("p1", "Jalón estándar", PoleKind.VERTICAL, 1.8)),
    own: List<AntennaProfile> = emptyList(),
) : GearRepository {
    val polesFlow = MutableStateFlow(poles)
    val ownFlow = MutableStateFlow(own)

    /** Receptor → (perfil, jalón activo). */
    val rows = MutableStateFlow<Map<String, Pair<String?, String?>>>(emptyMap())
    private var next = 0

    override fun profiles(): Flow<List<AntennaProfile>> = ownFlow.map { AntennaCatalog.ALL + it }

    override fun poles(): Flow<List<SavedPole>> = polesFlow

    override fun gear(receiverKey: String): Flow<ActiveGear> = combine(ownFlow, polesFlow, rows) { own, poles, rows ->
        val row = rows[receiverKey]
        GearResolver.resolve(row?.first, own, row?.second, poles)
    }

    override suspend fun saveProfile(id: String?, name: String, offsetText: String, radiusText: String): GearResult {
        val existing = AntennaCatalog.ALL.map { it.name } + ownFlow.value.filter { "own:$id" != it.ref }.map { it.name }
        return when (val check = GearValidation.profile(name, offsetText, radiusText, existing, null)) {
            is ProfileCheck.Invalid -> GearResult.Invalid(check.field)
            is ProfileCheck.Valid -> {
                val ref = id?.let { "own:$it" } ?: "own:fake-${next++}"
                val profile = AntennaProfile(ref, check.name, check.offsetM, check.radiusM, builtIn = false)
                ownFlow.value = ownFlow.value.filter { it.ref != ref } + profile
                GearResult.Saved(ref.removePrefix("own:"))
            }
        }
    }

    override suspend fun deleteProfile(id: String): GearResult {
        val users = rows.value.filter { it.value.first == "own:$id" }.keys.toList()
        if (users.isNotEmpty()) return GearResult.InUse(users)
        ownFlow.value = ownFlow.value.filter { it.ref != "own:$id" }
        return GearResult.Deleted
    }

    override suspend fun savePole(id: String?, name: String, kind: PoleKind, heightText: String): GearResult {
        val existing = polesFlow.value.filter { it.id != id }.map { it.name }
        return when (val check = GearValidation.pole(name, kind, heightText, existing, null)) {
            is PoleCheck.Invalid -> GearResult.Invalid(check.field)
            is PoleCheck.Valid -> {
                val saved = SavedPole(id ?: "fake-pole-${next++}", check.name, check.kind, check.enteredM)
                polesFlow.value = if (id != null && polesFlow.value.any { it.id == id }) {
                    polesFlow.value.map { if (it.id == id) saved else it }
                } else {
                    polesFlow.value + saved
                }
                GearResult.Saved(saved.id)
            }
        }
    }

    override suspend fun deletePole(id: String): GearResult {
        if (polesFlow.value.size == 1) return GearResult.LastPole
        val active = rows.value.filter { it.value.second == id }.keys.toList()
        if (active.isNotEmpty()) return GearResult.ActiveElsewhere(active)
        polesFlow.value = polesFlow.value.filter { it.id != id }
        return GearResult.Deleted
    }

    override suspend fun setProfile(receiverKey: String, profileRef: String): GearResult {
        val known = AntennaCatalog.byRef(profileRef) != null || ownFlow.value.any { it.ref == profileRef }
        if (!known) return GearResult.NotFound
        rows.value = rows.value + (receiverKey to (profileRef to rows.value[receiverKey]?.second))
        return GearResult.Saved(receiverKey)
    }

    override suspend fun setActivePole(receiverKey: String, poleId: String): GearResult {
        if (polesFlow.value.none { it.id == poleId }) return GearResult.NotFound
        rows.value = rows.value + (receiverKey to (rows.value[receiverKey]?.first to poleId))
        return GearResult.Saved(receiverKey)
    }
}

/** El equipo del receptor en uso sobre un repositorio en memoria (sin receptor: la clave por defecto). */
internal fun fakeCurrentGear(
    repository: FakeGearRepository = FakeGearRepository(),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
) = CurrentGear(flowOf(null), repository, scope)
