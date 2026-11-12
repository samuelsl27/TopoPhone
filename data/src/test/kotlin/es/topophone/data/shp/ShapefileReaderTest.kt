package es.topophone.data.shp

import es.topophone.core.formats.shp.ShpFileKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Elegir un ZIP o ficheros sueltos y dejarlos en una carpeta temporal, con sus límites y su limpieza (S030/RF-1 a RF-7, RF-29, RF-30). */
class ShapefileReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val cache: File by lazy { folder.newFolder("cache") }
    private var ids = 0

    private fun reader(
        maxPicked: Long = ShpFileKind.MAX_PICKED_BYTES,
        maxUncompressed: Long = ShpFileKind.MAX_UNCOMPRESSED_BYTES,
        freeSpace: () -> Long = { Long.MAX_VALUE },
    ) = ShapefileReader(cache, freeSpace, { "t${ids++}" }, maxPicked, maxUncompressed)

    private fun open(vararg paths: String, reader: ShapefileReader = reader()): ShpExtractResult =
        runBlocking { reader.extract(paths.map { ShpFixtures.input(it) }) }

    private fun extracted(result: ShpExtractResult) = result.shouldBeInstanceOf<ShpExtractResult.Extracted>().extracted

    private fun failed(result: ShpExtractResult) = result.shouldBeInstanceOf<ShpExtractResult.Failed>().reason

    private fun leftovers() = cache.listFiles().orEmpty().toList()

    @Test
    fun `S030 RF-2 un ZIP deja en la carpeta temporal las capas con los ficheros de su nombre y nada mas`() {
        val extracted = extracted(open("zip/completo.zip"))

        extracted.layers.map { it.name } shouldBe listOf("ejes", "parcelas", "vertices")
        val vertices = extracted.layers.first { it.name == "vertices" }
        vertices.shp.readBytes().toList() shouldBe ShpFixtures.bytes("puntos-25830/vertices.shp").toList()
        listOf(vertices.shx, vertices.dbf, vertices.prj, vertices.cpg).map { it?.name } shouldBe
            listOf("vertices.shx", "vertices.dbf", "vertices.prj", "vertices.cpg")
        // los metadatos, los índices y los ficheros de macOS no se copian
        extracted.directory.walkTopDown().filter { it.isFile }.map { it.name }.toSet() shouldBe
            listOf("ejes", "parcelas", "vertices").flatMap { stem -> listOf("shp", "shx", "dbf", "prj", "cpg").map { "$stem.$it" } }.toSet()
        extracted.drawingName shouldBe "completo.zip"
        extracted.close()
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S030 RF-1 los ficheros sueltos se copian y el dibujo toma el nombre del primer shp por orden alfabetico`() {
        val extracted = extracted(
            open(
                "poligonos-25830/parcelas.shp",
                "poligonos-25830/parcelas.dbf",
                "puntos-25830/vertices.shp",
                "puntos-25830/vertices.prj",
            ),
        )

        extracted.layers.map { it.name } shouldBe listOf("parcelas", "vertices")
        extracted.layers.first().dbf?.name shouldBe "parcelas.dbf"
        extracted.layers.last().dbf shouldBe null
        extracted.layers.last().prj?.name shouldBe "vertices.prj"
        extracted.drawingName shouldBe "parcelas.shp"
        extracted.close()
    }

    @Test
    fun `S030 RF-2 un shp guardado como dat y con las mayusculas cambiadas se asocia por contenido y por nombre`() {
        val other = extracted(open("otra-extension/vertices.dat", "otra-extension/vertices.dbf", "otra-extension/vertices.prj"))
        other.layers.single().name shouldBe "vertices"
        other.layers.single().shp.name shouldBe "vertices.dat"
        other.close()

        val upper =
            extracted(open("mayusculas/PARCELAS.SHP", "mayusculas/PARCELAS.Dbf", "mayusculas/PARCELAS.PRJ", "mayusculas/PARCELAS.Cpg"))
        upper.layers.single().let { listOf(it.name, it.dbf?.name, it.prj?.name, it.cpg?.name) } shouldBe
            listOf("PARCELAS", "PARCELAS.Dbf", "PARCELAS.PRJ", "PARCELAS.Cpg")
        upper.close()
    }

    @Test
    fun `S030 RF-5 los ficheros sin su shp salen como huerfanos`() {
        val zip = extracted(open("zip/huerfanos.zip"))
        zip.orphans shouldBe listOf("otro.shx", "solo.dbf", "solo.prj")
        zip.close()

        val loose = extracted(open("puntos-25830/vertices.shp", "puntos-25830/vertices.dbf", "incompletos/dbf_corto.dbf"))
        loose.orphans shouldBe listOf("dbf_corto.dbf")
        loose.close()
    }

    @Test
    fun `S030 RF-7 dos capas con el mismo nombre en carpetas distintas llevan la carpeta y cada una su fichero`() {
        val extracted = extracted(open("zip/carpetas-repetidas.zip"))

        extracted.layers.map { it.name } shouldBe listOf("norte/parcelas", "sur/parcelas")
        extracted.layers[0].shp.parentFile.name shouldBe "norte"
        extracted.layers[1].shp.parentFile.name shouldBe "sur"
        extracted.layers[0].shp.readBytes().contentEquals(extracted.layers[1].shp.readBytes()) shouldBe false
        extracted.close()
    }

    @Test
    fun `S030 RF-3 lo que no se puede abrir da su motivo y no deja carpetas`() {
        failed(open("zip/vacio.zip")) shouldBe ShpOpenFailure.EMPTY
        failed(open("zip/texto.zip")) shouldBe ShpOpenFailure.NOT_SHAPEFILE
        failed(open("zip/sin-shp.zip")) shouldBe ShpOpenFailure.NO_SHP
        failed(open("zip/danado.zip")) shouldBe ShpOpenFailure.DAMAGED
        failed(open("puntos-25830/vertices.dbf", "puntos-25830/vertices.prj")) shouldBe ShpOpenFailure.NO_SHP
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S030 RF-4 un ZIP con otros ficheros o mas de un ZIP se rechaza sin leer nada mas`() {
        failed(open("zip/completo.zip", "puntos-25830/vertices.shp")) shouldBe ShpOpenFailure.MIXED_SELECTION
        failed(open("zip/completo.zip", "zip/huerfanos.zip")) shouldBe ShpOpenFailure.MIXED_SELECTION
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S030 RF-6 un nombre con una ruta que sale de la carpeta no se escribe fuera de ella`() {
        val extracted = extracted(open("zip/ruta-peligrosa.zip"))

        extracted.layers.map { it.name } shouldBe listOf("ok")
        extracted.dropped shouldBe listOf("../escapa.shp", "/ruta/absoluta.dbf")
        folder.root.walkTopDown().none { it.name == "escapa.shp" || it.name == "absoluta.dbf" } shouldBe true
        extracted.close()
    }

    @Test
    fun `S030 RF-6 un ZIP que descomprime mas del limite se rechaza contando los bytes leidos y borra lo escrito`() {
        failed(open("zip/bomba.zip", reader = reader(maxUncompressed = 10L * 1024 * 1024))) shouldBe ShpOpenFailure.TOO_LARGE
        // y con el límite de 500 MB, el ZIP bomba de 20 MB es un ZIP sin ningún shp
        failed(open("zip/bomba.zip")) shouldBe ShpOpenFailure.NO_SHP
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S030 RF-6 mas de 100 MB entre los ficheros elegidos se rechaza aunque el tamano declarado diga otra cosa`() {
        val big = ShpFixtures.file("poligonos-25830/parcelas.shp").length()

        failed(open("poligonos-25830/parcelas.shp", reader = reader(maxPicked = big - 1))) shouldBe ShpOpenFailure.TOO_LARGE
        // el selector dice 150 bytes y el fichero tiene más: se cuentan los leídos
        val lying = ShpFixtures.input("poligonos-25830/parcelas.shp", declaredSize = 150)
        failed(runBlocking { reader(maxPicked = big - 1).extract(listOf(lying)) }) shouldBe ShpOpenFailure.TOO_LARGE
        // un ZIP que pesa más que el límite tampoco
        failed(open("zip/completo.zip", reader = reader(maxPicked = 1000))) shouldBe ShpOpenFailure.TOO_LARGE
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S030 RF-29 sin espacio libre no se copia nada`() {
        failed(open("zip/completo.zip", reader = reader(freeSpace = { 1_000L }))) shouldBe ShpOpenFailure.NO_SPACE
        failed(open("puntos-25830/vertices.shp", reader = reader(freeSpace = { 1_000L }))) shouldBe ShpOpenFailure.NO_SPACE
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S030 RF-30 cancelar a mitad de la copia borra la carpeta temporal`() {
        val bytes = ShpFixtures.bytes("puntos-25830/vertices.shp")
        val outcome = runBlocking {
            lateinit var job: Job
            val input = ShpInput("vertices.shp", bytes.size.toLong()) {
                object : ByteArrayInputStream(bytes) {
                    private var calls = 0

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (++calls == 2) job.cancel() // la segunda lectura de un flujo es la copia: se cancela en mitad
                        return super.read(b, off, len)
                    }
                }
            }
            job = async(Dispatchers.IO) { reader().extract(listOf(input)) }
            runCatching { job.await() }.exceptionOrNull()
        }

        outcome.shouldBeInstanceOf<CancellationException>()
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S030 RF-30 los temporales de una importacion interrumpida se borran al arrancar`() {
        File(cache, "shp-viejo").mkdirs()
        File(cache, "shp-viejo/a.shp").writeText("x")
        File(cache, "gpkg-otro.tmp").writeText("no es de Shapefile")

        reader().cleanUpStale() shouldBe 1

        cache.list()!!.toList() shouldBe listOf("gpkg-otro.tmp")
    }
}
