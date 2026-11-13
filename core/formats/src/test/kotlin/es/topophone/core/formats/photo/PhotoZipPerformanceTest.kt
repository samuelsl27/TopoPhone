package es.topophone.core.formats.photo

import es.topophone.core.model.point.PhotoOrigin
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.random.Random
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Escribir un ZIP con 100 fotos de 1 MB tarda menos de 10 s (S056, RNF). La JVM sirve de cota; la medida en el móvil de referencia es de la
 * demo.
 */
class PhotoZipPerformanceTest {

    @Test
    fun `S056 RNF un ZIP con 100 fotos de 1 MB tarda menos de 10 s`(@TempDir folder: File) {
        val random = Random(7)
        val files = (0 until PHOTOS).map { i -> File(folder, "f$i.jpg").also { it.writeBytes(random.nextBytes(PHOTO_BYTES)) } }
        val entries = files.mapIndexed { i, file ->
            PhotoZipEntry(PhotoIndexRow("P${i}_1.jpg", "P$i", 1, 1_794_471_330_000, PhotoOrigin.CAMERA, PHOTO_BYTES.toLong()), "id-$i") {
                file.inputStream()
            }
        }
        val zip = File(folder, "fotos.zip")

        val timed = measureTimedValue { zip.outputStream().buffered(1 shl 20).use { PhotoZipWriter.write(entries, it) } }

        println("S056 ZIP con $PHOTOS fotos de 1 MB: ${timed.duration.inWholeMilliseconds} ms, ${zip.length() / (1024 * 1024)} MB")
        timed.value.photosWritten shouldBe PHOTOS
        (zip.length() >= PHOTOS.toLong() * PHOTO_BYTES) shouldBe true
        (timed.duration.inWholeMilliseconds < 10_000) shouldBe true
    }

    private companion object {
        const val PHOTOS = 100
        const val PHOTO_BYTES = 1 shl 20
    }
}
