package es.topophone.core.formats.kml.export

import es.topophone.core.formats.kml.KmlFileKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.random.Random
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KmzWriterTest {

    private fun bytes(size: Int, seed: Int = 1): ByteArray = Random(seed).nextBytes(size)

    private fun photo(name: String, content: ByteArray) = KmzPhoto(name) { ByteArrayInputStream(content) }

    private fun missing(name: String) = KmzPhoto(name) { throw FileNotFoundException("falta $name") }

    private fun mark(name: String, photos: List<String>, description: String = "Poste") =
        KmlMark(name, description, null, emptyList(), KmlGeometry.Point(KmlVertex(-3.7038, 40.4168)), false, photos)

    private fun document(vararg marks: KmlMark) = KmlDocument("Obra", emptyList(), listOf(KmlFolder("Obra", marks.toList())))

    private fun kmz(document: KmlDocument, photos: List<KmzPhoto>, folder: File): Pair<ZipFile, KmzResult> {
        val file = File(folder, "obra.kmz")
        val result = file.outputStream().buffered().use { KmzWriter.write(document, photos, it) }
        return ZipFile(file) to result
    }

    private fun ZipFile.names(): List<String> = entries().toList().map { it.name }

    private fun ZipFile.read(name: String): ByteArray = getInputStream(getEntry(name)).use { it.readBytes() }

    private fun ZipFile.text(name: String): String = String(read(name), Charsets.UTF_8)

    @Test
    fun `S031 RF-28 doc kml es la primera entrada y es el KML de la exportacion con sus imagenes`(@TempDir folder: File) {
        val document = KmlExportFixtures.document("con-fotos")
        val names = document.folders.flatMap { it.marks }.flatMap { it.photos }.distinct()

        val (zip, result) = kmz(document, names.map { photo(it, bytes(10)) }, folder)

        zip.use {
            it.names() shouldBe listOf("doc.kml") + names.map { name -> "files/$name" }
            (it.read("doc.kml") contentEquals KmlExportFixtures.bytes("con-fotos.kml")) shouldBe true
        }
        result shouldBe KmzResult(names.size, 0)
    }

    @Test
    fun `S031 RF-28 las fotos se escriben byte a byte en files`(@TempDir folder: File) {
        val sizes = listOf(1, 70_001, 1_000_000)
        val contents = sizes.mapIndexed { i, size -> bytes(size, seed = i + 10) }
        val document = document(mark("A", listOf("A_1.jpg", "A_2.jpg")), mark("B", listOf("B_1.jpg")))
        val names = listOf("A_1.jpg", "A_2.jpg", "B_1.jpg")
        val photos = names.mapIndexed { i, name -> photo(name, contents[i]) }

        val (zip, result) = kmz(document, photos, folder)

        zip.use {
            names.forEachIndexed { i, name -> (it.read("files/$name") contentEquals contents[i]) shouldBe true }
        }
        result shouldBe KmzResult(3, 0)
    }

    @Test
    fun `S031 RF-28 las fotos no se recomprimen y doc kml si`(@TempDir folder: File) {
        val marks = (0 until 300).map { mark("Punto $it", if (it == 0) listOf("A_1.jpg") else emptyList(), "Descripción del punto $it") }

        val (zip, _) = kmz(document(*marks.toTypedArray()), listOf(photo("A_1.jpg", ByteArray(1 shl 20))), folder)

        zip.use {
            val doc: ZipEntry = it.getEntry("doc.kml")
            val image: ZipEntry = it.getEntry("files/A_1.jpg")
            (doc.compressedSize < doc.size / 2) shouldBe true
            (image.compressedSize >= image.size) shouldBe true
            image.size shouldBe (1L shl 20)
        }
    }

    @Test
    fun `S031 RF-30 una foto que no se puede leer no entra y su marca no lleva la imagen`(@TempDir folder: File) {
        val document = document(mark("A", listOf("A_1.jpg", "A_2.jpg")), mark("B", listOf("B_1.jpg")))
        val photos = listOf(photo("A_1.jpg", bytes(100)), missing("A_2.jpg"), photo("B_1.jpg", bytes(50, 2)))

        val (zip, result) = kmz(document, photos, folder)

        zip.use {
            it.names() shouldBe listOf("doc.kml", "files/A_1.jpg", "files/B_1.jpg")
            val text = it.text("doc.kml")
            text.contains("files/A_1.jpg") shouldBe true
            text.contains("files/B_1.jpg") shouldBe true
            text.contains("A_2.jpg") shouldBe false
        }
        result shouldBe KmzResult(2, 1)
    }

    @Test
    fun `S031 RF-30 una marca cuyas fotos no se pueden leer queda con su descripcion sin CDATA`(@TempDir folder: File) {
        val (zip, result) = kmz(document(mark("C", listOf("C_1.jpg"), "Tapa")), listOf(missing("C_1.jpg")), folder)

        zip.use {
            it.names() shouldBe listOf("doc.kml")
            val text = it.text("doc.kml")
            text.contains("<description>Tapa</description>") shouldBe true
            text.contains("CDATA") shouldBe false
        }
        result shouldBe KmzResult(0, 1)
    }

    @Test
    fun `S031 RF-30 una foto que se pierde antes de escribirla se omite sin dejar nada a medias`(@TempDir folder: File) {
        var calls = 0
        val flaky = KmzPhoto("A_1.jpg") { if (calls++ == 0) ByteArrayInputStream(bytes(10)) else throw FileNotFoundException("falta") }
        val document = document(mark("A", listOf("A_1.jpg")), mark("B", listOf("B_1.jpg")))

        val (zip, result) = kmz(document, listOf(flaky, photo("B_1.jpg", bytes(20))), folder)

        zip.use { it.names() shouldBe listOf("doc.kml", "files/B_1.jpg") }
        result shouldBe KmzResult(1, 1)
    }

    @Test
    fun `S031 RF-30 las fotos que cita una marca y no se han dado no se escriben en la descripcion`(@TempDir folder: File) {
        val (zip, result) = kmz(document(mark("A", listOf("A_1.jpg", "A_9.jpg"))), listOf(photo("A_1.jpg", bytes(10))), folder)

        zip.use {
            val text = it.text("doc.kml")
            text.contains("files/A_1.jpg") shouldBe true
            text.contains("A_9.jpg") shouldBe false
        }
        result shouldBe KmzResult(1, 0)
    }

    @Test
    fun `S031 RF-28 cada fichero de foto que se abre se cierra`(@TempDir folder: File) {
        var opened = 0
        var closed = 0
        fun counted(name: String) = KmzPhoto(name) {
            opened++
            object : ByteArrayInputStream(bytes(10)) {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        }

        val (zip, _) = kmz(document(mark("A", listOf("A_1.jpg", "A_2.jpg"))), listOf(counted("A_1.jpg"), counted("A_2.jpg")), folder)
        zip.close()

        opened shouldBe 4
        closed shouldBe 4
    }

    @Test
    fun `S031 RF-28 sin fotos el KMZ solo lleva doc kml`(@TempDir folder: File) {
        val (zip, result) = kmz(document(mark("A", emptyList())), emptyList(), folder)

        zip.use { it.names() shouldBe listOf("doc.kml") }
        result shouldBe KmzResult(0, 0)
    }

    @Test
    fun `S031 RF-28 dos fotos con el mismo nombre se escriben una vez`(@TempDir folder: File) {
        val first = bytes(30, seed = 1)

        val photos = listOf(photo("A_1.jpg", first), photo("A_1.jpg", bytes(30, seed = 2)))

        val (zip, result) = kmz(document(mark("A", listOf("A_1.jpg"))), photos, folder)

        zip.use {
            it.names() shouldBe listOf("doc.kml", "files/A_1.jpg")
            (it.read("files/A_1.jpg") contentEquals first) shouldBe true
        }
        result shouldBe KmzResult(1, 0)
    }

    @Test
    fun `S031 RF-28 un fallo de lectura a mitad de una foto no se esconde`(@TempDir folder: File) {
        val broken = KmzPhoto("A_1.jpg") {
            object : InputStream() {
                private var served = 0

                override fun read(): Int = if (served++ < 3) 1 else throw IOException("se cortó")
            }
        }

        shouldThrow<IOException> { kmz(document(mark("A", listOf("A_1.jpg"))), listOf(broken), folder) }
    }

    @Test
    fun `S031 RF-28 el KMZ escrito se reconoce como KMZ y su KML principal es doc kml`(@TempDir folder: File) {
        val (zip, _) = kmz(document(mark("A", listOf("A_1.jpg"))), listOf(photo("A_1.jpg", bytes(10))), folder)

        zip.use {
            val file = File(folder, "obra.kmz")
            val head = file.readBytes().copyOf(minOf(file.length().toInt(), KmlFileKind.HEAD_BYTES))
            KmlFileKind.of(head, file.length()) shouldBe KmlFileKind.Kind.KMZ
            KmlFileKind.mainEntry(it.names()) shouldBe "doc.kml"
        }
    }
}
