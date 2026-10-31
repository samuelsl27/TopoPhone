package es.topophone.app.ui.points

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.PhotoError
import es.topophone.core.model.point.PhotoAction
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.photo.PhotoFailure
import es.topophone.data.photo.PhotoStore
import es.topophone.data.photo.RoomPhotoRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Añadir fotos desde la ficha: cámara, galería con varias, el límite y los errores (S017b/RF-12 a RF-16). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PointPhotoAdderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val points = RoomPointRepository(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sources = mutableMapOf<String, ByteArray>()
    private var failWith: IOException? = null
    private lateinit var store: PhotoStore
    private lateinit var photos: RoomPhotoRepository
    private lateinit var adder: PointPhotoAdder
    private lateinit var projectId: String

    @Before
    fun setUp() {
        store = PhotoStore(
            folder.newFolder("files"),
            { source ->
                failWith?.let { throw it }
                sources[source]?.let(::ByteArrayInputStream) ?: File(source).takeIf(File::isFile)?.inputStream()
            },
            captureDir = folder.newFolder("capture"),
        )
        photos = RoomPhotoRepository(db, points, store)
        adder = PointPhotoAdder(scope, photos, store, SavedStateHandle())
        projectId = runBlocking { (RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "P")) as SaveResult.Saved).id }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun jpeg(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
    }.toByteArray()

    private fun source(name: String) = name.also { sources[it] = jpeg() }

    private fun measuredPoint(name: String = "12"): String = runBlocking {
        val p = PointFixtures.measured(name).copy(projectId = projectId)
        points.create(p)
        p.id
    }

    private fun waitIdle() = runBlocking { withTimeout(10_000) { while (adder.ui.value.adding) kotlinx.coroutines.delay(10) } }

    private fun count(pointId: String) = runBlocking { photos.photos(pointId).first().size }

    @Test
    fun `S017b RF-12 la foto de la galeria se anade al punto de la ficha, medido o manual`() {
        val measured = measuredPoint("12")
        adder.start(measured)

        adder.addFromGallery(listOf(source("g1")))
        waitIdle()

        count(measured) shouldBe 1
        val events = runBlocking { photos.events(measured).first() }
        events.single().action shouldBe PhotoAction.ADDED
        runBlocking { photos.photos(measured).first() }.single().origin shouldBe PhotoOrigin.GALLERY
        runBlocking { db.points().byId(measured)!!.edited } shouldBe false

        val manual = runBlocking {
            val p = PointFixtures.measured(
                "m",
            ).copy(projectId = projectId, measurement = null, origin = es.topophone.core.model.point.PointOrigin.MANUAL)
            points.create(p)
            p.id
        }
        adder.start(manual)
        adder.addFromGallery(listOf(source("g2")))
        waitIdle()

        count(manual) shouldBe 1
    }

    @Test
    fun `S017b RF-12 la foto de la camara se anade al punto de la ficha y se borra el fichero de captura`() {
        val id = measuredPoint()
        adder.start(id)

        val path = adder.beginCapture().shouldNotBeNull()
        File(path).writeBytes(jpeg())
        adder.finishCapture(taken = true)
        waitIdle()

        count(id) shouldBe 1
        runBlocking { photos.photos(id).first() }.single().origin shouldBe PhotoOrigin.CAMERA
        File(path).exists() shouldBe false
    }

    @Test
    fun `S017b RF-13 con 9 fotos y 3 elegidas entra una y se dice que 2 se quedan fuera`() {
        val id = measuredPoint()
        adder.start(id)
        adder.addFromGallery((1..9).map { source("a$it") })
        waitIdle()
        count(id) shouldBe 9

        adder.addFromGallery(listOf(source("b1"), source("b2"), source("b3")))
        waitIdle()

        count(id) shouldBe 10
        adder.ui.value.error shouldBe PhotoError.NotAllFit(2)
    }

    @Test
    fun `S017b RF-14 con 10 fotos no entra ninguna mas y se indica el limite`() {
        val id = measuredPoint()
        adder.start(id)
        adder.addFromGallery((1..10).map { source("a$it") })
        waitIdle()

        adder.addFromGallery(listOf(source("extra")))
        waitIdle()

        count(id) shouldBe 10
        adder.ui.value.error shouldBe PhotoError.Full
    }

    @Test
    fun `S017b RF-15 un fichero que no se puede leer no se anade y dice el motivo`() {
        val id = measuredPoint()
        adder.start(id)

        adder.addFromGallery(listOf("no-existe"))
        waitIdle()

        count(id) shouldBe 0
        adder.ui.value.error shouldBe PhotoError.Failed(PhotoFailure.UNREADABLE)
        runBlocking { photos.events(id).first() } shouldBe emptyList()
    }

    @Test
    fun `S017b RF-15 sin espacio la foto no se anade y el punto queda como estaba`() {
        val id = measuredPoint()
        adder.start(id)
        adder.addFromGallery(listOf(source("ok")))
        waitIdle()
        failWith = IOException("No space left on device")

        adder.addFromGallery(listOf(source("sin-espacio")))
        waitIdle()

        count(id) shouldBe 1
        adder.ui.value.error shouldBe PhotoError.Failed(PhotoFailure.NO_SPACE)
        store.pendingDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S017b RF-16 cerrar la galeria o la camara sin elegir no cambia nada`() {
        val id = measuredPoint()
        adder.start(id)

        adder.addFromGallery(emptyList())
        val path = adder.beginCapture().shouldNotBeNull()
        adder.finishCapture(taken = false)

        count(id) shouldBe 0
        adder.ui.value shouldBe PhotoAddUi()
        File(path).exists() shouldBe false
    }

    @Test
    fun `S017b RF-12 si Android cierra la app con la camara abierta la foto se anade al volver`() {
        val id = measuredPoint()
        val handle = SavedStateHandle()
        val before = PointPhotoAdder(scope, photos, store, handle).also { it.start(id) }
        val path = before.beginCapture().shouldNotBeNull()
        File(path).writeBytes(jpeg())

        // La ficha se recrea con el mismo estado guardado.
        val after = PointPhotoAdder(scope, photos, store, handle).also { it.start(id) }
        after.finishCapture(taken = true)
        runBlocking { withTimeout(10_000) { photos.photos(id).first { it.size == 1 } } }

        count(id) shouldBe 1
    }
}
