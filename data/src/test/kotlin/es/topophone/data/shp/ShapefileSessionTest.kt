package es.topophone.data.shp

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgGeometry
import es.topophone.core.formats.gpkg.GpkgGeometryReader
import es.topophone.core.formats.shp.ShpEncoding
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.GpkgValue
import es.topophone.data.cad.GpkgFeature
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Las capas de un Shapefile abierto: tipo, columnas, sistema, codificación, objetos y geometrías por registro (S030/RF-7 a RF-9, RF-15 a RF-21). */
class ShapefileSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val opened = ArrayList<ShapefileSession>()
    private var ids = 0
    private val layersExpected = ShpFixtures.expected("esperado-capas.csv")

    @After
    fun closeAll() = opened.forEach { it.close() }

    private fun open(vararg paths: String): ShapefileSession {
        val reader = ShapefileReader(folder.newFolder("c${ids++}"), freeSpace = { Long.MAX_VALUE })
        val result = runBlocking { reader.open(paths.map { ShpFixtures.input(it) }) }
        return result.shouldBeInstanceOf<ShpOpenResult.Opened>().session.also { opened += it }
    }

    private fun scenario(name: String, vararg layers: String): ShapefileSession {
        val files = File(ShpFixtures.folder, name).listFiles().orEmpty()
            .filter { f -> layers.any { f.nameWithoutExtension.equals(it, ignoreCase = true) } }
            .map { "$name/${it.name}" }
        return open(*files.toTypedArray())
    }

    private fun features(session: ShapefileSession, layer: GpkgLayerInfo): List<GpkgFeature> {
        val out = ArrayList<GpkgFeature>()
        session.forEachFeature(layer, null) { out += it }
        return out
    }

    private fun GpkgValue.text(): String = when (this) {
        GpkgValue.Null -> ""
        is GpkgValue.Integer -> value.toString()
        is GpkgValue.Real -> value.toString()
        is GpkgValue.Text -> value
        is GpkgValue.Date -> value
        is GpkgValue.Bool -> value.toString()
        else -> error("No sale de un .dbf")
    }

    @Test
    fun `S030 RF-7 las capas de cada escenario salen con su tipo su numero de objetos y sus columnas`() {
        var checked = 0
        for ((scenario, rows) in layersExpected.groupBy { it.getValue("escenario") }) {
            val session = scenario(scenario, *rows.map { it.getValue("capa") }.toTypedArray())

            session.layers.map { it.table }.toSet() shouldBe rows.map { it.getValue("capa") }.toSet()
            for (row in rows) {
                val layer = session.layers.first { it.table == row.getValue("capa") }
                layer.geometryType shouldBe row.getValue("tipo_geometria")
                layer.featureCount shouldBe row.getValue("objetos").toLong()
                layer.columns.joinToString(";") { "${it.name}:${it.type}" } shouldBe row.getValue("columnas")
                layer.hasSpatialIndex shouldBe false
                checked++
            }
        }
        (checked >= 46) shouldBe true
    }

    @Test
    fun `S030 RF-8 el sistema de cada capa sale de su prj`() {
        for ((scenario, rows) in layersExpected.groupBy { it.getValue("escenario") }) {
            val session = scenario(scenario, *rows.map { it.getValue("capa") }.toTypedArray())
            for (row in rows) {
                val crs = session.crs(session.layers.first { it.table == row.getValue("capa") })

                when (row.getValue("sistema_app")) {
                    "SIN_SISTEMA" -> crs shouldBe GpkgCrs.Undeclared
                    "NO_DISPONIBLE" -> crs.shouldBeInstanceOf<GpkgCrs.Unavailable>()
                    else -> crs.shouldBeInstanceOf<GpkgCrs.Supported>()
                }
            }
        }
    }

    @Test
    fun `S030 RF-21 los valores de cada objeto son los esperados con el tipo del dbf`() {
        val attributes = ShpFixtures.expected("esperado-atributos.csv").groupBy {
            Triple(it.getValue("escenario"), it.getValue("capa"), it.getValue("registro").toInt())
        }
        var checked = 0
        for ((scenario, rows) in layersExpected.groupBy { it.getValue("escenario") }) {
            val session = scenario(scenario, *rows.map { it.getValue("capa") }.toTypedArray())
            for (layer in session.layers) {
                for (feature in features(session, layer)) {
                    val want = attributes[Triple(scenario, layer.table, feature.fid.toInt())] ?: continue
                    feature.values.map { it.text() }.zip(want.map { it.getValue("valor") }).forEach { (got, expected) ->
                        if (expected.toDoubleOrNull() != null && got.toDoubleOrNull() != null) {
                            got.toDouble() shouldBe expected.toDouble()
                        } else {
                            got shouldBe expected
                        }
                        checked++
                    }
                }
            }
        }
        (checked > 150) shouldBe true
    }

    @Test
    fun `S030 RF-15 la geometria de cada objeto se lee con las partes esperadas y el numero de objeto es el registro desde 1`() {
        val expected = ShpFixtures.expected("esperado.csv").filter { it.getValue("estado") == "OK" }
            .groupBy { Triple(it.getValue("escenario"), it.getValue("capa"), it.getValue("registro").toInt()) }
        var checked = 0
        for ((scenario, rows) in layersExpected.groupBy { it.getValue("escenario") }) {
            val session = scenario(scenario, *rows.map { it.getValue("capa") }.toTypedArray())
            for (layer in session.layers) {
                features(session, layer).map { it.fid } shouldBe (1..layer.featureCount).toList()
                for (feature in features(session, layer)) {
                    val want = expected[Triple(scenario, layer.table, feature.fid.toInt())] ?: continue
                    val parts = GpkgGeometryReader.read(feature.geometry!!).shouldBeInstanceOf<GpkgGeometry.Parts>().parts

                    parts.size shouldBe want.size
                    parts.zip(want).forEach { (part, row) ->
                        part.vertexCount shouldBe row.getValue("vertices_fuente").split(";").size
                        part.x[0] shouldBe row.getValue("vertices_fuente").split(";")[0].split(" ")[0].toDouble()
                    }
                    checked++
                }
            }
        }
        (checked > 40) shouldBe true
    }

    @Test
    fun `S030 RF-16 un registro borrado un MultiPatch y una forma nula se distinguen`() {
        val borrados = scenario("borrados-y-nulos", "borrados", "nulos", "multipatch")

        val deleted = features(borrados, borrados.layers.first { it.table == "borrados" })
        deleted.map { it.ignored } shouldBe listOf(
            null,
            GpkgIgnoredReason.DELETED_RECORD,
            null,
            GpkgIgnoredReason.DELETED_RECORD,
            null,
        )
        val nulls = features(borrados, borrados.layers.first { it.table == "nulos" })
        nulls.map { it.geometry == null } shouldBe listOf(false, true, false, true)
        nulls.map { it.ignored } shouldBe listOf(null, null, null, null)
        val patches = features(borrados, borrados.layers.first { it.table == "multipatch" })
        patches.map { it.ignored } shouldBe listOf(GpkgIgnoredReason.MULTIPATCH, GpkgIgnoredReason.MULTIPATCH)
    }

    @Test
    fun `S030 RF-13 las cabeceras traen la envolvente y solo de los objetos con geometria que no estan borrados`() {
        val session = scenario("borrados-y-nulos", "borrados", "nulos")
        val heads = HashMap<String, List<Long>>()
        for (layer in session.layers) {
            val ids = ArrayList<Long>()
            session.forEachHead(layer) { fid, head ->
                val envelope = GpkgGeometryReader.header(head!!)!!.envelope!!
                (envelope.minX <= envelope.maxX) shouldBe true
                ids += fid
            }
            heads[layer.table] = ids
        }

        heads["borrados"] shouldBe listOf(1L, 3L, 5L)
        heads["nulos"] shouldBe listOf(1L, 3L)
    }

    @Test
    fun `S030 RF-19 las geometrias por registro salen igual con shx sin shx y con el shx roto`() {
        val session = scenario("incompletos", "sin_shx", "shx_roto", "sin_dbf", "sin_shx_ni_dbf")
        val reference = open("puntos-25830/vertices.shp", "puntos-25830/vertices.shx")
        val want = ArrayList<List<Byte>?>()
        reference.forEachGeometry(reference.layers.single(), longArrayOf(2, 4, 5)) { _, g -> want += g?.toList() }

        for (table in listOf("sin_shx", "shx_roto", "sin_dbf", "sin_shx_ni_dbf")) {
            val got = ArrayList<List<Byte>?>()
            val ids = ArrayList<Long>()
            session.forEachGeometry(session.layers.first { it.table == table }, longArrayOf(2, 4, 5)) { fid, g ->
                ids += fid
                got += g?.toList()
            }

            ids shouldBe listOf(2L, 4L, 5L)
            got shouldBe want
        }
        session.forEachGeometry(session.layers.first(), longArrayOf(0, 6, 99)) { _, _ -> error("fuera de rango") }
    }

    @Test
    fun `S030 RF-19 una capa sin dbf no tiene columnas y sus objetos no tienen valores`() {
        val session = scenario("incompletos", "sin_dbf")
        val layer = session.layers.single()

        layer.columns shouldBe emptyList()
        features(session, layer).map { it.values } shouldBe List(5) { emptyList<GpkgValue>() }
        features(session, layer).any { it.withoutAttributes } shouldBe false
        session.encoding(layer) shouldBe null
        session.objectsWithoutRecord(layer) shouldBe 0
    }

    @Test
    fun `S030 RF-20 un dbf corto deja sin atributos los objetos que no tienen registro y uno largo ignora los sobrantes`() {
        val session = scenario("incompletos", "dbf_corto", "dbf_largo")
        val short = session.layers.first { it.table == "dbf_corto" }
        val long = session.layers.first { it.table == "dbf_largo" }

        val shortFeatures = features(session, short)
        shortFeatures.map { it.withoutAttributes } shouldBe listOf(false, false, false, true, true)
        shortFeatures.last().values shouldBe List(short.columns.size) { GpkgValue.Null }
        session.objectsWithoutRecord(short) shouldBe 2
        features(session, long).size shouldBe 5
        features(session, long).any { it.withoutAttributes } shouldBe false
        session.objectsWithoutRecord(long) shouldBe 0
    }

    @Test
    fun `S030 RF-18 la codificacion sale del cpg y sin cpg del contenido y se puede cambiar`() {
        val session = scenario("codificaciones", "utf8_cpg", "w1252_cpg", "w1252_sin_cpg", "utf8_sin_cpg", "cpg_desconocido")
        fun layer(name: String) = session.layers.first { it.table == name }

        session.encoding(layer("utf8_cpg")) shouldBe ShpEncoding.UTF_8
        session.encoding(layer("w1252_cpg")) shouldBe ShpEncoding.WINDOWS_1252
        session.encoding(layer("w1252_sin_cpg")) shouldBe ShpEncoding.WINDOWS_1252
        session.encoding(layer("utf8_sin_cpg")) shouldBe ShpEncoding.UTF_8
        session.cpgUnknown(layer("cpg_desconocido")) shouldBe true
        session.cpgUnknown(layer("w1252_cpg")) shouldBe false
        features(session, layer("w1252_sin_cpg")).first().values.first() shouldBe GpkgValue.Text("Árbol ñandú")

        val changed = session.withEncoding(layer("w1252_sin_cpg"), ShpEncoding.UTF_8)

        session.encoding(changed) shouldBe ShpEncoding.UTF_8
        (features(session, changed).first().values.first() as GpkgValue.Text).value.contains('�') shouldBe true
        session.layers.first { it.table == "w1252_sin_cpg" } shouldBe changed
    }

    @Test
    fun `S030 RF-7 dos capas con el mismo nombre en carpetas distintas se distinguen y los huerfanos se avisan`() {
        val reader = ShapefileReader(folder.newFolder("zip"), freeSpace = { Long.MAX_VALUE })
        val repeated = runBlocking { reader.open(listOf(ShpFixtures.input("zip/carpetas-repetidas.zip"))) }
            .shouldBeInstanceOf<ShpOpenResult.Opened>().session.also { opened += it }
        val orphans = runBlocking { reader.open(listOf(ShpFixtures.input("zip/huerfanos.zip"))) }
            .shouldBeInstanceOf<ShpOpenResult.Opened>().session.also { opened += it }

        repeated.layers.map { it.table } shouldBe listOf("norte/parcelas", "sur/parcelas")
        repeated.drawingName shouldBe "carpetas-repetidas.zip"
        orphans.leftOutTables shouldBe listOf("otro.shx", "solo.dbf", "solo.prj")
    }

    @Test
    fun `S030 RF-26 la sesion es de origen Shapefile y al cerrarla se borra la carpeta temporal`() {
        val cache = folder.newFolder("limpia")
        val reader = ShapefileReader(cache, freeSpace = { Long.MAX_VALUE })
        val session = runBlocking { reader.open(listOf(ShpFixtures.input("puntos-25830/vertices.shp"))) }
            .shouldBeInstanceOf<ShpOpenResult.Opened>().session

        session.origin shouldBe es.topophone.core.model.cad.CadOrigin.SHAPEFILE
        cache.listFiles().orEmpty().size shouldBe 1
        session.close()
        session.close()
        cache.listFiles().orEmpty().size shouldBe 0
    }
}
