package es.topophone.data.photo

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.PhotoOrigin
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Reducir y guardar una foto de 12 MP termina en menos de 2 s (S017b, RNF-T13). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoPerformanceTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `S017b RNF-T13 reducir y guardar una foto de 12 MP termina en menos de 2 s`() {
        val twelveMp = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4_000, 3_000, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        val store = PhotoStore(folder.newFolder("files"), { ByteArrayInputStream(twelveMp) })
        // Calienta los códecs con una primera foto, que no se mide.
        store.stage("x", PhotoOrigin.CAMERA).shouldBeInstanceOf<StageResult.Staged>()

        val start = System.nanoTime()
        val photo = store.stage("x", PhotoOrigin.CAMERA).shouldBeInstanceOf<StageResult.Staged>().photo
        val seconds = (System.nanoTime() - start) / 1e9

        photo.width to photo.height shouldBe (2_048 to 1_536)
        seconds shouldBeLessThan 2.0
    }
}
