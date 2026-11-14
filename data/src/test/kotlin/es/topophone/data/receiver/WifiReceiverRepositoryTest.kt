package es.topophone.data.receiver

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.receiver.ReceiverKey
import es.topophone.core.model.receiver.WifiReceiver
import es.topophone.core.model.receiver.WifiReceiverInput
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Los receptores Wi-Fi: alta, edición con la misma identidad y borrado (S021/RF-4, RF-5). */
@RunWith(AndroidJUnit4::class)
class WifiReceiverRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private var now = 1_000L
    private var next = 0
    private val repository = RoomWifiReceiverRepository(db, clock = { now }, newId = { "id-${++next}" })

    @After
    fun close() = db.close()

    private fun input(name: String = "RS3", host: String = "192.168.42.1", position: Int = 9001, corrections: Int? = null) =
        WifiReceiverInput(name, host, position, corrections)

    private fun all() = runBlocking { repository.receivers().first() }

    @Test
    fun `S021 RF-4 sin receptores la lista esta vacia`() {
        all() shouldBe emptyList()
    }

    @Test
    fun `S021 RF-4 un alta se guarda tal cual con los puertos y el nombre`() {
        val id = runBlocking { repository.add(input(name = "  RS3 campo ", host = "reach.local", position = 9001, corrections = 9002)) }

        all() shouldBe listOf(WifiReceiver(id, "  RS3 campo ", "reach.local", 9001, 9002))
        runBlocking { repository.byId(id) } shouldBe WifiReceiver(id, "  RS3 campo ", "reach.local", 9001, 9002)
    }

    @Test
    fun `S021 RF-4 sin puerto de correcciones se guarda null`() {
        val id = runBlocking { repository.add(input(corrections = null)) }

        runBlocking { repository.byId(id) }.shouldNotBeNull().correctionsPort.shouldBeNull()
    }

    @Test
    fun `S021 RF-4 la lista va por orden de alta y cada receptor tiene su identidad`() {
        val first = runBlocking { repository.add(input(name = "A")) }
        now += 10
        val second = runBlocking { repository.add(input(name = "B")) }
        now += 10
        val third = runBlocking { repository.add(input(name = "C")) }

        listOf(first, second, third).toSet().size shouldBe 3
        all().map { it.name } shouldBe listOf("A", "B", "C")
    }

    @Test
    fun `S021 RF-5 editar la direccion o el puerto no cambia la identidad ni la clave`() {
        val id = runBlocking { repository.add(input(host = "192.168.0.20", position = 9001)) }
        val keyBefore = ReceiverKey.of(runBlocking { repository.byId(id) }.shouldNotBeNull())

        runBlocking {
            repository.update(id, input(name = "RS3 nuevo", host = "192.168.42.1", position = 9100, corrections = 9101))
        } shouldBe
            true

        val edited = runBlocking { repository.byId(id) }.shouldNotBeNull()
        edited shouldBe WifiReceiver(id, "RS3 nuevo", "192.168.42.1", 9100, 9101)
        ReceiverKey.of(edited) shouldBe keyBefore
        all().size shouldBe 1
    }

    @Test
    fun `S021 RF-5 editar conserva el orden de alta`() {
        val first = runBlocking { repository.add(input(name = "A")) }
        now += 10
        runBlocking { repository.add(input(name = "B")) }
        now += 10

        runBlocking { repository.update(first, input(name = "A editado")) }

        all().map { it.name } shouldBe listOf("A editado", "B")
    }

    @Test
    fun `S021 RF-4 editar o borrar un receptor que no existe no hace nada y lo dice`() {
        runBlocking { repository.update("nada", input()) } shouldBe false
        runBlocking { repository.delete("nada") } shouldBe false
        runBlocking { repository.byId("nada") }.shouldBeNull()
        all() shouldBe emptyList()
    }

    @Test
    fun `S021 RF-4 borrar quita solo ese receptor`() {
        val first = runBlocking { repository.add(input(name = "A")) }
        now += 10
        val second = runBlocking { repository.add(input(name = "B")) }

        runBlocking { repository.delete(first) } shouldBe true

        all().map { it.id } shouldBe listOf(second)
        runBlocking { repository.byId(first) }.shouldBeNull()
    }

    @Test
    fun `S021 RF-4 la lista observada se actualiza con cada cambio`() {
        runBlocking {
            val id = repository.add(input(name = "A"))
            repository.receivers().first().map { it.name } shouldBe listOf("A")
            repository.update(id, input(name = "B"))
            repository.receivers().first().map { it.name } shouldBe listOf("B")
            repository.observe(id).first().shouldNotBeNull().name shouldBe "B"
            repository.delete(id)
            repository.observe(id).first().shouldBeNull()
        }
    }
}
