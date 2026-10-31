package es.topophone.data.photo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.PhotoStats
import es.topophone.data.backup.BackupFixture
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.PointSaveResult
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Las fotos viajan con el punto y con el proyecto, y el barrido borra los ficheros sin fila (S017b/RF-24, RF-25, RF-26, RF-27, RF-34). */
@RunWith(AndroidJUnit4::class)
class PhotoCleanupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "cleanup-${System.nanoTime()}.db"
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

    private fun staged(): StagedPhoto {
        val id = "photo-${counter++}"
        store.pendingDir.mkdirs()
        store.pendingFile(id).writeBytes(ByteArray(1_000))
        return StagedPhoto(id, PhotoOrigin.CAMERA, 1_000, 1_000, 640, 480)
    }

    private fun files(dir: File) = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()

    private suspend fun addTo(pointId: String, count: Int): List<StagedPhoto> =
        (1..count).map { staged() }.also { photos.addPhotos(pointId, it) }

    @Test
    fun `S017b RF-24 la papelera de puntos guarda y devuelve las fotos`() = runBlocking<Unit> {
        val added = addTo("id-1", 2)

        fixture.points.moveToTrash(listOf("id-1"))
        photos.photos("id-1").first().size shouldBe 2
        files(store.photosDir).size shouldBe 2

        fixture.points.restore("id-1") shouldBe PointSaveResult.Saved
        photos.photos("id-1").first().map { it.id } shouldBe added.map { it.id }
        photos.file(added.first().id)!!.exists() shouldBe true
    }

    @Test
    fun `S017b RF-24 borrar un punto definitivo limpia sus fotos`() = runBlocking<Unit> {
        addTo("id-4", 3) // el 4 ya está en la papelera
        files(store.photosDir).size shouldBe 3

        fixture.points.deletePermanently("id-4")
        db.photos().allIds() shouldBe emptyList()
        photos.sweep() shouldBe 3

        files(store.photosDir) shouldBe emptyList()
    }

    @Test
    fun `S017b RF-24 la purga limpia las fotos`() = runBlocking<Unit> {
        addTo("id-4", 1)
        fixture.now += 31L * 24 * 3_600_000

        fixture.points.purgeTrash() shouldBe 1
        photos.sweep() shouldBe 1

        files(store.photosDir) shouldBe emptyList()
    }

    @Test
    fun `S017b RF-34 un proyecto borrado limpia sus fotos`() = runBlocking<Unit> {
        addTo("id-9", 2) // el 9 es del proyecto Viejo, que ya está en la papelera

        photos.photos("id-9").first().size shouldBe 2
        photos.stats(fixture.oldId).first() shouldBe PhotoStats(2, 2_000)

        fixture.projects.deleteForever(fixture.oldId)
        photos.sweep() shouldBe 2

        files(store.photosDir) shouldBe emptyList()
        db.photos().allIds() shouldBe emptyList()
    }

    @Test
    fun `S017b RF-34 restaurar un proyecto devuelve fotos`() = runBlocking<Unit> {
        addTo("id-9", 1)

        fixture.projects.restore(fixture.oldId, "restaurado")

        photos.photos("id-9").first().size shouldBe 1
        files(store.photosDir).size shouldBe 1
    }

    @Test
    fun `S017b RF-25 duplicar no copia las fotos`() = runBlocking<Unit> {
        addTo("id-1", 2)
        val original = fixture.points.point("id-1").first()!!
        val copy = original.copy(id = "copia", name = "1-copia", copyOf = original.id)

        fixture.points.create(copy) shouldBe PointSaveResult.Saved

        photos.photos("copia").first() shouldBe emptyList()
        photos.photos("id-1").first().size shouldBe 2
    }

    @Test
    fun `S017b RF-26 el resumen lleva el recuento`() = runBlocking<Unit> {
        addTo("id-1", 3)

        val summaries = fixture.points.summaries(fixture.streetId).first().associateBy { it.id }

        summaries.getValue("id-1").photoCount shouldBe 3
        summaries.getValue("id-2").photoCount shouldBe 0
    }

    @Test
    fun `S017b RF-27 estadisticas del proyecto`() = runBlocking<Unit> {
        photos.stats(fixture.streetId).first() shouldBe PhotoStats.NONE

        addTo("id-1", 2)
        addTo("id-2", 1)

        photos.stats(fixture.streetId).first() shouldBe PhotoStats(3, 3_000)
        photos.stats(fixture.portId).first() shouldBe PhotoStats.NONE
    }

    @Test
    fun `S017b RF-20 el barrido borra huerfanos y respeta la en curso`() = runBlocking<Unit> {
        val kept = addTo("id-1", 1).single()
        File(store.photosDir, "huerfana.jpg").writeBytes(ByteArray(10))
        File(store.photosDir, "colgada.jpg.tmp").writeBytes(ByteArray(10))
        val inProgress = staged()
        val abandoned = staged()

        photos.sweep(pendingInUse = setOf(inProgress.id)) shouldBe 3

        files(store.photosDir) shouldBe listOf("${kept.id}.jpg")
        files(store.pendingDir) shouldBe listOf("${inProgress.id}.jpg")
        store.pendingFile(abandoned.id).exists() shouldBe false
    }
}
