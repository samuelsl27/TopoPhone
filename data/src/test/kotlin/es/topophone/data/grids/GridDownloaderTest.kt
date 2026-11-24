package es.topophone.data.grids

import es.topophone.core.geodesy.catalog.CrsCatalog
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** La descarga de rejillas: completa, cortada, con el SHA-256 alterado, con error del servidor y cancelada (S034/RF-19, RF-21, RF-22). */
class GridDownloaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    private val name = "fr_ign_ggpf02-Bora.tif"
    private val entry = CrsCatalog.bundled.grids.getValue(name)
    private val bytes = File(root, "fixtures/geodesia/rejillas/$name").readBytes()
    private val directory by lazy { folder.newFolder("grids") }
    private val store by lazy { GridStore(directory) { emptyList() } }

    /** Una respuesta de prueba: el código, los bytes que da el cuerpo y, si se pide, un error de lectura tras [failAfter] bytes. */
    private class FakeHttp(
        private val code: Int = 200,
        private val body: ByteArray = ByteArray(0),
        private val failAfter: Int? = null,
        private val onRead: () -> Unit = {},
    ) : GridHttp {
        val urls = mutableListOf<String>()

        override suspend fun <T> get(url: String, read: (GridHttpResponse) -> T): T {
            urls += url
            val stream: InputStream = object : InputStream() {
                private var position = 0

                override fun read(): Int = if (position < body.size) body[position++].toInt() and 0xFF else -1

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    onRead()
                    if (failAfter != null && position >= failAfter) throw IOException("conexión perdida")
                    val limit = if (failAfter != null) minOf(body.size, failAfter) else body.size
                    if (position >= limit) return -1
                    val count = minOf(length, limit - position, 1024)
                    System.arraycopy(body, position, buffer, offset, count)
                    position += count
                    return count
                }
            }
            return read(GridHttpResponse(code, body.size.toLong(), stream))
        }
    }

    private fun GridDownloader.run(onProgress: (Long) -> Unit = {}) = runBlocking { download(entry, onProgress) }

    private fun leftovers() = directory.listFiles()!!.map { it.name }

    @Test
    fun `S034 RF-19 una descarga completa instala la rejilla con su SHA-256 y sube la generacion`() {
        val http = FakeHttp(body = bytes)
        val before = store.generation
        val progress = mutableListOf<Long>()

        val result = GridDownloader(store, http).run { progress += it }

        result shouldBe DownloadResult.Done
        File(directory, name).readBytes().contentEquals(bytes) shouldBe true
        leftovers() shouldBe listOf(name)
        store.state(name) shouldBe GridState.Installed(bytes.size.toLong())
        http.urls shouldBe listOf("https://cdn.proj.org/$name")
        (store.generation > before) shouldBe true
        progress.last() shouldBe bytes.size.toLong()
        (progress == progress.sorted()) shouldBe true
    }

    @Test
    fun `S034 RF-22 una descarga cortada no deja ficheros y se puede reintentar`() {
        val result = GridDownloader(store, FakeHttp(body = bytes, failAfter = 300)).run()

        (result is DownloadResult.Interrupted) shouldBe true
        leftovers().shouldBeEmpty()
        store.state(name) shouldBe GridState.Missing

        GridDownloader(store, FakeHttp(body = bytes)).run() shouldBe DownloadResult.Done
    }

    @Test
    fun `S034 RF-22 una descarga que llega mas corta de lo anunciado no se instala`() {
        val result = GridDownloader(store, FakeHttp(body = bytes.copyOf(bytes.size - 10))).run()

        (result is DownloadResult.Interrupted) shouldBe true
        leftovers().shouldBeEmpty()
    }

    @Test
    fun `S034 RF-21 un fichero con el SHA-256 alterado se descarta y se avisa de que esta danado`() {
        val tampered = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }

        val result = GridDownloader(store, FakeHttp(body = tampered)).run()

        result shouldBe DownloadResult.Damaged
        leftovers().shouldBeEmpty()
        store.state(name) shouldBe GridState.Missing
    }

    @Test
    fun `S034 RF-19 un error del servidor no instala nada`() {
        GridDownloader(store, FakeHttp(code = 404)).run() shouldBe DownloadResult.ServerError(404)
        leftovers().shouldBeEmpty()
    }

    @Test
    fun `S034 RF-22 cancelar a mitad borra el fichero a medias`() = runBlocking<Unit> {
        lateinit var download: Deferred<DownloadResult>
        val http = FakeHttp(body = bytes, onRead = { download.cancel() })
        download = async(Dispatchers.Default, start = CoroutineStart.LAZY) { GridDownloader(store, http).download(entry) }
        download.start() // con `download` ya asignado: onRead puede cancelarlo desde la primera lectura

        download.join()

        download.isCancelled shouldBe true
        leftovers().shouldBeEmpty()
        store.state(name) shouldBe GridState.Missing
    }

    @Test
    fun `S034 RF-19 solo se descarga por HTTPS`() {
        val failure = runCatching { GridDownloader(store, FakeHttp(), baseUrl = "http://cdn.proj.org/") }.exceptionOrNull()

        (failure is IllegalArgumentException) shouldBe true
    }

    @Test
    fun `S034 RF-22 un nombre de rejilla que sale de la carpeta no se descarga`() {
        val evil = entry.copy(name = "../fuera.tif")

        val result = runBlocking { GridDownloader(store, FakeHttp(body = bytes)).download(evil) }

        (result is DownloadResult.Interrupted) shouldBe true
        File(directory.parentFile, "fuera.tif").exists() shouldBe false
    }
}
