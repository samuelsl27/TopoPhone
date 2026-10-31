package es.topophone.data.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import es.topophone.core.model.point.PhotoOrigin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Reducir y guardar la foto con sus metadatos y sin dejar nada a medias (S017b/RF-15, RF-18, RF-19, RF-20). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sources = mutableMapOf<String, () -> InputStream?>()
    private val store by lazy { PhotoStore(folder.newFolder("files"), { sources[it]?.invoke() }, clock = { 1_700_000_000_000 }) }

    private fun jpeg(width: Int, height: Int, exif: Map<String, String> = emptyMap()): ByteArray {
        val file = folder.newFile()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (exif.isNotEmpty()) {
            val e = ExifInterface(file.path)
            exif.forEach { (k, v) -> e.setAttribute(k, v) }
            e.saveAttributes()
        }
        return file.readBytes().also { file.delete() }
    }

    private fun add(name: String, bytes: ByteArray) {
        sources[name] = { ByteArrayInputStream(bytes) }
    }

    private fun size(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    private fun staged(result: StageResult) = result.shouldBeInstanceOf<StageResult.Staged>().photo

    @Test
    fun `S017b RF-18 una foto de 12 MP baja a 2048 px de lado largo`() {
        add("big", jpeg(4_000, 3_000))

        val photo = staged(store.stage("big", PhotoOrigin.CAMERA))

        photo.width to photo.height shouldBe (2_048 to 1_536)
        size(store.pendingFile(photo.id)) shouldBe (2_048 to 1_536)
        photo.sizeBytes shouldBe store.pendingFile(photo.id).length()
        photo.origin shouldBe PhotoOrigin.CAMERA
    }

    @Test
    fun `S017b RF-18 una foto menor que el maximo se copia tal cual sin recomprimir`() {
        val bytes = jpeg(1_600, 1_200)
        add("small", bytes)

        val photo = staged(store.stage("small", PhotoOrigin.GALLERY))

        store.pendingFile(photo.id).readBytes().toList() shouldBe bytes.toList()
        photo.width to photo.height shouldBe (1_600 to 1_200)
    }

    @Test
    fun `S017b RF-18 la fecha, la orientacion y la ubicacion se conservan`() {
        add(
            "exif",
            jpeg(
                3_000,
                2_000,
                mapOf(
                    ExifInterface.TAG_DATETIME_ORIGINAL to "2026:10:30 14:05:09",
                    ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_ROTATE_90.toString(),
                    ExifInterface.TAG_GPS_LATITUDE to "40/1,25/1,0/1",
                    ExifInterface.TAG_GPS_LATITUDE_REF to "N",
                    ExifInterface.TAG_GPS_LONGITUDE to "3/1,42/1,0/1",
                    ExifInterface.TAG_GPS_LONGITUDE_REF to "W",
                    ExifInterface.TAG_MAKE to "Fabrica",
                ),
            ),
        )

        val photo = staged(store.stage("exif", PhotoOrigin.GALLERY))

        val exif = ExifInterface(store.pendingFile(photo.id).path)
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) shouldBe "2026:10:30 14:05:09"
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) shouldBe ExifInterface.ORIENTATION_ROTATE_90
        exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF) shouldBe "N"
        exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF) shouldBe "W"
        exif.getAttribute(ExifInterface.TAG_MAKE) shouldBe "Fabrica"
        photo.takenAtMs shouldBe LocalDateTime.of(2026, 10, 30, 14, 5, 9).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    @Test
    fun `S017b RF-11 sin fecha en los metadatos la fecha es la de cuando se anade`() {
        add("plain", jpeg(800, 600))

        staged(store.stage("plain", PhotoOrigin.CAMERA)).takenAtMs shouldBe 1_700_000_000_000
    }

    @Test
    fun `S017b RF-19 la foto original no cambia`() {
        val bytes = jpeg(4_000, 3_000, mapOf(ExifInterface.TAG_MAKE to "Fabrica"))
        val original = folder.newFile("galeria.jpg").apply { writeBytes(bytes) }
        sources["gallery"] = { original.inputStream() }
        val before = original.lastModified()

        store.stage("gallery", PhotoOrigin.GALLERY)

        original.readBytes().toList() shouldBe bytes.toList()
        original.lastModified() shouldBe before
    }

    @Test
    fun `S017b RF-15 un fichero ilegible no deja nada`() {
        add("junk", ByteArray(2_000) { (it * 7).toByte() })

        store.stage("junk", PhotoOrigin.GALLERY) shouldBe StageResult.Failed(PhotoFailure.UNREADABLE)

        store.pendingDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S017b RF-15 una fuente que no se puede abrir falla sin dejar nada`() {
        store.stage("missing", PhotoOrigin.CAMERA) shouldBe StageResult.Failed(PhotoFailure.UNREADABLE)

        store.pendingDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S017b RF-20 un fallo de escritura a medias no deja ni la foto ni el temporal`() {
        val bytes = jpeg(3_000, 2_000)
        sources["broken"] = {
            object : InputStream() {
                private var n = 0

                override fun read(): Int {
                    if (n >= 1_000) throw IOException("No space left on device")
                    return bytes[n++].toInt() and 0xFF
                }
            }
        }

        store.stage("broken", PhotoOrigin.CAMERA) shouldBe StageResult.Failed(PhotoFailure.NO_SPACE)

        store.pendingDir.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S017b RF-4 descartar una foto en espera borra su fichero`() {
        add("a", jpeg(800, 600))
        val photo = staged(store.stage("a", PhotoOrigin.CAMERA))

        store.discard(photo.id)

        store.pendingFile(photo.id).exists() shouldBe false
    }
}
