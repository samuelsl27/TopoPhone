package es.topophone.data.gear

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.PoleKind
import es.topophone.core.survey.AntennaCatalog
import es.topophone.core.survey.AntennaProfile
import es.topophone.core.survey.GearProblem
import es.topophone.core.survey.GearProblemField
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Altas, ediciones, borrados y el equipo de cada receptor (S046/RF-3 a RF-13; RNF-T6). */
@RunWith(AndroidJUnit4::class)
class GearRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var now = 1_000L
    private var next = 0
    private val repository = RoomGearRepository(db, idGenerator = { "id-${next++}" }, clock = { now++ })

    @After
    fun close() = db.close()

    private fun saved(result: GearResult) = result.shouldBeInstanceOf<GearResult.Saved>().id

    @Test
    fun `S046 RF-1 RF-3 los perfiles son el catalogo y los propios por antiguedad`() = runBlocking<Unit> {
        saved(repository.saveProfile(null, "Mi antena", "0,12", "0.06"))
        saved(repository.saveProfile(null, "Otra", "0.1", ""))

        val profiles = repository.profiles().first()

        profiles.take(AntennaCatalog.ALL.size) shouldBe AntennaCatalog.ALL
        profiles.drop(AntennaCatalog.ALL.size).map { it.name } shouldContainExactly listOf("Mi antena", "Otra")
        profiles.last().offsetM shouldBe 0.1
        profiles.last().radiusM.shouldBeNull()
        profiles.last().builtIn shouldBe false
    }

    @Test
    fun `S046 RF-4 un perfil no valido no se guarda y no se repite un nombre ni el de un modelo del catalogo`() = runBlocking<Unit> {
        saved(repository.saveProfile(null, "Mi antena", "0.12", ""))

        repository.saveProfile(null, "mi ANTENA", "0.1", "") shouldBe GearResult.Invalid(GearProblemField.NAME_TAKEN)
        repository.saveProfile(null, "reach rs3", "0.1", "") shouldBe GearResult.Invalid(GearProblemField.NAME_TAKEN)
        repository.saveProfile(null, "X", "2", "") shouldBe GearResult.Invalid(GearProblemField.OFFSET)
        repository.saveProfile(null, "X", "0.1", "0.5") shouldBe GearResult.Invalid(GearProblemField.RADIUS)
        repository.profiles().first().size shouldBe AntennaCatalog.ALL.size + 1
    }

    @Test
    fun `S046 RF-5 editar un perfil conserva su identificador y su antiguedad y su nombre no choca consigo mismo`() = runBlocking<Unit> {
        val id = saved(repository.saveProfile(null, "Mi antena", "0.12", ""))
        saved(repository.saveProfile(null, "Otra", "0.1", ""))

        saved(repository.saveProfile(id, "Mi antena", "0.13", "0.05")) shouldBe id

        val profiles = repository.profiles().first().drop(AntennaCatalog.ALL.size)
        profiles.map { it.name } shouldContainExactly listOf("Mi antena", "Otra")
        profiles.first().offsetM shouldBe 0.13
        profiles.first().radiusM shouldBe 0.05
        repository.saveProfile("no-existe", "Z", "0.1", "") shouldBe GearResult.NotFound
        repository.saveProfile(id, "Otra", "0.1", "") shouldBe GearResult.Invalid(GearProblemField.NAME_TAKEN)
    }

    @Test
    fun `S046 RF-6 un perfil que usa un receptor no se borra y uno libre si`() = runBlocking<Unit> {
        val id = saved(repository.saveProfile(null, "Mi antena", "0.12", ""))
        saved(repository.savePole(null, "Jalón estándar", PoleKind.VERTICAL, "1.8"))
        repository.setProfile("AA:BB", "own:$id")
        repository.setProfile("replay", "own:$id")

        repository.deleteProfile(id) shouldBe GearResult.InUse(listOf("AA:BB", "replay"))

        repository.setProfile("AA:BB", "catalog:RS3")
        repository.setProfile("replay", "catalog:RS3")
        repository.deleteProfile(id) shouldBe GearResult.Deleted
        repository.deleteProfile(id) shouldBe GearResult.NotFound
    }

    @Test
    fun `S046 RF-9 RF-10 los jalones tienen nombre unico y altura de 0 a 10 m`() = runBlocking<Unit> {
        saved(repository.savePole(null, "Jalón estándar", PoleKind.VERTICAL, "1,8"))

        repository.savePole(null, "jalon ESTANDAR", PoleKind.VERTICAL, "2") shouldBe GearResult.Invalid(GearProblemField.NAME_TAKEN)
        repository.savePole(null, "Largo", PoleKind.VERTICAL, "10,001") shouldBe GearResult.Invalid(GearProblemField.HEIGHT)
        saved(repository.savePole(null, "Largo", PoleKind.SLANT, "2.5"))

        val poles = repository.poles().first()
        poles.map { it.name } shouldContainExactly listOf("Jalón estándar", "Largo")
        poles.last().kind shouldBe PoleKind.SLANT
        poles.first().enteredM shouldBe 1.8
    }

    @Test
    fun `S046 RF-11 no se borra el ultimo jalon`() = runBlocking<Unit> {
        val only = saved(repository.savePole(null, "Jalón estándar", PoleKind.VERTICAL, "1.8"))
        val second = saved(repository.savePole(null, "Corto", PoleKind.VERTICAL, "1.2"))

        repository.deletePole(only) shouldBe GearResult.Deleted
        repository.deletePole(second) shouldBe GearResult.LastPole
        repository.poles().first().size shouldBe 1
    }

    @Test
    fun `S046 RF-12 el jalon activo de un receptor no se borra hasta elegir otro`() = runBlocking<Unit> {
        val standard = saved(repository.savePole(null, "Jalón estándar", PoleKind.VERTICAL, "1.8"))
        val short = saved(repository.savePole(null, "Corto", PoleKind.VERTICAL, "1.2"))
        repository.setActivePole("AA:BB", short)

        repository.deletePole(short) shouldBe GearResult.ActiveElsewhere(listOf("AA:BB"))

        repository.setActivePole("AA:BB", standard)
        repository.deletePole(short) shouldBe GearResult.Deleted
    }

    @Test
    fun `S046 RF-7 RF-13 el equipo de un receptor se recuerda y un receptor nuevo usa el RS3 y el primer jalon`() = runBlocking<Unit> {
        val standard = saved(repository.savePole(null, "Jalón estándar", PoleKind.VERTICAL, "1.8"))
        val short = saved(repository.savePole(null, "Corto", PoleKind.VERTICAL, "1.2"))

        val fresh = repository.gear("nuevo").first()
        fresh.profile shouldBe AntennaProfile.RS3
        fresh.pole.shouldNotBeNull().id shouldBe standard

        repository.setProfile("AA:BB", "catalog:RS4") shouldBe GearResult.Saved("AA:BB")
        repository.setActivePole("AA:BB", short)
        val gear = repository.gear("AA:BB").first()
        gear.profile.name shouldBe "Reach RS4/RS4 Pro"
        gear.pole.shouldNotBeNull().id shouldBe short
        gear.antennaHeightM.shouldNotBeNull() shouldBe (1.295 plusOrMinus 1e-12)

        repository.gear("nuevo").first().pole.shouldNotBeNull().id shouldBe standard
        repository.setProfile("AA:BB", "catalog:NOPE") shouldBe GearResult.NotFound
        repository.setActivePole("AA:BB", "no-existe") shouldBe GearResult.NotFound
    }

    @Test
    fun `S046 RF-19 cambiar el radio del perfil recalcula la altura vertical del jalon inclinado`() = runBlocking<Unit> {
        val profile = saved(repository.saveProfile(null, "Con radio", "0.12", "0.06"))
        val pole = saved(repository.savePole(null, "Inclinado", PoleKind.SLANT, "1.95"))
        repository.setProfile("AA:BB", "own:$profile")
        repository.setActivePole("AA:BB", pole)
        repository.gear("AA:BB").first().verticalPoleM.shouldNotBeNull() shouldBe (1.949076 plusOrMinus 1e-6)

        repository.saveProfile(profile, "Con radio", "0.12", "0.2")

        repository.gear("AA:BB").first().verticalPoleM.shouldNotBeNull() shouldBe (1.939716 plusOrMinus 1e-6)
        repository.saveProfile(profile, "Con radio", "0.12", "") // sin radio
        repository.gear("AA:BB").first().problem shouldBe GearProblem.NO_RADIUS
    }

    @Test
    fun `S046 RNF-T6 un perfil borrado de un receptor sin uso cae en el RS3`() = runBlocking<Unit> {
        saved(repository.savePole(null, "Jalón estándar", PoleKind.VERTICAL, "1.8"))
        val profile = saved(repository.saveProfile(null, "Mi antena", "0.12", ""))
        repository.setProfile("AA:BB", "own:$profile")
        repository.setProfile("AA:BB", "catalog:RX")
        repository.deleteProfile(profile)

        repository.gear("AA:BB").first().profile.ref shouldBe "catalog:RX"
    }
}
