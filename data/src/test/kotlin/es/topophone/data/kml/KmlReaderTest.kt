package es.topophone.data.kml

import es.topophone.core.formats.gpkg.GpkgGeometry
import es.topophone.core.formats.gpkg.GpkgGeometryReader
import es.topophone.core.model.cad.GpkgIgnoredReason
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Leer un KML o un KMZ por flujo a un almacén temporal, con sus límites y su limpieza (S031/RF-1 a RF-4, RF-5, RF-17). */
class KmlReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val cache: File by lazy { folder.newFolder("cache") }
    private var ids = 0

    private fun reader(
        maxFile: Long = 100L * 1024 * 1024,
        maxUncompressed: Long = 500L * 1024 * 1024,
        freeSpace: () -> Long = { Long.MAX_VALUE },
    ) = KmlReader(cache, freeSpace, { "t${ids++}" }, maxFile, maxUncompressed)

    private fun read(path: String, reader: KmlReader = reader(), declared: Long? = KmlFixtures.file(path).length()): KmlReadResult =
        runBlocking { reader.read(KmlFixtures.input(path, declared)) }

    private fun store(result: KmlReadResult) = result.shouldBeInstanceOf<KmlReadResult.Read>().store

    private fun leftovers() = cache.listFiles().orEmpty().toList()

    private fun failure(name: String) = when (name) {
        "EMPTY" -> KmlOpenFailure.EMPTY
        "NOT_KML" -> KmlOpenFailure.NOT_KML
        "NO_PLACEMARKS" -> KmlOpenFailure.NO_PLACEMARKS
        "DAMAGED" -> KmlOpenFailure.DAMAGED
        else -> error(name)
    }

    @Test
    fun `S031 RF-2 cada fichero de ejemplo se abre o falla como se espera y no deja nada si falla`() {
        for ((name, row) in KmlFixtures.files) {
            val result = read(name)
            val expected = row.getValue("resultado")
            if (expected == "OK") {
                val store = store(result)
                store.validObjects shouldBe row.getValue("objetos").toInt()
                store.drawingName shouldBe name
                store.hasElevations shouldBe (row.getValue("altitudes_absolutas") == "SI")
                store.ignoredElements shouldBe ignored(row.getValue("ignorados"))
                store.close()
            } else {
                result.shouldBeInstanceOf<KmlReadResult.Failed>().reason shouldBe failure(expected)
            }
            leftovers() shouldBe emptyList()
        }
    }

    private fun ignored(text: String): Map<GpkgIgnoredReason, Int> {
        val pairs = text.split(";").filter { it.isNotEmpty() }
        return pairs.associate { pair -> GpkgIgnoredReason.valueOf(pair.substringBefore('=')) to pair.substringAfter('=').toInt() }
    }

    @Test
    fun `S031 RF-5 las capas del almacen son las del fichero con sus objetos y sus columnas`() {
        var checked = 0
        for ((name, row) in KmlFixtures.files.filter { it.value.getValue("resultado") == "OK" }) {
            val store = store(read(name))
            val expected = KmlFixtures.expected("esperado-capas.csv").filter { it.getValue("archivo") == name }
            val byKey = store.layers.associateBy { it.path to if (it.points) "POINTS" else "LINES" }
            byKey.size shouldBe expected.size
            for (capa in expected) {
                val key = capa.getValue("capa").replace(Regex(" \\((puntos|líneas)\\)$"), "") to capa.getValue("tipo")
                val layer = byKey.getValue(key)
                layer.count shouldBe capa.getValue("objetos").toInt()
                layer.columnNames.joinToString(";") shouldBe capa.getValue("columnas")
                checked++
            }
            store.close()
        }
        (checked > 25) shouldBe true
    }

    @Test
    fun `S031 RF-5 un objeto se lee con su blob de geometria, su color y sus valores`() {
        val store = store(read("estilos.kml"))
        val layer = store.layers.first { it.path == "Estilos" && it.points }

        val record = store.read(layer, 1)
        record.ignoredEmpty shouldBe false
        record.color shouldBe 0xFFFF0000.toInt()
        record.values shouldBe mapOf(0 to "Rojo")
        val parts = GpkgGeometryReader.read(record.blob!!).shouldBeInstanceOf<GpkgGeometry.Parts>().parts
        parts.single().x.toList() shouldBe listOf(-3.7038)
        store.read(layer, 2).color shouldBe null
        store.close()
    }

    @Test
    fun `S031 RF-11 los atributos de un objeto van a las columnas del almacen`() {
        val store = store(read("atributos.kml"))
        val layer = store.layers.single()

        val poste = store.read(layer, 1)
        poste.values[0] shouldBe "Poste"
        poste.values[1] shouldBe "Poste de hormigón\nLínea 2\nLínea 3 & fin"
        poste.values[layer.columnNames.indexOf("altura")] shouldBe "12.5"
        poste.values[layer.columnNames.indexOf("name_2")] shouldBe "dup"
        store.read(layer, 3).values shouldBe mapOf(0 to "Sin datos")
        store.close()
    }

    @Test
    fun `S031 RF-9 una geometria vacia es un registro ignorado sin blob`() {
        val store = store(read("ignorados.kml"))
        val layer = store.layers.first { it.path == "Mezcla" && it.points }

        store.read(layer, 3).ignoredEmpty shouldBe true
        store.read(layer, 3).blob shouldBe null
        store.read(layer, 1).ignoredEmpty shouldBe false
        store.close()
    }

    @Test
    fun `S031 RF-13 la cabecera de un objeto trae su envolvente sin leer el resto`() {
        val store = store(read("geometrias.kml"))
        val layer = store.layers.first { it.points }

        val head = store.readHead(layer, 1)
        val blob = head.blob!!
        blob.size shouldBe 40
        GpkgGeometryReader.header(blob)!!.envelope!!.minX shouldBe -3.7038
        head.values shouldBe emptyMap()
        store.close()
    }

    @Test
    fun `S031 RF-4 un fichero mas grande que el limite no se lee aunque el proveedor diga que es pequeno`() {
        read("carpetas-anidadas.kml", reader(maxFile = 100)).shouldBeInstanceOf<KmlReadResult.Failed>().reason shouldBe
            KmlOpenFailure.TOO_LARGE
        read("carpetas-anidadas.kml", reader(maxFile = 1_000), declared = 10).shouldBeInstanceOf<KmlReadResult.Failed>().reason shouldBe
            KmlOpenFailure.TOO_LARGE
        read("con-doc.kmz", reader(maxFile = 100)).shouldBeInstanceOf<KmlReadResult.Failed>().reason shouldBe KmlOpenFailure.TOO_LARGE
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S031 RF-4 un KMZ que descomprime mas del limite no se lee`() {
        val result = read("con-doc.kmz", reader(maxUncompressed = 100))

        result.shouldBeInstanceOf<KmlReadResult.Failed>().reason shouldBe KmlOpenFailure.TOO_LARGE
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S031 RF-4 con el espacio justo se lee y sin espacio se dice`() {
        read("con-doc.kmz", reader(freeSpace = { 0 })).shouldBeInstanceOf<KmlReadResult.Failed>().reason shouldBe KmlOpenFailure.NO_SPACE
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S031 RF-17 una importacion interrumpida deja una carpeta que se borra al arrancar`() {
        File(cache, "kml-viejo").apply {
            mkdirs()
            File(this, "objetos.bin").writeText("x")
        }
        File(cache, "otra-cosa").mkdirs()

        reader().cleanUpStale() shouldBe 1

        leftovers().map { it.name } shouldBe listOf("otra-cosa")
    }

    @Test
    fun `S031 RF-17 cancelar la lectura borra la carpeta temporal`() {
        val job = Job()
        val bytes = KmlFixtures.bytes("carpetas-anidadas.kml")
        val input = KmlInput("carpetas-anidadas.kml", bytes.size.toLong()) {
            object : ByteArrayInputStream(bytes) {
                private var reads = 0

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (++reads == 3) job.cancel()
                    return super.read(b, off, minOf(len, 64))
                }
            }
        }
        var cancelled = false
        runBlocking {
            val task = async(Dispatchers.IO + job) { reader().read(input) }
            try {
                task.await()
            } catch (e: CancellationException) {
                cancelled = true
            }
        }

        cancelled shouldBe true
        leftovers() shouldBe emptyList()
    }

    @Test
    fun `S031 RNF-T5 una entrada de un KMZ con una ruta que sale de la carpeta no escribe nada fuera ni se enlaza`() {
        val kml = "<kml><Document><Placemark><name>P</name><Point><coordinates>1,2</coordinates></Point></Placemark></Document></kml>"
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                for (name in listOf("doc.kml", "../fuera.kml", "/absoluto.kml")) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(kml.toByteArray())
                    z.closeEntry()
                }
            }
        }.toByteArray()

        val store = store(runBlocking { reader().read(KmlInput("seguro.kmz", zip.size.toLong()) { ByteArrayInputStream(zip) }) })

        store.validObjects shouldBe 1
        store.close()
        folder.root.walkTopDown().filter { it.isFile }.toList() shouldBe emptyList()
    }

    @Test
    fun `S031 RF-2 el KML de un KMZ de varias capas de QGIS se lee por sus vinculos`() {
        val store = store(read("qgis/qgis-libkml-multicapa.kmz"))

        store.layers.map { it.path }.toSet() shouldBe setOf("puntos", "lineas", "poligonos")
        store.validObjects shouldBe 7
        store.close()
    }
}
