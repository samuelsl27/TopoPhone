package es.topophone.data.photo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.PhotoAction
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.data.backup.BackupFixture
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Guardar el punto con sus fotos, añadir, borrar y los sucesos (S017b/RF-2, RF-12, RF-14, RF-20, RF-21, RF-22, RF-23). */
@RunWith(AndroidJUnit4::class)
class PointRepositoryPhotosTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "photos-${System.nanoTime()}.db"
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
        photos = RoomPhotoRepository(db, fixture.points, store, clock = { fixture.now })
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(name)
    }

    /** Una foto en espera con su fichero en `pending/`. */
    private fun staged(takenAtMs: Long = 1_000, origin: PhotoOrigin = PhotoOrigin.CAMERA): StagedPhoto {
        val id = "photo-${counter++}"
        store.pendingDir.mkdirs()
        store.pendingFile(id).writeBytes(ByteArray(100) { it.toByte() })
        return StagedPhoto(id, origin, takenAtMs, 100, 640, 480)
    }

    private fun files(dir: File) = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()

    @Test
    fun `S017b RF-2 el punto se guarda con sus fotos y los ficheros pasan de espera a definitivos`() = runBlocking<Unit> {
        val a = staged(1_000)
        val b = staged(1_000)
        val point = fixture.measured("20", fixture.streetId)

        photos.createWithPhotos(point, listOf(a, b)) shouldBe PointWithPhotosResult.Saved

        val saved = photos.photos(point.id).first()
        saved.map { it.id } shouldBe listOf(a.id, b.id)
        saved.map { it.name }.distinct().size shouldBe 2
        files(store.photosDir) shouldBe listOf("${a.id}.jpg", "${b.id}.jpg").sorted()
        files(store.pendingDir) shouldBe emptyList()
        // Las fotos de la medida son parte de la creación: ningún suceso.
        photos.events(point.id).first() shouldBe emptyList()
        db.points().byId(point.id)!!.edited shouldBe false
    }

    @Test
    fun `S017b RF-20 un fallo a medias no deja ni filas ni ficheros del punto`() = runBlocking<Unit> {
        val a = staged()
        val missing = StagedPhoto("no-existe", PhotoOrigin.CAMERA, 1, 1, 1, 1)
        val point = fixture.measured("21", fixture.streetId)

        photos.createWithPhotos(point, listOf(a, missing)) shouldBe PointWithPhotosResult.FileError

        db.points().byId(point.id) shouldBe null
        db.photos().allIds() shouldBe emptyList()
        files(store.photosDir) shouldBe emptyList()
        files(store.pendingDir) shouldBe listOf("${a.id}.jpg")
    }

    @Test
    fun `S017b RF-20 un nombre repetido no guarda ni el punto ni las fotos`() = runBlocking<Unit> {
        val a = staged()
        val duplicate = fixture.measured("1", fixture.streetId).copy(id = "otro")

        photos.createWithPhotos(duplicate, listOf(a)) shouldBe PointWithPhotosResult.DuplicateName

        db.photos().allIds() shouldBe emptyList()
        files(store.photosDir) shouldBe emptyList()
        files(store.pendingDir) shouldBe listOf("${a.id}.jpg")
    }

    @Test
    fun `S017b RF-12 anadir a un punto guardado anota el suceso y no lo marca como editado`() = runBlocking<Unit> {
        val a = staged(5_000, PhotoOrigin.GALLERY)

        val result = photos.addPhotos("id-1", listOf(a)).shouldBeInstanceOf<AddPhotosResult.Added>()

        result.added.single().origin shouldBe PhotoOrigin.GALLERY
        result.rejected shouldBe 0
        val events = photos.events("id-1").first()
        events.single().action shouldBe PhotoAction.ADDED
        events.single().photoName shouldBe result.added.single().name
        db.points().byId("id-1")!!.edited shouldBe false
        fixture.points.history("id-1").first() shouldBe emptyList()
        files(store.photosDir) shouldBe listOf("${a.id}.jpg")
    }

    @Test
    fun `S017b RF-14 el limite de 10 se comprueba en la transaccion y las que sobran se descartan`() = runBlocking<Unit> {
        val first = (1..8).map { staged(it * 1_000L) }
        photos.addPhotos("id-1", first).shouldBeInstanceOf<AddPhotosResult.Added>().rejected shouldBe 0

        val more = (1..4).map { staged(20_000L + it) }
        val result = photos.addPhotos("id-1", more).shouldBeInstanceOf<AddPhotosResult.Added>()

        result.added.size shouldBe 2
        result.rejected shouldBe 2
        db.photos().count("id-1") shouldBe 10
        files(store.pendingDir) shouldBe emptyList()
        photos.addPhotos("id-1", listOf(staged())).shouldBeInstanceOf<AddPhotosResult.Added>().added shouldBe emptyList()
    }

    @Test
    fun `S017b RF-21 la misma foto anadida dos veces cuenta como dos con nombres distintos`() = runBlocking<Unit> {
        val a = staged(7_000)
        val b = staged(7_000)

        photos.addPhotos("id-1", listOf(a)).shouldBeInstanceOf<AddPhotosResult.Added>()
        photos.addPhotos("id-1", listOf(b)).shouldBeInstanceOf<AddPhotosResult.Added>()

        val saved = photos.photos("id-1").first()
        saved.size shouldBe 2
        saved.map { it.name }.distinct().size shouldBe 2
    }

    @Test
    fun `S017b RF-22 borrar una foto quita fila y fichero y deja su suceso`() = runBlocking<Unit> {
        val a = staged(9_000)
        val added = photos.addPhotos("id-1", listOf(a)).shouldBeInstanceOf<AddPhotosResult.Added>().added.single()
        fixture.now += 500

        photos.deletePhoto(a.id) shouldBe true

        photos.photos("id-1").first() shouldBe emptyList()
        files(store.photosDir) shouldBe emptyList()
        val events = photos.events("id-1").first()
        events.map { it.action } shouldBe listOf(PhotoAction.DELETED, PhotoAction.ADDED)
        events.first().photoName shouldBe added.name
        events.first().photoTakenAtMs shouldBe 9_000
        db.points().byId("id-1")!!.edited shouldBe false
        photos.deletePhoto(a.id) shouldBe false
    }

    @Test
    fun `S017b RF-12 un punto que no existe no admite fotos`() = runBlocking<Unit> {
        photos.addPhotos("nada", listOf(staged())) shouldBe AddPhotosResult.PointNotFound
    }
}
