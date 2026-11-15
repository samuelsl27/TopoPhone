package es.topophone.app.receiver

import es.topophone.core.model.point.PoleKind
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.survey.ActiveGear
import es.topophone.core.survey.AntennaProfile
import es.topophone.core.survey.SavedPole
import es.topophone.data.gear.GearRepository
import es.topophone.data.gear.GearResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test

/** El GNSS del móvil no tiene jalón ni desplazamiento al centro de fase: el punto del suelo es la posición de Android (S022/RF-6). */
class PhoneGearTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Un repositorio que da el equipo de siempre (RS3 y 1,8 m) y cuenta las veces que se le pide guardar algo. */
    private class CountingRepository : GearRepository {
        val writes = AtomicInteger()

        override fun profiles(): Flow<List<AntennaProfile>> = flowOf(emptyList())

        override fun poles(): Flow<List<SavedPole>> = flowOf(listOf(SavedPole("p", "Jalón", PoleKind.VERTICAL, 1.8)))

        override fun gear(receiverKey: String): Flow<ActiveGear> = flowOf(CurrentGear.DEFAULT)

        override suspend fun saveProfile(id: String?, name: String, offsetText: String, radiusText: String): GearResult {
            writes.incrementAndGet()
            return GearResult.NotFound
        }

        override suspend fun deleteProfile(id: String): GearResult = GearResult.NotFound

        override suspend fun savePole(id: String?, name: String, kind: PoleKind, heightText: String): GearResult {
            writes.incrementAndGet()
            return GearResult.NotFound
        }

        override suspend fun deletePole(id: String): GearResult = GearResult.NotFound

        override suspend fun setProfile(receiverKey: String, profileRef: String): GearResult {
            writes.incrementAndGet()
            return GearResult.Saved(receiverKey)
        }

        override suspend fun setActivePole(receiverKey: String, poleId: String): GearResult {
            writes.incrementAndGet()
            return GearResult.Saved(receiverKey)
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    @Test
    fun `S022 RF-6 con la clave del movil el equipo no tiene jalon ni desplazamiento al centro de fase`() {
        val gear = CurrentGear(flowOf(ReceiverKey.PHONE), CountingRepository(), scope)

        val active = await { gear.state.first { it.verticalPoleM == 0.0 } }

        active.usable shouldBe true
        active.verticalPoleM shouldBe (0.0 plusOrMinus 1e-12)
        active.antennaHeightM shouldBe (0.0 plusOrMinus 1e-12)
        active.profile.offsetM shouldBe (0.0 plusOrMinus 1e-12)
        active.profile.radiusM shouldBe (0.0 plusOrMinus 1e-12)
        active.pole.shouldBeInstanceOf<SavedPole>().enteredM shouldBe (0.0 plusOrMinus 1e-12)
        CurrentGear.PHONE_GEAR.usable shouldBe true
    }

    @Test
    fun `S022 RF-6 con otro receptor el equipo sigue siendo el que guarda el repositorio`() {
        val key = MutableStateFlow<String?>("00:11:22:33:44:55")
        val gear = CurrentGear(key, CountingRepository(), scope)

        await { gear.state.first { it.verticalPoleM != null } }.verticalPoleM shouldBe (1.8 plusOrMinus 1e-9)

        key.value = ReceiverKey.PHONE
        await { gear.state.first { it.verticalPoleM == 0.0 } }
        key.value = ReceiverKey.VIRTUAL
        await { gear.state.first { it.verticalPoleM != 0.0 } }.verticalPoleM shouldBe (1.8 plusOrMinus 1e-9)
    }

    @Test
    fun `S022 RF-6 el equipo del movil no se edita ni el jalon ni el perfil ni la altura`() {
        val repository = CountingRepository()
        val gear = CurrentGear(flowOf(ReceiverKey.PHONE), repository, scope)
        await { gear.state.first { it.verticalPoleM == 0.0 } }

        await { gear.setActivePole("p") } shouldBe GearResult.NotFound
        await { gear.setProfile("catalog:RS3") } shouldBe GearResult.NotFound
        await { gear.editActivePoleHeight(2.0) }

        repository.writes.get() shouldBe 0
        gear.state.value.verticalPoleM shouldBe (0.0 plusOrMinus 1e-12)
    }
}
