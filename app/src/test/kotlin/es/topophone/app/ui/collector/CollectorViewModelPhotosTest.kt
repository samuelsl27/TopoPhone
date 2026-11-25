package es.topophone.app.ui.collector

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PhotoDestination
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.photo.PhotoFailure
import es.topophone.data.photo.PhotoStore
import es.topophone.data.photo.RoomPhotoRepository
import es.topophone.data.point.CollectorPreferences
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.receiver.platform.Alerts
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Las fotos en el recolector, con la `ReceiverSession` real y las épocas de una en una: a qué punto van, las que esperan a su punto,
 * cancelar con confirmación y los límites (S017b/RF-1 a RF-8, RF-13 a RF-16).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-rES")
class CollectorViewModelPhotosTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val projects = RoomProjectRepository(db)
    private val points = RoomPointRepository(db)
    private val preferences = object : CollectorPreferences {
        override val settings = MutableStateFlow(CollectorSettings(mode = MeasurementMode.AVERAGED, averagingSeconds = 600))

        override suspend fun save(settings: CollectorSettings) {
            this.settings.value = settings
        }
    }
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(
        sessionScope,
        NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE },
        noDataTimeoutMs = 60_000,
        watchdogPeriodMs = 1_000,
    )
    private val toApp = PipedOutputStream()
    private val alerts = object : Alerts {
        override fun tiltLost() = Unit

        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit
    }
    private val viewModels = mutableListOf<CollectorViewModel>()
    private val sources = mutableMapOf<String, ByteArray>()
    private lateinit var store: PhotoStore
    private lateinit var photos: RoomPhotoRepository
    private val handle = SavedStateHandle()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store =
            PhotoStore(folder.newFolder("files"), {
                sources[it]?.let(::ByteArrayInputStream) ?: File(it).takeIf(File::isFile)?.inputStream()
            }, captureDir = folder.newFolder("capture"))
        photos = RoomPhotoRepository(db, points, store)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        runCatching { toApp.close() }
        session.disconnect()
        sessionScope.cancel()
        db.close()
        Dispatchers.resetMain()
    }

    private fun project(): String = runBlocking {
        val draft = ProjectDefaults().toDraft().copy(name = "Calle Mayor 12", heightSystem = HeightSystem.ELLIPSOIDAL)
        (projects.create(draft) as SaveResult.Saved).id
    }

    private fun viewModel(projectId: String, state: SavedStateHandle = handle): CollectorViewModel {
        val vm = CollectorViewModel(
            projects,
            points,
            preferences,
            session,
            alerts,
            { ProjectPositionCalculator() },
            MeasurementInProgress(),
            photoRepository = photos,
            photoStore = store,
            savedState = state,
        )
        viewModels += vm
        vm.start(projectId)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        CoroutineScope(Dispatchers.Unconfined).launch { vm.photos.ui.collect {} }
        await(vm) { it.loaded }
        runBlocking { withTimeout(10_000) { while (!vm.isProjectLoaded) kotlinx.coroutines.delay(10) } }
        return vm
    }

    private fun await(vm: CollectorViewModel, predicate: (CollectorUiState) -> Boolean): CollectorUiState =
        runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    private fun awaitPhotos(vm: CollectorViewModel, predicate: (PhotoUi) -> Boolean): PhotoUi =
        runBlocking { withTimeout(10_000) { vm.photos.ui.first(predicate) } }

    private fun sendEpoch(second: Int) {
        val time = "1015%02d.00".format(30 + second)
        toApp.write(nmea("GNGGA,$time,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
        toApp.write(nmea("GNGST,$time,0.012,0.010,0.007,45.0,0.008,0.006,0.015").toByteArray())
        toApp.flush()
    }

    private fun receiving(vm: CollectorViewModel) {
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        sendEpoch(0)
        sendEpoch(1)
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.epoch != null } } }
        await(vm) { it.live != null }
        await(vm) { it.blocker == null }
    }

    private fun jpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun source(name: String) = name.also { sources[it] = jpeg() }

    /** Una medida promediada en marcha, que no termina hasta pulsar «Terminar». */
    private fun startMeasuring(vm: CollectorViewModel) {
        vm.measure()
        await(vm) { it.measuring }
    }

    private fun addGallery(vm: CollectorViewModel, vararg names: String) {
        vm.photos.addFromGallery(names.map(::source))
        awaitPhotos(vm) { !it.adding }
    }

    private fun pendingFiles() = store.pendingDir.listFiles().orEmpty().filter { it.isFile }

    private fun finish(vm: CollectorViewModel, savedName: String) {
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { (it.progress?.epochsUsed ?: 0) >= 2 }
        vm.finishNow()
        await(vm) { it.savedName == savedName }
        awaitPhotos(vm) { it.destination is PhotoDestination.Saved }
    }

    @Test
    fun `S017b RF-7 sin ningun punto no hay destino y los botones no se pueden usar`() {
        val vm = viewModel(project())
        receiving(vm)

        val ui = vm.photos.ui.value
        ui.destination shouldBe PhotoDestination.None
        ui.canAdd shouldBe false
        vm.photos.beginCapture() shouldBe null
    }

    @Test
    fun `S017b RF-2 y RF-8 durante la medida la foto va al punto en medida y se dice cuantas lleva`() {
        val vm = viewModel(project())
        receiving(vm)
        startMeasuring(vm)

        addGallery(vm, "a", "b")

        val ui = vm.photos.ui.value
        ui.destination shouldBe PhotoDestination.Measuring
        ui.destinationName shouldBe "1"
        ui.count shouldBe 2
        ui.canAdd shouldBe true
        pendingFiles().size shouldBe 2
    }

    @Test
    fun `S017b RF-2 al guardar el punto sus fotos se guardan con el y dejan de estar en espera`() {
        val id = project()
        val vm = viewModel(id)
        receiving(vm)
        startMeasuring(vm)
        addGallery(vm, "a", "b")

        finish(vm, "1")

        val point = runBlocking { points.points(id).first() }.single()
        runBlocking { photos.photos(point.id).first() }.size shouldBe 2
        pendingFiles() shouldBe emptyList()
        vm.photos.ui.value.destination shouldBe PhotoDestination.Saved(point.id)
        vm.photos.ui.value.count shouldBe 2
    }

    @Test
    fun `S017b RF-5 sin medida la foto va al ultimo punto guardado y se anade en el acto`() {
        val id = project()
        val vm = viewModel(id)
        receiving(vm)
        startMeasuring(vm)
        finish(vm, "1")
        val point = runBlocking { points.points(id).first() }.single()

        addGallery(vm, "c")

        runBlocking { photos.photos(point.id).first() }.size shouldBe 1
        awaitPhotos(vm) { it.count == 1 }.destinationName shouldBe "1"
        runBlocking { photos.events(point.id).first() }.size shouldBe 1
    }

    @Test
    fun `S017b RF-5 al empezar otra medida el ultimo punto deja de ser el destino`() {
        val id = project()
        val vm = viewModel(id)
        receiving(vm)
        startMeasuring(vm)
        finish(vm, "1")

        startMeasuring(vm)

        vm.photos.ui.value.destination shouldBe PhotoDestination.Measuring
        vm.photos.ui.value.count shouldBe 0
    }

    @Test
    fun `S017b RF-3 cancelar con fotos pide confirmacion diciendo cuantas y la medida sigue`() {
        val vm = viewModel(project())
        receiving(vm)
        startMeasuring(vm)
        addGallery(vm, "a", "b", "c")

        vm.cancel()

        vm.photos.ui.value.pendingCancel shouldBe 3
        vm.state.value.measuring shouldBe true
        vm.photos.keepPhotos()
        vm.photos.ui.value.pendingCancel shouldBe null
        vm.photos.ui.value.count shouldBe 3
    }

    @Test
    fun `S017b RF-3 y RF-4 confirmar descarta las fotos y cancela la medida`() {
        val id = project()
        val vm = viewModel(id)
        receiving(vm)
        startMeasuring(vm)
        addGallery(vm, "a", "b")
        vm.cancel()

        vm.confirmCancelWithPhotos()

        // `state` junta varios flujos y llega un poco después: se espera, no se lee al momento (en un runner lento fallaba).
        await(vm) { !it.measuring }.measuring shouldBe false
        vm.photos.ui.value.destination shouldBe PhotoDestination.None
        pendingFiles() shouldBe emptyList()
        runBlocking { points.points(id).first() } shouldBe emptyList()
        runBlocking { db.photos().allIds() } shouldBe emptyList()
    }

    @Test
    fun `S017b RF-3 cancelar sin fotos cancela sin preguntar`() {
        val vm = viewModel(project())
        receiving(vm)
        startMeasuring(vm)

        vm.cancel()

        await(vm) { !it.measuring }
        vm.photos.ui.value.pendingCancel shouldBe null
    }

    @Test
    fun `S017b RF-4 fuera de tolerancia sin confirmar las fotos se descartan y confirmando se guardan`() {
        preferences.settings.value = preferences.settings.value.copy(
            precision = PrecisionControl(admitted = AdmittedSolutions.ALL, horizontalLimitM = 0.001),
        )
        val id = project()
        val vm = viewModel(id)
        receiving(vm)
        startMeasuring(vm)
        addGallery(vm, "a")
        sendEpoch(2)
        sendEpoch(3)
        await(vm) { (it.progress?.epochsUsed ?: 0) >= 2 }
        vm.finishNow()
        await(vm) { it.pending != null }
        // Esperando confirmar la foto se puede seguir añadiendo (RF-2).
        vm.photos.ui.value.destination shouldBe PhotoDestination.Measuring

        vm.discardPending()

        pendingFiles() shouldBe emptyList()
        runBlocking { points.points(id).first() } shouldBe emptyList()

        startMeasuring(vm)
        addGallery(vm, "b")
        sendEpoch(4)
        sendEpoch(5)
        await(vm) { (it.progress?.epochsUsed ?: 0) >= 2 }
        vm.finishNow()
        await(vm) { it.pending != null }
        vm.confirmOutOfTolerance()
        await(vm) { it.savedName == "1" }
        val point = runBlocking { points.points(id).first() }.single()
        runBlocking { photos.photos(point.id).first() }.size shouldBe 1
    }

    @Test
    fun `S017b RF-13 de una seleccion de 12 caben 10 y se dice cuantas quedan fuera`() {
        val vm = viewModel(project())
        receiving(vm)
        startMeasuring(vm)

        addGallery(vm, *(1..12).map { "g$it" }.toTypedArray())

        val ui = vm.photos.ui.value
        ui.count shouldBe 10
        ui.error shouldBe PhotoError.NotAllFit(2)
        ui.canAdd shouldBe false
    }

    @Test
    fun `S017b RF-14 con 10 fotos no se anade ninguna mas y se indica el limite`() {
        val vm = viewModel(project())
        receiving(vm)
        startMeasuring(vm)
        addGallery(vm, *(1..10).map { "g$it" }.toTypedArray())
        vm.photos.dismissError()

        addGallery(vm, "extra")

        val ui = vm.photos.ui.value
        ui.count shouldBe 10
        ui.error shouldBe PhotoError.Full
        pendingFiles().size shouldBe 10
    }

    @Test
    fun `S017b RF-15 una foto que no se puede leer no se anade y dice el motivo`() {
        val vm = viewModel(project())
        receiving(vm)
        startMeasuring(vm)

        vm.photos.addFromGallery(listOf("no-existe"))
        awaitPhotos(vm) { !it.adding }

        val ui = vm.photos.ui.value
        ui.count shouldBe 0
        ui.error shouldBe PhotoError.Failed(PhotoFailure.UNREADABLE)
        pendingFiles() shouldBe emptyList()
    }

    @Test
    fun `S017b RF-16 cerrar la galeria o la camara sin elegir no cambia nada`() {
        val vm = viewModel(project())
        receiving(vm)
        startMeasuring(vm)
        addGallery(vm, "a")
        val before = vm.photos.ui.value

        vm.photos.addFromGallery(emptyList())
        val path = vm.photos.beginCapture().shouldNotBeNull()
        vm.photos.finishCapture(taken = false)

        vm.photos.ui.value shouldBe before
        File(path).exists() shouldBe false
    }

    @Test
    fun `S017b RF-1 la foto de la camara se anade al punto en medida`() {
        val vm = viewModel(project())
        receiving(vm)
        startMeasuring(vm)

        val path = vm.photos.beginCapture().shouldNotBeNull()
        File(path).writeBytes(jpeg())
        vm.photos.finishCapture(taken = true)
        awaitPhotos(vm) { !it.adding && it.count == 1 }

        vm.photos.ui.value.count shouldBe 1
        File(path).exists() shouldBe false
    }

    @Test
    fun `S017b RF-1 si Android cierra la app con la camara abierta la foto de un punto guardado se anade al volver`() {
        val id = project()
        val vm = viewModel(id)
        receiving(vm)
        startMeasuring(vm)
        finish(vm, "1")
        val path = vm.photos.beginCapture().shouldNotBeNull()
        File(path).writeBytes(jpeg())
        val point = runBlocking { points.points(id).first() }.single()

        // Otra instancia del recolector con el mismo estado guardado: la app se recreó.
        val again = viewModel(id)
        again.photos.finishCapture(taken = true)

        runBlocking { withTimeout(10_000) { photos.photos(point.id).first { it.size == 1 } } }.size shouldBe 1
    }

    @Test
    fun `S017b RF-1 si Android cierra la app con la camara abierta y la medida ya no existe la foto se descarta`() {
        val id = project()
        val vm = viewModel(id)
        receiving(vm)
        startMeasuring(vm)
        val path = vm.photos.beginCapture().shouldNotBeNull()
        File(path).writeBytes(jpeg())

        // La app se recrea: la medida ya no está.
        vm.cancel()
        val again = viewModel(id)
        again.photos.finishCapture(taken = true)
        awaitPhotos(again) { !it.adding }

        pendingFiles() shouldBe emptyList()
        runBlocking { db.photos().allIds() } shouldBe emptyList()
    }

    @Test
    fun `S017b RF-6 y RF-7 con el modo automatico las fotos van al ultimo punto que guardo`() {
        preferences.settings.value = CollectorSettings(mode = MeasurementMode.INSTANT)
        val id = project()
        val vm = viewModel(id)
        receiving(vm)
        vm.setAutoEnabled(true)
        await(vm) { it.autoEnabled }

        vm.startAuto()
        // Sin ningun punto guardado todavia, el modo automatico no tiene a donde mandar la foto (RF-7).
        vm.photos.ui.value.destination shouldBe PhotoDestination.None
        sendEpoch(2)
        sendEpoch(3)
        awaitPhotos(vm) { it.destination is PhotoDestination.Saved }
        val first = runBlocking { points.points(id).first() }.first()

        vm.state.value.auto.running shouldBe true
        addGallery(vm, "auto")

        vm.photos.ui.value.destination shouldBe PhotoDestination.Saved(first.id)
        runBlocking { photos.photos(first.id).first() }.size shouldBe 1
        vm.stopAuto()
    }
}
