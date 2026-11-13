package es.topophone.data.photo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.data.backup.BackupFixture
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Las fotos de varios puntos de una vez para exportarlas, en orden de alta y con la consulta troceada (S056/RF-1, RF-2, S031/RF-28). */
@RunWith(AndroidJUnit4::class)
class PhotoExportRepoTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "photo-export-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var store: PhotoStore
    private lateinit var photos: RoomPhotoRepository
    private var counter = 0

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        store = PhotoStore(folder.newFolder("files"), { null })
        photos = RoomPhotoRepository(db, fixture.points, store, clock = { fixture.now + (counter++) })
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(name)
    }

    private fun staged(): StagedPhoto {
        val id = "foto-${counter++}"
        store.pendingDir.mkdirs()
        store.pendingFile(id).writeBytes(ByteArray(10))
        return StagedPhoto(id, PhotoOrigin.CAMERA, 1_000, 10, 640, 480)
    }

    /** Un punto nuevo con [count] fotos: su id y los de sus fotos. */
    private fun withPhotos(name: String, count: Int): Pair<String, List<String>> = runBlocking {
        val point = fixture.measured(name, fixture.streetId)
        val list = List(count) { staged() }
        photos.createWithPhotos(point, list)
        point.id to list.map { it.id }
    }

    @Test
    fun `S056 RF-1 las fotos salen por punto y en el orden en que se anadieron`() = runBlocking<Unit> {
        val (idA, a) = withPhotos("30", 3)
        val (idB, b) = withPhotos("31", 2)

        val result = photos.photosForExport(listOf(idB, idA))

        result.keys.toSet() shouldBe setOf(idA, idB)
        result.getValue(idA).map { it.id } shouldBe a
        result.getValue(idB).map { it.id } shouldBe b
    }

    @Test
    fun `S056 RF-1 un punto sin fotos no sale y uno que no existe se ignora`() = runBlocking<Unit> {
        val (id, _) = withPhotos("32", 1)

        val result = photos.photosForExport(listOf(id, "sin-fotos", "no-existe"))

        result.keys.toList() shouldBe listOf(id)
    }

    @Test
    fun `S056 RF-2 solo salen las fotos de los puntos pedidos`() = runBlocking<Unit> {
        val (first, _) = withPhotos("33", 2)
        val (second, other) = withPhotos("34", 2)

        val result = photos.photosForExport(listOf(second))

        result.getValue(second).map { it.id } shouldBe other
        result.containsKey(first) shouldBe false
    }

    @Test
    fun `S056 RF-1 sin puntos no hay fotos`() = runBlocking<Unit> {
        withPhotos("35", 1)

        photos.photosForExport(emptyList()) shouldBe emptyMap()
    }

    @Test
    fun `S056 RF-1 con mas de mil puntos pedidos la consulta no pasa del limite de variables de SQLite`() = runBlocking<Unit> {
        val (id, a) = withPhotos("36", 2)
        val ids = List(2_500) { "relleno-$it" } + id

        val result = photos.photosForExport(ids)

        result.keys.toList() shouldBe listOf(id)
        result.getValue(id).map { it.id } shouldBe a
    }

    @Test
    fun `S056 RF-1 cada foto lleva su fecha, su origen y su tamano`() = runBlocking<Unit> {
        val (id, _) = withPhotos("37", 1)

        val photo = photos.photosForExport(listOf(id)).getValue(id).single()

        photo.origin shouldBe PhotoOrigin.CAMERA
        photo.takenAtMs shouldBe 1_000
        photo.sizeBytes shouldBe 10
    }

    @Test
    fun `S031 RF-28 el repositorio sin fotos no devuelve nada`() = runBlocking<Unit> {
        PhotoRepository.NONE.photosForExport(listOf("1", "2")) shouldBe emptyMap()
    }
}
