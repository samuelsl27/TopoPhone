package es.topophone.data.gpkg

import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsUnit
import es.topophone.core.formats.gpkg.GpkgFileKind
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.GpkgLayerKind
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.survey.LayerBox
import es.topophone.data.cad.GpkgFeature
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Abrir un GeoPackage: capas, columnas, sistemas, tablas ajenas, errores y limpieza del temporal (S048/RF-1 a RF-7, RNF-T5). */
@RunWith(AndroidJUnit4::class)
class GeoPackageReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val cache: File by lazy { folder.newFolder("cache") }

    private fun reader(freeSpace: () -> Long = { Long.MAX_VALUE }) = GeoPackageReader(cache, freeSpace)

    private fun open(name: String, bytes: ByteArray = GpkgFixtures.bytes(name), size: Long = bytes.size.toLong()): GpkgOpenResult =
        runBlocking { reader().open(size) { ByteArrayInputStream(bytes) } }

    private fun session(name: String): GpkgSession = open(name).shouldBeInstanceOf<GpkgOpenResult.Opened>().session

    private fun failure(name: String, bytes: ByteArray = GpkgFixtures.bytes(name), size: Long = bytes.size.toLong()): GpkgOpenFailure =
        open(name, bytes, size).shouldBeInstanceOf<GpkgOpenResult.Failed>().reason

    private fun temporaries(): List<String> = cache.listFiles().orEmpty().map { it.name }

    @Test
    fun `S048 RF-5 una capa de puntos con sus columnas`() {
        session("puntos-25830.gpkg").use { session ->
            val layer = session.layers.single()

            layer.table shouldBe "vertices"
            layer.geometryColumn shouldBe "geom"
            layer.geometryType shouldBe "POINT"
            layer.kind shouldBe GpkgLayerKind.POINTS
            layer.featureCount shouldBe 5
            layer.srsId shouldBe 25830
            layer.hasSpatialIndex shouldBe true
            layer.columns.map { it.name to it.type } shouldBe listOf(
                "nombre" to "TEXT",
                "cota" to "REAL",
                "codigo" to "INTEGER",
                "fecha" to "DATE",
                "activo" to "BOOLEAN",
                "foto" to "BLOB",
            )
        }
    }

    @Test
    fun `S048 RF-5 las capas de cada ejemplo son las esperadas`() {
        val expected = GpkgFixtures.expected("esperado-capas.csv").groupBy { it.getValue("archivo") }

        for ((file, rows) in expected) {
            session(file).use { session ->
                session.layers.map { it.table } shouldBe rows.map { it["tabla"] }
                for ((layer, row) in session.layers.zip(rows)) {
                    layer.geometryType shouldBe row["tipo_geometria"]
                    layer.featureCount shouldBe row.getValue("objetos").toLong()
                    layer.srsId shouldBe row.getValue("srs_id").toInt()
                    layer.hasSpatialIndex shouldBe (row["rtree"] == "SI")
                    layer.columns.joinToString(";") { "${it.name}:${it.type}" } shouldBe row["columnas"]
                }
            }
        }
    }

    @Test
    fun `S048 RF-5 un fichero con varias capas las lista en orden`() {
        session("sistemas-mixtos.gpkg").use { session ->
            session.layers.map { it.table } shouldBe listOf("utm29", "utm31", "ed50_utm30", "canarias_utm28", "utm33", "geograficas_4258")
        }
        session("multiples-25830.gpkg").use { session ->
            session.layers.map { it.kind } shouldBe listOf(GpkgLayerKind.POINTS, GpkgLayerKind.LINES, GpkgLayerKind.MIXED)
        }
    }

    @Test
    fun `S048 RF-8 el sistema de cada capa se resuelve frente a la app`() {
        session("sistemas-mixtos.gpkg").use { session ->
            val crs = session.layers.associate { it.table to session.crs(it) }

            crs.getValue("utm29").shouldBeInstanceOf<GpkgCrs.Supported>().source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
            crs.getValue("ed50_utm30").shouldBeInstanceOf<GpkgCrs.Supported>().source shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
            crs.getValue("canarias_utm28").shouldBeInstanceOf<GpkgCrs.Supported>().source shouldBe
                CadSourceCrs.Utm(GeodeticDatum.REGCAN95, 28)
            crs.getValue("geograficas_4258").shouldBeInstanceOf<GpkgCrs.Supported>().unit shouldBe GpkgCrsUnit.DEGREE
            crs.getValue("utm33") shouldBe GpkgCrs.Unavailable("EPSG", 25833)
        }
    }

    @Test
    fun `S048 RF-8 WGS 84 se propone como ETRS89 marcado`() {
        session("wgs84-4326.gpkg").use { session ->
            val crs = session.crs(session.layers.single()).shouldBeInstanceOf<GpkgCrs.Supported>()

            crs.wgs84 shouldBe true
            crs.source shouldBe CadSourceCrs.Geographic(GeodeticDatum.ETRS89)
        }
    }

    @Test
    fun `S048 RF-9 sin sistema declarado o no disponible la capa lo dice`() {
        session("sin-sistema.gpkg").use { session ->
            session.layers.map { session.crs(it) } shouldBe listOf(GpkgCrs.Undeclared, GpkgCrs.Undeclared, GpkgCrs.Undeclared)
        }
        session("no-disponible-3857.gpkg").use { session ->
            session.crs(session.layers.single()) shouldBe GpkgCrs.Unavailable("EPSG", 3857)
        }
    }

    @Test
    fun `S048 RF-6 las tablas sin geometria y las de teselas se dejan fuera`() {
        session("con-ajenas-25830.gpkg").use { session ->
            session.layers.map { it.table } shouldBe listOf("puntos")
            session.leftOutTables shouldBe listOf("censo", "teselas", "notas")
        }
        session("puntos-25830.gpkg").use { it.leftOutTables shouldBe emptyList() }
        session("poligonos-25830.gpkg").use { it.leftOutTables shouldBe emptyList() }
    }

    @Test
    fun `S048 RF-2 se reconoce por el contenido y no por el nombre`() {
        session("lineas-con-otra-extension.dat").use { it.layers.single().table shouldBe "ejes" }
    }

    @Test
    fun `S048 RF-3 un texto, un fichero vacio y un SQLite comun no se abren`() {
        failure("texto.gpkg") shouldBe GpkgOpenFailure.NOT_GEOPACKAGE
        failure("vacio.gpkg") shouldBe GpkgOpenFailure.EMPTY
        failure("sqlite-comun.gpkg") shouldBe GpkgOpenFailure.NOT_GEOPACKAGE
        temporaries() shouldBe emptyList()
    }

    @Test
    fun `S048 RF-3 un GeoPackage cortado esta danado y sin capas se rechaza`() {
        failure("danado.gpkg") shouldBe GpkgOpenFailure.DAMAGED
        failure("sin-capas.gpkg") shouldBe GpkgOpenFailure.NO_LAYERS
        temporaries() shouldBe emptyList()
    }

    @Test
    fun `S048 RF-4 mas de 100 MB no se abre ni se copia`() {
        failure("puntos-25830.gpkg", size = GpkgFileKind.MAX_BYTES + 1) shouldBe GpkgOpenFailure.TOO_LARGE
        temporaries() shouldBe emptyList()
    }

    @Test
    fun `S048 RF-4 un flujo mas largo de lo que dice su tamano se corta`() {
        val head = GpkgFixtures.bytes("puntos-25830.gpkg").copyOf(GpkgFileKind.HEAD_BYTES)
        val endless = object : InputStream() {
            override fun read(): Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also {
                if (off ==
                    0
                ) {
                    head.copyInto(b, 0, 0, minOf(len, head.size))
                }
            }
        }

        val result = runBlocking { GeoPackageReader(cache, maxBytes = 1_000_000L).open(1_000) { endless } }

        result.shouldBeInstanceOf<GpkgOpenResult.Failed>().reason shouldBe GpkgOpenFailure.TOO_LARGE
        temporaries() shouldBe emptyList()
    }

    @Test
    fun `S048 RF-4 sin espacio para la copia no se empieza`() {
        val bytes = GpkgFixtures.bytes("puntos-25830.gpkg")

        val result =
            runBlocking { GeoPackageReader(cache, freeSpace = { 1_000L }).open(bytes.size.toLong()) { ByteArrayInputStream(bytes) } }

        result.shouldBeInstanceOf<GpkgOpenResult.Failed>().reason shouldBe GpkgOpenFailure.NO_SPACE
        temporaries() shouldBe emptyList()
    }

    @Test
    fun `S048 RF-1 si el selector falla al leer no queda el temporal`() {
        val bytes = GpkgFixtures.bytes("puntos-25830.gpkg")
        var opened = 0
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("sin conexion")
        }

        val result = runBlocking {
            reader().open(bytes.size.toLong()) { if (opened++ == 0) ByteArrayInputStream(bytes) else broken }
        }

        result.shouldBeInstanceOf<GpkgOpenResult.Failed>().reason shouldBe GpkgOpenFailure.READ_ERROR
        temporaries() shouldBe emptyList()
    }

    @Test
    fun `S048 RF-7 se abre de solo lectura y el original no se toca`() {
        val original = folder.newFile("original.gpkg").apply { writeBytes(GpkgFixtures.bytes("puntos-25830.gpkg")) }
        val before = original.readBytes()

        val result = runBlocking { reader().open(original.length()) { original.inputStream() } }

        result.shouldBeInstanceOf<GpkgOpenResult.Opened>().session.use { session ->
            session.isReadOnly shouldBe true
            session.layers.single().featureCount shouldBe 5
        }
        original.readBytes().toList() shouldBe before.toList()
        original.parentFile.listFiles().orEmpty().map { it.name }.sorted() shouldBe listOf("cache", "original.gpkg")
    }

    @Test
    fun `S048 RF-7 un fichero en modo WAL sin el wal se lee consolidado`() {
        val wal = GpkgFixtures.bytes("puntos-25830.gpkg").also {
            it[18] = 2
            it[19] = 2
        } // versiones de escritura y lectura: WAL

        val opened = open("wal.gpkg", wal)

        opened.shouldBeInstanceOf<GpkgOpenResult.Opened>().session.use { it.layers.single().featureCount shouldBe 5 }
        temporaries() shouldBe emptyList()
    }

    @Test
    fun `S048 RNF-T5 cerrar la sesion borra el temporal y se puede cerrar dos veces`() {
        val session = session("puntos-25830.gpkg")

        temporaries().size shouldBe 1
        session.close()
        session.close()

        temporaries() shouldBe emptyList()
    }

    @Test
    fun `S048 RNF-T5 cancelar mientras se copia no deja el temporal`() {
        val bytes = GpkgFixtures.bytes("puntos-25830.gpkg")
        var opened = 0
        val cancelling = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelado")

            override fun read(b: ByteArray, off: Int, len: Int): Int = throw CancellationException("cancelado")
        }

        val cancelled = runCatching {
            runBlocking { reader().open(bytes.size.toLong()) { if (opened++ == 0) ByteArrayInputStream(bytes) else cancelling } }
        }

        (cancelled.exceptionOrNull() is CancellationException) shouldBe true
        temporaries() shouldBe emptyList()
    }

    @Test
    fun `S048 RNF-T5 al arrancar se borran los temporales de una importacion interrumpida`() {
        File(cache, "gpkg-vieja.tmp").writeText("a medias")
        File(cache, "gpkg-vieja.tmp-wal").writeText("a medias")
        File(cache, "otra-cosa.txt").writeText("no es nuestra")

        val deleted = reader().cleanUpStale()

        deleted shouldBe 2
        temporaries() shouldBe listOf("otra-cosa.txt")
    }

    @Test
    fun `S048 RF-17 los objetos de una capa salen con su clave, geometria y atributos`() {
        session("puntos-25830.gpkg").use { session ->
            val layer = session.layers.single()
            val features = mutableListOf<GpkgFeature>()

            session.forEachFeature(layer) { features += it }

            features.map { it.fid } shouldBe listOf(1L, 2L, 3L, 4L, 5L)
            features.all { it.geometry != null && it.geometry!![0] == 'G'.code.toByte() } shouldBe true
            features[0].values.take(5) shouldBe listOf(
                GpkgValue.Text("Esquina norte"),
                GpkgValue.Real(646.377),
                GpkgValue.Integer(1),
                GpkgValue.Date("2026-10-01"),
                GpkgValue.Bool(true),
            )
            features[1].values[0] shouldBe GpkgValue.Text("Árbol ñandú")
            features[1].values[4] shouldBe GpkgValue.Bool(false)
        }
    }

    @Test
    fun `S048 RF-20 nulos, vacios y blobs grandes se distinguen`() {
        session("puntos-25830.gpkg").use { session ->
            val features = mutableListOf<GpkgFeature>()
            session.forEachFeature(session.layers.single()) { features += it }

            features[2].values[0] shouldBe GpkgValue.Null
            features[3].values[0] shouldBe GpkgValue.Text("")
            features[3].values[1] shouldBe GpkgValue.Null
            features[0].values[5] shouldBe GpkgValue.Null
            features[4].values[5] shouldBe GpkgValue.Omitted(70_000)
        }
    }

    @Test
    fun `S048 RF-13 con indice espacial salen al menos los del recuadro`() {
        session("sin-rtree-25830.gpkg").use { session ->
            val withIndex = session.layers.first { it.table == "con_rtree" }
            val around = mutableListOf<Long>()

            session.forEachFeature(withIndex, box(withIndex, session, 120.0)) { around += it.fid }

            // En un móvil el R-tree deja solo el objeto 1; el SQLite de Robolectric no trae el módulo y devuelve todos (respaldo). Nunca falta uno.
            (1L in around) shouldBe true
            (around.size in 1..3) shouldBe true
        }
    }

    @Test
    fun `S048 RF-13 sin indice espacial se devuelven todos los objetos`() {
        session("sin-rtree-25830.gpkg").use { session ->
            val bare = session.layers.first { it.table == "sin_rtree" }
            val seen = mutableListOf<Long>()

            session.forEachFeature(bare, LayerBox(0.0, 1.0, 0.0, 1.0)) { seen += it.fid }

            seen shouldBe listOf(1L, 2L, 3L)
        }
    }

    @Test
    fun `S048 RNF-T5 una excepcion al recorrer no deja la sesion sin cerrar`() {
        val session = session("puntos-25830.gpkg")
        val layer: GpkgLayerInfo = session.layers.single()

        val thrown = runCatching { session.forEachFeature(layer) { throw CancellationException("cancelado") } }
        session.close()

        (thrown.exceptionOrNull() is CancellationException) shouldBe true
        temporaries() shouldBe emptyList()
    }

    /** Un recuadro alrededor de (440000, 4474000) de radio [half] en el sistema UTM 30 de la capa. */
    private fun box(layer: GpkgLayerInfo, session: GpkgSession, half: Double): LayerBox {
        layer.srsId shouldBe 25830
        session.crs(layer).shouldBeInstanceOf<GpkgCrs.Supported>()
        return LayerBox(440_000.0 + 90.0 - half, 440_000.0 + 90.0 + half, 4_474_000.0 + 30.0 - half, 4_474_000.0 + 30.0 + half)
    }
}
