package es.topophone.app.ui.points

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.photos.PhotoImages
import es.topophone.core.model.point.HistoryRecord
import es.topophone.core.model.point.PhotoAction
import es.topophone.core.model.point.PointHistory
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.photo.PhotoStore
import es.topophone.data.photo.RoomPhotoRepository
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Borrar una foto desde la ficha deja su rastro en el historial y no marca el punto como editado (S017b/RF-22, RF-23). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PointDetailsPhotosViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val points = RoomPointRepository(db)
    private var clock = 1_000L
    private lateinit var photos: RoomPhotoRepository
    private lateinit var vm: PointDetailsViewModel
    private lateinit var projectId: String
    private lateinit var pointId: String

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val bytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        val store = PhotoStore(folder.newFolder("files"), { ByteArrayInputStream(bytes) }, captureDir = folder.newFolder("capture"))
        photos = RoomPhotoRepository(db, points, store, clock = { clock })
        val projects = RoomProjectRepository(db)
        projectId = runBlocking { (projects.create(ProjectDefaults().toDraft().copy(name = "P")) as SaveResult.Saved).id }
        val point = PointFixtures.measured("12").copy(projectId = projectId)
        pointId = point.id
        runBlocking { points.create(point) }
        vm = PointDetailsViewModel(points, projects, photos, PhotoImages(photos::file), store) { ProjectPositionCalculator() }
        vm.start(projectId, pointId)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun await(predicate: (PointDetailsUiState) -> Boolean) = runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    @Test
    fun `S017b RF-22 y RF-23 anadir y borrar una foto deja los dos sucesos y el punto sigue sin editar`() {
        vm.photoAdder.addFromGallery(listOf("galeria-1"))
        val added = await { it.photos.size == 1 }.photos.single()
        clock = 5_000

        vm.deletePhoto(added)
        val state = await { it.photos.isEmpty() && it.photoEvents.size == 2 }

        state.photoEvents.map { it.action } shouldBe listOf(PhotoAction.DELETED, PhotoAction.ADDED)
        state.photoEvents.first().photoName shouldBe added.name
        state.photoEvents.first().atMs shouldBe 5_000L
        state.photoEvents.first().photoTakenAtMs shouldBe added.takenAtMs
        state.point!!.edited shouldBe false
        state.history shouldBe emptyList()
        state.photos shouldBe emptyList()
    }

    @Test
    fun `S017b RF-23 el historial mezcla los sucesos de foto con las versiones del punto del mas reciente al mas antiguo`() {
        vm.photoAdder.addFromGallery(listOf("galeria-1"))
        val state = await { it.photoEvents.size == 1 }
        clock = 9_000

        val timeline = HistoryRecord.timeline(PointHistory.items(state.history, state.point!!), state.photoEvents)

        timeline.size shouldBe 1
        (timeline.single() as HistoryRecord.Photo).event.action shouldBe PhotoAction.ADDED
    }

    @Test
    fun `S017b RF-22 borrar una foto que ya no existe no hace nada`() {
        vm.photoAdder.addFromGallery(listOf("galeria-1"))
        val added = await { it.photos.size == 1 }.photos.single()
        vm.deletePhoto(added)
        await { it.photos.isEmpty() }

        vm.deletePhoto(added)

        await { it.photoEvents.size == 2 }.photoEvents.size shouldBe 2
    }
}
