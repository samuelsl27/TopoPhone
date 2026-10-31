package es.topophone.app.ui.points

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.photos.PhotoImages
import es.topophone.core.designsystem.theme.TopoPhoneTheme
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
}
