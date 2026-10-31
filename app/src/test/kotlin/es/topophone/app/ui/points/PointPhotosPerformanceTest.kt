package es.topophone.app.ui.points

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.photos.PhotoImages
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.PointPhoto
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.shouldBeLessThan
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** La ficha de un punto con 10 fotos de 2.048 px se compone y carga sus miniaturas en menos de 1 s (S017b, RNF). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PointPhotosPerformanceTest {

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

    @Test
    fun `S017b RNF la ficha con 10 fotos de 2048 px se abre en menos de 1 s`() {
        val point = PointFixtures.measured("12")
        val files = (1..10).associate { "f$it" to folder.newFile("f$it.jpg") }
        val master = Bitmap.createBitmap(2_048, 1_536, Bitmap.Config.ARGB_8888)
        files.values.forEach { file -> file.outputStream().use { master.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        val photos = files.keys.mapIndexed { i, id ->
            PointPhoto(id, point.id, "12_$i.jpg", PhotoOrigin.CAMERA, 1_000L + i, 2_000L + i, 800_000L, 2_048, 1_536)
        }
        val images = PhotoImages({ files[it] as File? })

        val start = System.nanoTime()
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
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTagCount(photoThumbnailTag(9)) > 0
        }
        compose.waitForIdle()
        // Las miniaturas se cargan a unos 256 px, no a tamaño completo.
        files.keys.forEach { id -> kotlinx.coroutines.runBlocking { images.thumbnail(id) } }
        val seconds = (System.nanoTime() - start) / 1e9

        compose.onNodeWithTag(POINT_PHOTOS_TAG).assertExists()
        seconds shouldBeLessThan 1.0
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
