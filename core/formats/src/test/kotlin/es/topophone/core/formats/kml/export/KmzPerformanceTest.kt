package es.topophone.core.formats.kml.export

import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.random.Random
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Escribir un KMZ con 100 fotos de 1 MB tarda menos de 10 s (S031, RNF). La JVM sirve de cota; la medida en el móvil de referencia es de la
 * demo.
 */
class KmzPerformanceTest {

    @Test
    fun `S031 RNF un KMZ con 100 fotos de 1 MB tarda menos de 10 s`(@TempDir folder: File) {
        val random = Random(7)
        val files = (0 until PHOTOS).map { i -> File(folder, "f$i.jpg").also { it.writeBytes(random.nextBytes(PHOTO_BYTES)) } }
        val marks = (0 until PHOTOS).map { i ->
            val point = KmlGeometry.Point(KmlVertex(-3.7 + i * 1e-5, 40.4))
            KmlMark("P$i", "Punto $i", null, emptyList(), point, false, listOf("P${i}_1.jpg"))
        }
        val document = KmlDocument("Obra", emptyList(), listOf(KmlFolder("Obra", marks)))
        val photos = files.mapIndexed { i, file -> KmzPhoto("P${i}_1.jpg") { file.inputStream() } }
        val kmz = File(folder, "obra.kmz")

        val timed = measureTimedValue { kmz.outputStream().buffered(1 shl 20).use { KmzWriter.write(document, photos, it) } }

        println("S031 KMZ con $PHOTOS fotos de 1 MB: ${timed.duration.inWholeMilliseconds} ms, ${kmz.length() / (1024 * 1024)} MB")
        timed.value shouldBe KmzResult(PHOTOS, 0)
        (kmz.length() >= PHOTOS.toLong() * PHOTO_BYTES) shouldBe true
        (timed.duration.inWholeMilliseconds < 10_000) shouldBe true
    }

    private companion object {
        const val PHOTOS = 100
        const val PHOTO_BYTES = 1 shl 20
    }
}
