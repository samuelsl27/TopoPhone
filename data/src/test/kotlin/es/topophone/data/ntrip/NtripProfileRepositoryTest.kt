package es.topophone.data.ntrip

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.model.ntrip.NtripProfileError
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.secret.AesGcmCipher
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContainInOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Perfiles NTRIP con la contraseña cifrada (S007/RF-1 a RF-4, RF-6, RF-7, RF-19). */
@RunWith(AndroidJUnit4::class)
class NtripProfileRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_792_080_000_000L
    private var nextId = 0

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val repository = repository(key)

    private fun repository(key: SecretKey) = RoomNtripProfileRepository(
        db,
        AesGcmCipher {
            key
        },
        clock = { now },
        newId = { "id-${++nextId}" },
    )

    @After
    fun close() = db.close()

    private val ign = NtripProfileDraft(
        name = "ERGNSS (IGN): red",
        host = "ergnss-tr.ign.es",
        port = 2101,
        useTls = false,
        username = "sgarcia",
        mountpoint = "VRS3M",
        mountpointNeedsGga = true,
    )

    private fun create(draft: NtripProfileDraft = ign, password: String = "secreta-ñ"): String = runBlocking {
        repository.create(draft, password).shouldBeInstanceOf<NtripSaveResult.Saved>().id
    }

    private fun rawPasswordColumn(id: String): ByteArray? =
        db.openHelper.readableDatabase.query("SELECT password_ct FROM ntrip_profiles WHERE id = ?", arrayOf(id)).use {
            it.moveToFirst()
            if (it.isNull(0)) null else it.getBlob(0)
        }

    @Test
    fun `S007 RF-2 un perfil se guarda con todos sus datos`() = runBlocking<Unit> {
        val id = create()

        val profile = repository.profile(id).shouldNotBeNull()
        profile.draft shouldBe ign
        profile.hasPassword shouldBe true
        profile.createdAtMs shouldBe now
        profile.modifiedAtMs shouldBe now
    }

    @Test
    fun `S007 RF-1 la lista de perfiles va por nombre con su servidor, puerto y punto de montaje`() = runBlocking<Unit> {
        create(ign.copy(name = "Red de Bizkaia", host = "80.245.2.245", mountpoint = "VRS3M"))
        create(ign.copy(name = "catNet (ICGC)", host = "catnet-ip.icgc.cat", mountpoint = "VRS3M"))
        create(ign)

        val profiles = repository.profiles().first()

        profiles.map { it.draft.name } shouldBe listOf("catNet (ICGC)", "ERGNSS (IGN): red", "Red de Bizkaia")
        profiles.first().draft.let { Triple(it.host, it.port, it.mountpoint) } shouldBe Triple("catnet-ip.icgc.cat", 2101, "VRS3M")
    }

    @Test
    fun `S007 RF-3 un perfil no valido no se guarda y se dicen los motivos`() = runBlocking<Unit> {
        create()

        repository.create(ign.copy(name = "ergnss (ign): RED", host = "", port = 0), "x") shouldBe
            NtripSaveResult.Invalid(setOf(NtripProfileError.DUPLICATE_NAME, NtripProfileError.EMPTY_HOST, NtripProfileError.INVALID_PORT))
        repository.profiles().first().size shouldBe 1
    }

    @Test
    fun `S007 RF-3 al editar, el nombre del propio perfil no cuenta como repetido`() = runBlocking<Unit> {
        val id = create()
        now += 60_000

        repository.update(id, ign.copy(mountpoint = "CERCANA3M"), password = null) shouldBe NtripSaveResult.Saved(id)

        val profile = repository.profile(id).shouldNotBeNull()
        profile.draft.mountpoint shouldBe "CERCANA3M"
        profile.modifiedAtMs shouldBe now
    }

    @Test
    fun `S007 RF-4 la contrasena solo se guarda cifrada y se recupera al conectar`() = runBlocking<Unit> {
        val id = create(password = "secreta-ñ")

        val stored = rawPasswordColumn(id).shouldNotBeNull()
        stored.toList() shouldNotContainInOrder "secreta-ñ".toByteArray().toList()
        repository.password(id) shouldBe PasswordResult.Ok("secreta-ñ")
    }

    @Test
    fun `S007 RF-4 al editar, sin contrasena nueva se conserva, con otra se cambia y vacia se quita`() = runBlocking<Unit> {
        val id = create(password = "primera")

        repository.update(id, ign, password = null)
        repository.password(id) shouldBe PasswordResult.Ok("primera")

        repository.update(id, ign, password = "segunda")
        repository.password(id) shouldBe PasswordResult.Ok("segunda")

        repository.update(id, ign, password = "")
        repository.password(id) shouldBe PasswordResult.None
        rawPasswordColumn(id).shouldBeNull()
        repository.profile(id).shouldNotBeNull().hasPassword shouldBe false
    }

    @Test
    fun `S007 RF-6 si la contrasena no se puede descifrar se dice y el perfil sigue entero`() = runBlocking<Unit> {
        val id = create()

        val restored = repository(newKey()) // otra clave: como tras restaurar el móvil

        restored.password(id) shouldBe PasswordResult.Undecryptable
        restored.profile(id).shouldNotBeNull().draft shouldBe ign
    }

    @Test
    fun `S007 RF-7 borrar un perfil lo elimina con su contrasena`() = runBlocking<Unit> {
        val id = create()

        repository.delete(id)

        repository.profiles().first().shouldBeEmpty()
        repository.profile(id).shouldBeNull()
        repository.password(id) shouldBe PasswordResult.None
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ntrip_profiles").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 0
        }
    }

    @Test
    fun `S007 RF-19 se recuerda si el punto de montaje necesita la GGA, o que no se sabe`() = runBlocking<Unit> {
        val station = create(ign.copy(name = "Estación", mountpoint = "AVL13M", mountpointNeedsGga = false))
        val manual = create(ign.copy(name = "A mano", mountpoint = "MIBASE", mountpointNeedsGga = null))

        repository.profile(station).shouldNotBeNull().draft.mountpointNeedsGga shouldBe false
        repository.profile(manual).shouldNotBeNull().draft.mountpointNeedsGga.shouldBeNull()
    }
}
