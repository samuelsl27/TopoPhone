package es.topophone.core.formats.photo

import es.topophone.core.model.point.PhotoOrigin
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PhotoZipWriterTest {

    private fun bytes(size: Int, seed: Int = 1): ByteArray = Random(seed).nextBytes(size)

    private fun row(name: String, point: String = "P1", number: Int = 1, size: Long = 0, origin: PhotoOrigin = PhotoOrigin.CAMERA) =
        PhotoIndexRow(name, point, number, 1_794_471_330_000, origin, size)

    private fun entry(name: String, content: ByteArray, point: String = "P1", number: Int = 1, key: String = point) =
        PhotoZipEntry(row(name, point, number, content.size.toLong()), key) { ByteArrayInputStream(content) }

    private fun missing(name: String, point: String = "P1", key: String = point) =
        PhotoZipEntry(row(name, point), key) { throw FileNotFoundException("falta $name") }

    private fun write(entries: List<PhotoZipEntry>, folder: File): Triple<ZipFile, PhotoZipResult, File> {
        val file = File(folder, "fotos.zip")
        val result = file.outputStream().buffered().use { PhotoZipWriter.write(entries, it) }
        return Triple(ZipFile(file), result, file)
    }

    private fun ZipFile.names(): List<String> = entries().toList().map { it.name }

    private fun ZipFile.read(name: String): ByteArray = getInputStream(getEntry(name)).use { it.readBytes() }

    /** Las entradas del ejemplo de `tools` con los bytes de cada foto del tamaño que dice su entrada. */
    private fun exampleEntries(): List<PhotoZipEntry> {
        val points = PhotoZipFixtures.points()
        val names = PhotoArchiveNames.of(points.map { PhotoNameRef(it.name, it.photos.size) })
        return points.mapIndexed { index, point ->
            point.photos.mapIndexed { i, photo ->
                val content = bytes(photo.size, index * 100 + i)
                val origin = if (photo.origin == "camera") PhotoOrigin.CAMERA else PhotoOrigin.GALLERY
                val row = PhotoIndexRow(names[index][i], point.name, i + 1, photo.takenAtMs, origin, photo.size.toLong())
                PhotoZipEntry(row, "punto$index") { ByteArrayInputStream(content) }
            }
        }.flatten()
    }

    @Test
    fun `S056 RF-4 cada foto sale del ZIP igual que entro byte a byte y en su orden`(@TempDir folder: File) {
        val a = bytes(70_000, 1)
        val b = bytes(3, 2)
        val c = bytes(200_000, 3)

        val (zip, result, _) = write(listOf(entry("P1_1.jpg", a), entry("P1_2.jpg", b, number = 2), entry("P2_1.jpg", c, "P2")), folder)

        zip.use {
            it.names() shouldBe listOf("P1_1.jpg", "P1_2.jpg", "P2_1.jpg", "fotos.csv")
            it.read("P1_1.jpg").contentEquals(a) shouldBe true
            it.read("P1_2.jpg").contentEquals(b) shouldBe true
            it.read("P2_1.jpg").contentEquals(c) shouldBe true
        }
        result.photosWritten shouldBe 3
    }

    @Test
    fun `S056 RF-6 el indice va el ultimo y es el de tools byte a byte con las fotos del ejemplo`(@TempDir folder: File) {
        val (zip, result, _) = write(exampleEntries(), folder)

        zip.use {
            it.read("fotos.csv").contentEquals(PhotoZipFixtures.index()) shouldBe true
            it.names() shouldBe PhotoZipFixtures.photoNames() + "fotos.csv"
        }
        result.photosWritten shouldBe PhotoZipFixtures.photoNames().size
    }

    @Test
    fun `S056 RF-6 el tamano del indice es el de los bytes que se escribieron y no el del registro`(@TempDir folder: File) {
        val real = bytes(1_500)
        val registered = PhotoZipEntry(row("P1_1.jpg", size = 999), "P1") { ByteArrayInputStream(real) }

        val (zip, _, _) = write(listOf(registered), folder)

        zip.use { String(it.read("fotos.csv"), Charsets.UTF_8).split("\r\n")[1].split(",").last() shouldBe "1500" }
    }

    @Test
    fun `S056 RF-9 una foto cuyo fichero falta no esta en el ZIP ni en el indice y se cuenta`(@TempDir folder: File) {
        val ok = bytes(100)

        val (zip, result, _) = write(listOf(entry("P1_1.jpg", ok), missing("P1_2.jpg"), entry("P2_1.jpg", ok, "P2")), folder)

        zip.use {
            it.names() shouldBe listOf("P1_1.jpg", "P2_1.jpg", "fotos.csv")
            String(it.read("fotos.csv"), Charsets.UTF_8).contains("P1_2.jpg") shouldBe false
        }
        result.photosWritten shouldBe 2
        result.photosSkipped shouldBe 1
    }

    @Test
    fun `S056 RF-9 una foto que no se abre, vacia o que falla en la primera lectura se omite igual`(@TempDir folder: File) {
        val failsAtFirstRead = PhotoZipEntry(row("P1_3.jpg"), "P1") {
            object : InputStream() {
                override fun read(): Int = throw IOException("sector dañado")

                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("sector dañado")
            }
        }
        val entries = listOf(
            PhotoZipEntry(row("P1_1.jpg"), "P1") { null },
            PhotoZipEntry(row("P1_2.jpg"), "P1") { ByteArrayInputStream(ByteArray(0)) },
            failsAtFirstRead,
            entry("P1_4.jpg", bytes(10), number = 4),
        )

        val (zip, result, _) = write(entries, folder)

        zip.use { it.names() shouldBe listOf("P1_4.jpg", "fotos.csv") }
        result.photosWritten shouldBe 1
        result.photosSkipped shouldBe 3
    }

    @Test
    fun `S056 RF-9 un punto cuyas fotos se omiten todas no cuenta como punto escrito`(@TempDir folder: File) {
        val entries = listOf(
            entry("P1_1.jpg", bytes(10)),
            entry("P1_2.jpg", bytes(10), number = 2),
            missing("P2_1.jpg", "P2"),
            entry("P3_1.jpg", bytes(10), "P3"),
        )

        val (zip, result, _) = write(entries, folder)
        zip.close()

        result.pointsWritten shouldBe 2
        result.photosWritten shouldBe 3
    }

    @Test
    fun `S056 RF-9 dos puntos con el mismo nombre son dos puntos`(@TempDir folder: File) {
        val entries = listOf(
            entry("A_1.jpg", bytes(10), "A", key = "id-1"),
            entry("A_2_1.jpg", bytes(10), "A", key = "id-2"),
        )

        val (zip, result, _) = write(entries, folder)
        zip.close()

        result.pointsWritten shouldBe 2
    }

    @Test
    fun `S056 RF-4 un fallo a mitad de la copia de una foto es un fallo de escritura y no se oculta`(@TempDir folder: File) {
        val dying = PhotoZipEntry(row("P1_1.jpg"), "P1") {
            object : InputStream() {
                private var sent = 0

                override fun read(): Int = throw IOException("se cortó")

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sent >= 100) throw IOException("se cortó")
                    val n = minOf(len, 100 - sent)
                    sent += n
                    return n
                }
            }
        }

        shouldThrow<IOException> { write(listOf(dying), folder) }
    }

    @Test
    fun `S056 RF-11 un fallo al escribir en el destino sale como IOException`() {
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("sin espacio")

            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("sin espacio")
        }

        shouldThrow<IOException> { PhotoZipWriter.write(listOf(entry("P1_1.jpg", bytes(1_000))), broken) }
    }

    @Test
    fun `S056 RF-4 las fotos van sin recomprimir y el ZIP pasa el CRC`(@TempDir folder: File) {
        val content = bytes(50_000)

        val (zip, _, _) = write(listOf(entry("P1_1.jpg", content)), folder)

        zip.use {
            val photo = it.getEntry("P1_1.jpg")
            photo.method shouldBe ZipEntry.DEFLATED
            (photo.compressedSize >= photo.size) shouldBe true // nivel 0: ni gasta CPU ni gana nada con un JPEG
            // lee todo: el CRC se comprueba al acabar cada entrada
            it.entries().toList().forEach { e -> it.getInputStream(e).use { s -> s.readBytes() } }
        }
    }

    @Test
    fun `S056 RF-7 el indice del ZIP es UTF-8 sin BOM`(@TempDir folder: File) {
        val (zip, _, _) = write(listOf(entry("Arbol_n_9_1.jpg", bytes(10), "Árbol ñ/9")), folder)

        val raw = zip.use { it.read("fotos.csv") }

        raw.take(5) shouldBe "Photo".toByteArray().toList()
        String(raw, Charsets.UTF_8).contains("Árbol ñ/9") shouldBe true
    }

    @Test
    fun `S056 RF-6 sin ninguna foto el ZIP lleva solo el indice con la cabecera`(@TempDir folder: File) {
        val (zip, result, _) = write(emptyList(), folder)

        zip.use {
            it.names() shouldBe listOf("fotos.csv")
            String(it.read("fotos.csv"), Charsets.UTF_8) shouldBe "Photo,Point,Number,Date,Source,Size\r\n"
        }
        result shouldBe PhotoZipResult(0, 0, 0, result.zipBytes)
    }

    @Test
    fun `S056 RF-13 el tamano del ZIP son los bytes que se escribieron en el destino`(@TempDir folder: File) {
        val (zip, result, file) = write(listOf(entry("P1_1.jpg", bytes(10_000))), folder)
        zip.close()

        result.zipBytes shouldBe file.length()
    }

    @Test
    fun `S056 RF-4 el escritor no cierra el destino`() {
        var closed = false
        val out = object : ByteArrayOutputStream() {
            override fun close() {
                closed = true
                super.close()
            }
        }

        PhotoZipWriter.write(listOf(entry("P1_1.jpg", bytes(10))), out)

        closed shouldBe false
        (out.size() > 10) shouldBe true
    }

    @Test
    fun `S056 RF-14 las fotos solo se leen y se cierran`() {
        var closed = 0
        val stream = object : ByteArrayInputStream(bytes(100)) {
            override fun close() {
                closed++
                super.close()
            }
        }

        PhotoZipWriter.write(listOf(PhotoZipEntry(row("P1_1.jpg", size = 100), "P1") { stream }), ByteArrayOutputStream())

        closed shouldBe 1
    }
}
