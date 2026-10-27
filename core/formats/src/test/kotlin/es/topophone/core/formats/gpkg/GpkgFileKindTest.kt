package es.topophone.core.formats.gpkg

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Qué es un fichero por su contenido: GeoPackage, SQLite que no lo es, texto, vacío o dañado, y el límite de tamaño (S048/RF-2 a RF-4). */
class GpkgFileKindTest {

    private fun probe(name: String): GpkgProbe {
        val size = GpkgFixtures.size(name)
        val head = GpkgFixtures.bytes(name).copyOf(minOf(size, GpkgFileKind.HEAD_BYTES.toLong()).toInt())
        return GpkgFileKind.probe(head, size)
    }

    @Test
    fun `S048 RF-2 los GeoPackage de ejemplo se reconocen por su contenido`() {
        val files = GpkgFixtures.expected("esperado-resumen.csv")
            .filter { it["clave"] == "tipo_fichero" && it["valor"] == "GEOPACKAGE" }
            .map { it.getValue("archivo") }

        (files.size >= 15) shouldBe true
        files.forEach { probe(it) shouldBe GpkgProbe.Candidate(declared = true) }
    }

    @Test
    fun `S048 RF-2 la extension no cuenta`() {
        probe("lineas-con-otra-extension.dat") shouldBe GpkgProbe.Candidate(declared = true)
        probe("lineas-4258.gpkg") shouldBe GpkgProbe.Candidate(declared = true)
    }

    @Test
    fun `S048 RF-3 un SQLite que no es un GeoPackage es candidato sin la declaracion`() {
        probe("sqlite-comun.gpkg") shouldBe GpkgProbe.Candidate(declared = false)
    }

    @Test
    fun `S048 RF-3 las versiones antiguas de la declaracion tambien valen`() {
        for (id in listOf("GPKG", "GP10", "GP11")) {
            GpkgFileKind.probe(header(applicationId = id), 4096) shouldBe GpkgProbe.Candidate(declared = true)
        }
        GpkgFileKind.probe(header(applicationId = "GP12"), 4096) shouldBe GpkgProbe.Candidate(declared = false)
    }

    @Test
    fun `S048 RF-3 un texto, un fichero vacio o uno con otra cabecera no se importan`() {
        probe("texto.gpkg") shouldBe GpkgProbe.NotSqlite
        probe("vacio.gpkg") shouldBe GpkgProbe.Empty
        GpkgFileKind.probe(byteArrayOf(1, 2, 3, 0, 5), 5) shouldBe GpkgProbe.NotSqlite
        GpkgFileKind.probe("SQLite format 4\u0000".toByteArray() + ByteArray(90), 100) shouldBe GpkgProbe.NotSqlite
        GpkgFileKind.probe(ByteArray(0), 10) shouldBe GpkgProbe.Empty
    }

    @Test
    fun `S048 RF-3 una cabecera cortada o con un tamano de pagina imposible esta danada`() {
        GpkgFileKind.probe(header().copyOf(60), 60) shouldBe GpkgProbe.Damaged
        GpkgFileKind.probe("SQLite format 3\u0000".toByteArray(), 16) shouldBe GpkgProbe.Damaged
        GpkgFileKind.probe(header(pageSize = 100), 4096) shouldBe GpkgProbe.Damaged
        GpkgFileKind.probe(header(pageSize = 1000), 4096) shouldBe GpkgProbe.Damaged
        GpkgFileKind.probe(header(pageSize = 65535), 4096) shouldBe GpkgProbe.Damaged
    }

    @Test
    fun `S048 RF-3 los tamanos de pagina validos son potencias de 2 de 512 a 32768 y el 1 que es 65536`() {
        for (size in listOf(512, 1024, 4096, 8192, 32768, 1)) {
            GpkgFileKind.probe(header(pageSize = size), 8192) shouldBe GpkgProbe.Candidate(declared = true)
        }
    }

    @Test
    fun `S048 RF-3 un fichero cortado conserva una cabecera valida y lo detecta data al abrirlo`() {
        probe("danado.gpkg") shouldBe GpkgProbe.Candidate(declared = true)
        GpkgFixtures.size("danado.gpkg") shouldBe 8192
    }

    @Test
    fun `S048 RF-4 mas de 100 MB no se importa y justo 100 MB si`() {
        val head = GpkgFixtures.bytes("puntos-25830.gpkg").copyOf(GpkgFileKind.HEAD_BYTES)

        GpkgFileKind.MAX_BYTES shouldBe 100L * 1024 * 1024
        GpkgFileKind.probe(head, GpkgFileKind.MAX_BYTES) shouldBe GpkgProbe.Candidate(declared = true)
        GpkgFileKind.probe(head, GpkgFileKind.MAX_BYTES + 1) shouldBe GpkgProbe.TooLarge
    }

    @Test
    fun `S048 RF-4 un texto de mas de 100 MB sigue siendo un texto`() {
        GpkgFileKind.probe("hola".toByteArray(), GpkgFileKind.MAX_BYTES + 1) shouldBe GpkgProbe.NotSqlite
    }

    @Test
    fun `S048 RF-2 se leen los primeros 100 bytes de un flujo`() {
        val all = GpkgFixtures.bytes("puntos-25830.gpkg")

        GpkgFileKind.readHead(all.inputStream()).toList() shouldBe all.take(100)
        GpkgFileKind.readHead(ByteArray(10).inputStream()).size shouldBe 10
    }

    /** Una cabecera de SQLite de 100 bytes con el tamaño de página y el `application_id` dados. */
    private fun header(pageSize: Int = 4096, applicationId: String = "GPKG"): ByteArray {
        val bytes = ByteArray(100)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[16] = (pageSize shr 8).toByte()
        bytes[17] = pageSize.toByte()
        applicationId.toByteArray(Charsets.US_ASCII).copyInto(bytes, 68)
        return bytes
    }
}
