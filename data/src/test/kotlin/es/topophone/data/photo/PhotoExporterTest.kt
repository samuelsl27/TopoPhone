package es.topophone.data.photo

import es.topophone.core.formats.photo.PhotoZipResult
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.PointPhoto
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Escribir el ZIP de fotos: nombres, bytes, tamaño previsto, espacio, 1 GB, fallos sin dejar fichero y fotos que faltan (S056). */
class PhotoExporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val files = HashMap<String, File>()

    private val repository = object : PhotoRepository by PhotoRepository.NONE {
        override fun file(photoId: String): File? = files[photoId]
    }

    private fun photo(id: String, point: String, bytes: ByteArray?, size: Long = bytes?.size?.toLong() ?: 100): PointPhoto {
        if (bytes != null) files[id] = folder.newFile("$id.jpg").also { it.writeBytes(bytes) }
        return PointPhoto(id, point, "$id.jpg", PhotoOrigin.CAMERA, 1_794_471_330_000, 1_000, size, 640, 480)
    }

    private fun content(seed: Int, size: Int = 3_000) = Random(seed).nextBytes(size)

    private fun target(name: String = "fotos.zip") = File(folder.root, name)

    private fun exporter(freeSpace: Long = Long.MAX_VALUE, open: (File) -> InputStream = { it.inputStream() }) =
        PhotoExporter(repository, { freeSpace }, open)

    private fun written(outcome: PhotoExportOutcome): PhotoZipResult = outcome.shouldBeInstanceOf<PhotoExportOutcome.Written>().result

    private fun ZipFile.read(name: String) = getInputStream(getEntry(name)).use { it.readBytes() }

    @Test
    fun `S056 RF-3 cuenta fotos, puntos con fotos y lo que ocupan`() {
        val points = listOf(
            PhotoExportPoint("a", "A", listOf(photo("1", "a", null, 1_000), photo("2", "a", null, 2_500))),
            PhotoExportPoint("b", "B", emptyList()),
            PhotoExportPoint("c", "C", listOf(photo("3", "c", null, 700))),
        )

        exporter().estimate(points) shouldBe PhotoExportEstimate(photos = 3, points = 2, photoBytes = 4_200)
    }

    @Test
    fun `S056 RF-10 el aviso sale con mas de 1 GB y no antes`() {
        val below = PhotoExportEstimate(photos = 1, points = 1, photoBytes = (1L shl 30) - 10_000)
        val above = PhotoExportEstimate(photos = 1, points = 1, photoBytes = 1L shl 30)

        below.exceedsWarning shouldBe false
        above.exceedsWarning shouldBe true
        (above.zipBytes > above.photoBytes) shouldBe true
    }

    @Test
    fun `S056 RF-4 el ZIP lleva cada foto igual que esta guardada`() {
        val a = content(1)
        val b = content(2, 80_000)
        val points = listOf(PhotoExportPoint("p1", "P1", listOf(photo("f1", "p1", a), photo("f2", "p1", b))))

        val outcome = runBlocking { exporter().writeFile(points, target()) }

        written(outcome).photosWritten shouldBe 2
        ZipFile(target()).use {
            it.read("P1_1.jpg").contentEquals(a) shouldBe true
            it.read("P1_2.jpg").contentEquals(b) shouldBe true
        }
    }

    @Test
    fun `S056 RF-5 los nombres repetidos no se pisan`() {
        val points = listOf(
            PhotoExportPoint("p1", "Árbol ñ/9", listOf(photo("f1", "p1", content(1)))),
            PhotoExportPoint("p0", "vacio", emptyList()),
            PhotoExportPoint("p2", "Arbol n 9", listOf(photo("f2", "p2", content(2)))),
        )

        val outcome = runBlocking { exporter().writeFile(points, target()) }

        written(outcome) shouldBe PhotoZipResult(2, 2, 0, target().length())
        val names = ZipFile(target()).use { zip -> zip.entries().toList().map { it.name } }
        names shouldBe listOf("Arbol_n_9_1.jpg", "Arbol_n_9_2_1.jpg", "fotos.csv")
    }

    @Test
    fun `S056 RF-6 el indice lleva su fila por foto`() {
        val points = listOf(PhotoExportPoint("p1", "P1", listOf(photo("f1", "p1", content(1, 1_234)))))

        runBlocking { exporter().writeFile(points, target()) }

        val index = ZipFile(target()).use { String(it.read("fotos.csv"), Charsets.UTF_8) }
        index shouldBe "Photo,Point,Number,Date,Source,Size\r\nP1_1.jpg,P1,1,2026-11-12T08:15:30Z,camera,1234\r\n"
    }

    @Test
    fun `S056 RF-9 la foto cuyo fichero falta no entra y se cuenta`() {
        val points = listOf(
            PhotoExportPoint("p1", "P1", listOf(photo("f1", "p1", content(1)), photo("f2", "p1", null), photo("f3", "p1", content(3)))),
        )

        val outcome = runBlocking { exporter().writeFile(points, target()) }

        written(outcome).photosWritten shouldBe 2
        written(outcome).photosSkipped shouldBe 1
        ZipFile(target()).use { zip -> zip.entries().toList().map { it.name } shouldBe listOf("P1_1.jpg", "P1_3.jpg", "fotos.csv") }
    }

    @Test
    fun `S056 RF-9 si no se puede leer ninguna no hay ZIP`() {
        val points = listOf(PhotoExportPoint("p1", "P1", listOf(photo("f1", "p1", null), photo("f2", "p1", null))))

        val outcome = runBlocking { exporter().writeFile(points, target()) }

        outcome shouldBe PhotoExportOutcome.NothingReadable(2)
        target().exists() shouldBe false
    }

    @Test
    fun `S056 RF-11 sin sitio no se escribe nada`() {
        val points = listOf(PhotoExportPoint("p1", "P1", listOf(photo("f1", "p1", content(1)))))

        val outcome = runBlocking { exporter(freeSpace = 0).writeFile(points, target()) }

        outcome shouldBe PhotoExportOutcome.NoSpace
        target().exists() shouldBe false
    }

    @Test
    fun `S056 RF-11 se pide el tamano previsto mas un 5 por ciento`() {
        val points = listOf(PhotoExportPoint("p1", "P1", listOf(photo("f1", "p1", content(1)))))
        val zipBytes = exporter().estimate(points).zipBytes
        val needed = zipBytes + zipBytes / 20

        runBlocking { exporter(freeSpace = needed - 1).writeFile(points, target()) } shouldBe PhotoExportOutcome.NoSpace
        written(runBlocking { exporter(freeSpace = needed).writeFile(points, target()) }).photosWritten shouldBe 1
    }

    @Test
    fun `S056 RF-11 un fallo a mitad de la copia no deja el fichero`() {
        val points = listOf(PhotoExportPoint("p1", "P1", listOf(photo("f1", "p1", content(1)), photo("f2", "p1", content(2)))))
        val dying = exporter(open = {
            object : InputStream() {
                private var sent = 0

                override fun read(): Int = throw IOException("se cortó")

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sent >= 10) throw IOException("se cortó")
                    sent += 10
                    return 10
                }
            }
        })

        val outcome = runBlocking { dying.writeFile(points, target()) }

        outcome shouldBe PhotoExportOutcome.Failed
        target().exists() shouldBe false
    }

    @Test
    fun `S056 RF-11 sin poder crear el fichero no queda nada`() {
        val points = listOf(PhotoExportPoint("p1", "P1", listOf(photo("f1", "p1", content(1)))))
        val inNowhere = File(folder.root, "no-existe/fotos.zip")

        runBlocking { exporter().writeFile(points, inNowhere) } shouldBe PhotoExportOutcome.Failed
        inNowhere.exists() shouldBe false
    }

    @Test
    fun `S056 RF-14 exportar no cambia ninguna foto`() {
        val a = content(1)
        val points = listOf(PhotoExportPoint("p1", "P1", listOf(photo("f1", "p1", a))))
        val before = files.getValue("f1").lastModified()

        runBlocking { exporter().writeFile(points, target()) }

        files.getValue("f1").readBytes().contentEquals(a) shouldBe true
        files.getValue("f1").lastModified() shouldBe before
        folder.root.listFiles().orEmpty().map { it.name }.sorted() shouldBe listOf("f1.jpg", "fotos.zip")
    }
}
