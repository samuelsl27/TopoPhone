package es.topophone.app.ui.points

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.PhotoError
import es.topophone.app.ui.photos.PhotoImages
import es.topophone.app.ui.projects.CONFIRM_TAG
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PhotoAction
import es.topophone.core.model.point.PhotoEvent
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointPhoto
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.data.photo.PhotoFailure
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Las fotos en la ficha de un punto: miniaturas, visor, añadir, borrar y el historial (S017b/RF-9 a RF-17, RF-22, RF-23). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "es-rES")
class PointPhotosScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private val point: Point = PointFixtures.measured("12")
    private val files = mutableMapOf<String, File>()
    private val images = PhotoImages({ files[it] })

    private fun jpeg(id: String, width: Int = 320, height: Int = 240, orientation: Int? = null): File {
        val file = folder.newFile("$id.jpg")
        file.outputStream().use { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (orientation != null) {
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return file.also { files[id] = it }
    }

    private fun photo(id: String, index: Int) =
        PointPhoto(id, point.id, "12_2026103$index.jpg", PhotoOrigin.CAMERA, 1_000L * index, 2_000L * index, 1_000L, 320, 240)

    private fun show(photos: List<PointPhoto>) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    PointDetailsUiState(project, point, photos = photos),
                    PointDetailsActions(),
                    {},
                    photoImages = images,
                )
            }
        }
    }

    @Test
    fun `S017b RF-9 las miniaturas salen en orden con su numero para TalkBack`() {
        val list = (1..3).map { photo("f$it", it).also { _ -> jpeg("f$it") } }
        show(list)

        compose.onNodeWithTag(POINT_PHOTOS_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 fotos").assertIsDisplayed()
        (0..2).forEach { index ->
            compose.onNodeWithTag(photoThumbnailTag(index)).performScrollTo().assertIsDisplayed()
        }
        compose.onNode(hasContentDescription("Foto 1 de 3 del punto 12")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Foto 2 de 3 del punto 12")).assertIsDisplayed()
        compose.onNode(hasContentDescription("Foto 3 de 3 del punto 12")).assertIsDisplayed()
        compose.onNodeWithTag(photoThumbnailTag(3)).assertDoesNotExist()
    }

    @Test
    fun `S017b RF-9 un punto sin fotos enseña la seccion sin miniaturas`() {
        show(emptyList())

        compose.onNodeWithTag(POINT_PHOTOS_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(photoThumbnailTag(0)).assertDoesNotExist()
    }

    @Test
    fun `S017b RF-17 la foto cuyo fichero no se puede abrir lleva su nombre y un aviso y las demas siguen`() {
        val list = listOf(photo("buena", 1), photo("rota", 2), photo("otra", 3))
        jpeg("buena")
        files["rota"] = folder.newFile("rota.jpg").apply { writeBytes(ByteArray(500) { (it * 3).toByte() }) }
        jpeg("otra")
        show(list)
        compose.waitForIdle()

        compose.onNodeWithTag(photoUnreadableTag(1), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("12_20261032.jpg", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("No se puede abrir la foto", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(photoUnreadableTag(0), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(photoUnreadableTag(2), useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithText("No se puede abrir la foto", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `S017b RF-17 un fichero que falta tambien es una foto que no se puede abrir`() {
        show(listOf(photo("sin-fichero", 1)))
        compose.waitForIdle()

        compose.onNodeWithTag(photoUnreadableTag(0), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S017b RF-9 la miniatura aplica la orientacion de los metadatos sin tocar el fichero`() {
        val file = jpeg("girada", width = 400, height = 200, orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val before = file.readBytes().toList()

        val thumbnail = runBlocking { images.thumbnail("girada") }!!

        // Apaisada en el fichero y girada 90° al mostrarla.
        (thumbnail.height > thumbnail.width) shouldBe true
        file.readBytes().toList() shouldBe before
    }

    @Test
    fun `S017b RF-9 la miniatura no carga la foto a tamano completo`() {
        jpeg("grande", width = 2_048, height = 1_536)

        val thumbnail = runBlocking { images.thumbnail("grande") }!!

        (maxOf(thumbnail.width, thumbnail.height) <= 512) shouldBe true
    }

    @Test
    fun `S017b RF-17 una foto que no se puede decodificar da null y no se guarda en la cache`() {
        files["rota"] = folder.newFile("rota.jpg").apply { writeBytes(ByteArray(100)) }

        runBlocking { images.thumbnail("rota") } shouldBe null
        // Si el fichero se arregla, la siguiente carga lo ve.
        files["rota"]!!.outputStream().use {
            Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        (runBlocking { images.thumbnail("rota") } != null) shouldBe true
    }

    private fun openViewer(count: Int, first: Int = 0): List<PointPhoto> {
        val list = (1..count).map { photo("v$it", it).also { _ -> jpeg("v$it") } }
        show(list)
        compose.onNodeWithTag(photoThumbnailTag(first)).performScrollTo().performClick()
        compose.waitForIdle()
        return list
    }

    @Test
    fun `S017b RF-10 tocar una miniatura abre la foto a pantalla completa y se puede pasar a las otras`() {
        openViewer(count = 3)

        compose.onNodeWithTag(PHOTO_VIEWER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PHOTO_VIEWER_POSITION_TAG).assertTextEquals("Foto 1 de 3")
        compose.onNodeWithTag(PHOTO_VIEWER_PREVIOUS_TAG).assertIsNotEnabled()

        compose.onNodeWithTag(PHOTO_VIEWER_NEXT_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PHOTO_VIEWER_POSITION_TAG).assertTextEquals("Foto 2 de 3")

        compose.onNodeWithTag(PHOTO_VIEWER_NEXT_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PHOTO_VIEWER_POSITION_TAG).assertTextEquals("Foto 3 de 3")
        compose.onNodeWithTag(PHOTO_VIEWER_NEXT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `S017b RF-10 se abre la foto tocada y no la primera`() {
        openViewer(count = 3, first = 2)

        compose.onNodeWithTag(PHOTO_VIEWER_POSITION_TAG).assertTextEquals("Foto 3 de 3")
    }

    @Test
    fun `S017b RF-10 el visor se cierra y vuelve a la ficha`() {
        openViewer(count = 2)

        compose.onNodeWithTag(PHOTO_VIEWER_CLOSE_TAG).performClick()

        compose.onNodeWithTag(PHOTO_VIEWER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(POINT_PHOTOS_TAG).assertExists()
    }

    @Test
    fun `S017b RF-11 con la foto abierta se ven su fecha y hora, su origen y su tamano`() {
        val list = listOf(
            photo("c1", 1).copy(origin = PhotoOrigin.CAMERA, sizeBytes = 850_000L),
            photo("g1", 2).copy(origin = PhotoOrigin.GALLERY, sizeBytes = 1_250_000L),
        )
        list.forEach { jpeg(it.id) }
        show(list)
        compose.onNodeWithTag(photoThumbnailTag(0)).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(PHOTO_VIEWER_INFO_TAG).assert(hasText("Cámara", substring = true) and hasText("850 kB", substring = true))
        compose.onNodeWithTag(PHOTO_VIEWER_INFO_TAG).assert(hasText(list[0].name, substring = true))

        compose.onNodeWithTag(PHOTO_VIEWER_NEXT_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PHOTO_VIEWER_INFO_TAG).assert(hasText("Galería", substring = true) and hasText("1,3 MB", substring = true))
    }

    @Test
    fun `S017b RF-17 en el visor una foto ilegible muestra su aviso y las demas se siguen viendo`() {
        val list = listOf(photo("buena", 1), photo("rota", 2))
        jpeg("buena")
        files["rota"] = folder.newFile("rota.jpg").apply { writeBytes(ByteArray(300) { (it * 5).toByte() }) }
        show(list)
        compose.onNodeWithTag(photoThumbnailTag(1)).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(PHOTO_VIEWER_UNREADABLE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(PHOTO_VIEWER_PREVIOUS_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PHOTO_VIEWER_UNREADABLE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PHOTO_VIEWER_POSITION_TAG).assertTextEquals("Foto 1 de 2")
    }

    @Test
    fun `S017b RF-10 la foto con otra orientacion se ve entera en el visor`() {
        jpeg("girada", width = 400, height = 200, orientation = ExifInterface.ORIENTATION_ROTATE_90)

        val full = runBlocking { images.full("girada", 2_048) }!!

        (full.height > full.width) shouldBe true
    }

    @Test
    fun `S017b RNF-T8 los botones del visor miden 56 dp`() {
        openViewer(count = 2)

        listOf(PHOTO_VIEWER_PREVIOUS_TAG, PHOTO_VIEWER_NEXT_TAG, PHOTO_VIEWER_CLOSE_TAG).forEach {
            compose.onNodeWithTag(it).assertHeightIsAtLeast(56.dp)
        }
    }

    private val calls = mutableListOf<String>()

    private fun showWithAdd(photos: List<PointPhoto>, add: PhotoAddUi = PhotoAddUi()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    PointDetailsUiState(project, point, photos = photos, photoAdd = add),
                    PointDetailsActions(onPhoto = { calls += "photo" }, onGallery = { calls += "gallery" }),
                    {},
                    photoImages = images,
                )
            }
        }
    }

    @Test
    fun `S017b RF-12 Foto y Galeria de la ficha llaman a la camara y a la galeria`() {
        showWithAdd(emptyList())

        compose.onNodeWithTag(POINT_PHOTO_ADD_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(POINT_GALLERY_ADD_TAG).performScrollTo().performClick()

        calls shouldBe listOf("photo", "gallery")
    }

    @Test
    fun `S017b RF-14 con 10 fotos los botones se desactivan y se indica el limite`() {
        val list = (1..10).map { photo("f$it", it % 9 + 1).copy(id = "f$it") }
        list.forEach { jpeg(it.id) }
        showWithAdd(list)

        compose.onNodeWithTag(POINT_PHOTO_ADD_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(POINT_GALLERY_ADD_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Máximo de 10 fotos por punto").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S017b RF-13 y RF-15 los avisos de las fotos que no caben o fallan salen en la ficha`() {
        showWithAdd(emptyList(), PhotoAddUi(error = PhotoError.NotAllFit(2)))
        compose.onNodeWithText("2 fotos no caben y no se han añadido").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S017b RF-15 un fallo al guardar dice el motivo`() {
        showWithAdd(emptyList(), PhotoAddUi(error = PhotoError.Failed(PhotoFailure.NO_SPACE)))
        compose.onNodeWithText("No se ha podido guardar la foto: no hay espacio").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S017b RF-12 mientras se anade una foto los botones se desactivan`() {
        showWithAdd(emptyList(), PhotoAddUi(adding = true))

        compose.onNodeWithTag(POINT_PHOTO_ADD_TAG).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `S017b RNF-T8 Foto y Galeria de la ficha miden 56 dp`() {
        showWithAdd(emptyList())

        compose.onNodeWithTag(POINT_PHOTO_ADD_TAG).performScrollTo().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag(POINT_GALLERY_ADD_TAG).performScrollTo().assertHeightIsAtLeast(56.dp)
    }

    private fun showWithDelete(initial: List<PointPhoto>, events: List<PhotoEvent> = emptyList(), history: Boolean = false) {
        photosState = mutableStateOf(initial)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                PointDetailsScreen(
                    ThemeState.DEFAULT,
                    {},
                    PointDetailsUiState(project, point, photos = photosState.value, photoEvents = events),
                    PointDetailsActions(onDeletePhoto = { deleted += it.id }),
                    {},
                    photoImages = images,
                )
            }
        }
        if (history) compose.onNodeWithTag(POINT_HISTORY_TAG).performClick()
    }

    private lateinit var photosState: androidx.compose.runtime.MutableState<List<PointPhoto>>
    private val deleted = mutableListOf<String>()

    @Test
    fun `S017b RF-22 borrar una foto del visor pide confirmacion y solo la borra al confirmar`() {
        val list = (1..2).map { photo("d$it", it).also { _ -> jpeg("d$it") } }
        showWithDelete(list)
        compose.onNodeWithTag(photoThumbnailTag(1)).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(PHOTO_VIEWER_DELETE_TAG).performClick()
        compose.onNodeWithText("¿Borrar esta foto? No se puede deshacer.").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        deleted shouldBe emptyList()

        compose.onNodeWithTag(PHOTO_VIEWER_DELETE_TAG).performClick()
        compose.onNodeWithTag(CONFIRM_TAG).performClick()
        deleted shouldBe listOf("d2")
    }

    @Test
    fun `S017b RF-22 al borrar la ultima foto el visor se cierra`() {
        val list = listOf(photo("u1", 1).also { jpeg("u1") })
        showWithDelete(list)
        compose.onNodeWithTag(photoThumbnailTag(0)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(PHOTO_VIEWER_TAG).assertIsDisplayed()

        photosState.value = emptyList()
        compose.waitForIdle()

        compose.onNodeWithTag(PHOTO_VIEWER_TAG).assertDoesNotExist()
    }

    @Test
    fun `S017b RF-23 el historial enseña las fotos anadidas y borradas con su nombre y su fecha`() {
        val events = listOf(
            PhotoEvent(atMs = 9_000, action = PhotoAction.DELETED, photoName = "12_20261030-101500.jpg", photoTakenAtMs = 4_000),
            PhotoEvent(atMs = 3_000, action = PhotoAction.ADDED, photoName = "12_20261030-101500.jpg", photoTakenAtMs = 4_000),
        )
        showWithDelete(emptyList(), events, history = true)

        compose.onNodeWithTag(HISTORY_DIALOG_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(HISTORY_PHOTO_TAG).assertCountEquals(2)
        compose.onAllNodesWithText("Foto borrada", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Foto añadida", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("12_20261030-101500.jpg", substring = true).assertCountEquals(2)
    }
}
