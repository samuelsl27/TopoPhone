package es.topophone.data.kml

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsUnit
import es.topophone.core.formats.gpkg.GpkgGeometry
import es.topophone.core.formats.gpkg.GpkgGeometryReader
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.data.cad.GpkgFeature
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Un KML leído como sesión de capas: capas con su ruta, objetos, color, atributos y geometrías (S031/RF-5 a RF-15). */
class KmlSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val cache: File by lazy { folder.newFolder("cache") }

    private fun open(path: String, datum: GeodeticDatum = GeodeticDatum.ETRS89): KmlSession {
        val result = runBlocking { KmlReader(cache).read(KmlFixtures.input(path)) }
        return KmlSession(result.shouldBeInstanceOf<KmlReadResult.Read>().store, datum)
    }

    private fun features(session: KmlSession, layerName: String): List<GpkgFeature> {
        val layer = session.layers.first { it.table == layerName }
        val out = ArrayList<GpkgFeature>()
        session.forEachFeature(layer) { out += it }
        return out
    }

    @Test
    fun `S031 RF-5 las capas son las del fichero con su nombre, su tipo, sus objetos y sus columnas`() {
        for ((name, row) in KmlFixtures.files.filter { it.value.getValue("resultado") == "OK" }) {
            val session = open(name)
            val expected = KmlFixtures.expected("esperado-capas.csv").filter { it.getValue("archivo") == name }

            session.layers.map { it.table } shouldBe expected.map { it.getValue("capa") }
            for ((layer, capa) in session.layers.zip(expected)) {
                layer.geometryType shouldBe if (capa.getValue("tipo") == "POINTS") "POINT" else "LINESTRING"
                layer.featureCount shouldBe capa.getValue("objetos").toLong()
                layer.columns.map { it.name }.joinToString(";") shouldBe capa.getValue("columnas")
                layer.columns.all { it.type == "TEXT" } shouldBe true
            }
            session.close()
        }
    }

    @Test
    fun `S031 RF-5 los nombres de las capas de un fichero son distintos`() {
        for (name in KmlFixtures.files.filter { it.value.getValue("resultado") == "OK" }.keys) {
            val session = open(name)

            session.layers.map { it.table }.toSet().size shouldBe session.layers.size
            session.close()
        }
    }

    @Test
    fun `S031 RF-5 los sufijos de las dos capas de una carpeta los da quien abre la sesion`() {
        val result = runBlocking { KmlReader(cache).read(KmlFixtures.input("carpetas-anidadas.kml")) }
        val store = result.shouldBeInstanceOf<KmlReadResult.Read>().store
        val session = KmlSession(store, GeodeticDatum.ETRS89, KmlLayerSuffixes(" [P]", " [L]"))

        session.layers.map { it.table }.filter { it.startsWith("Obra") } shouldBe
            listOf(
                "Obra [L]",
                "Obra [P]",
                "Obra / Parcelas / Norte [P]",
                "Obra / Parcelas / Norte [L]",
                "Obra / Parcelas / Sur",
                "Obra / Anexo",
            )
        session.close()
    }

    @Test
    fun `S031 RF-7 la sesion es un KML en las geograficas del datum de los calculos marcado como WGS84`() {
        val etrs89 = open("sin-ns.kml")
        etrs89.origin shouldBe CadOrigin.KML
        etrs89.crs(etrs89.layers.first()) shouldBe
            GpkgCrs.Supported(4258, CadSourceCrs.Geographic(GeodeticDatum.ETRS89), GpkgCrsUnit.DEGREE, wgs84 = true)
        val regcan = open("sin-ns.kml", GeodeticDatum.REGCAN95)
        regcan.crs(regcan.layers.first()) shouldBe
            GpkgCrs.Supported(4081, CadSourceCrs.Geographic(GeodeticDatum.REGCAN95), GpkgCrsUnit.DEGREE, wgs84 = true)
        etrs89.close()
        regcan.close()
    }

    @Test
    fun `S031 RF-13 la sesion dice si hay altitudes absolutas y que elementos se ignoran`() {
        for ((name, row) in KmlFixtures.files.filter { it.value.getValue("resultado") == "OK" }) {
            val session = open(name)

            session.hasElevations shouldBe (row.getValue("altitudes_absolutas") == "SI")
            session.leftOutTables shouldBe emptyList()
            session.close()
        }
        val ignorados = open("ignorados.kml")
        ignorados.ignoredElements shouldBe mapOf(
            GpkgIgnoredReason.NETWORK_LINK to 2,
            GpkgIgnoredReason.OVERLAY to 3,
            GpkgIgnoredReason.MODEL to 2,
            GpkgIgnoredReason.TRACK to 2,
            GpkgIgnoredReason.TOUR to 1,
        )
        ignorados.close()
    }

    @Test
    fun `S031 RF-12 el color de cada objeto es el de su estilo`() {
        val session = open("estilos.kml")

        features(session, "Estilos (puntos)").map { it.color } shouldBe listOf(
            0xFFFF0000.toInt(), null, 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF332211.toInt(), 0xFF00FF00.toInt(),
            null, null, 0xFF332211.toInt(), null, null, null, null,
        )
        features(session, "Estilos (líneas)").map { it.color } shouldBe listOf(0xFF0000FF.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt())
        session.close()
    }

    @Test
    fun `S031 RF-10 y RF-11 los valores van como texto en el orden de las columnas y vacio es nulo`() {
        val session = open("atributos.kml")
        val layer = session.layers.single()
        val rows = features(session, layer.table)

        layer.columns.map { it.name } shouldBe
            listOf("name", "description", "altura", "material", "name_2", "diametro", "vacio", "mezcla", "html")
        rows[0].values shouldBe listOf(
            GpkgValue.Text("Poste"),
            GpkgValue.Text("Poste de hormigón\nLínea 2\nLínea 3 & fin"),
            GpkgValue.Text("12.5"),
            GpkgValue.Text("hormigón"),
            GpkgValue.Text("dup"),
            GpkgValue.Null,
            GpkgValue.Null,
            GpkgValue.Null,
            GpkgValue.Null,
        )
        rows[2].values.drop(1).all { it == GpkgValue.Null } shouldBe true
        rows[3].values[7] shouldBe GpkgValue.Text("a & b")
        session.close()
    }

    @Test
    fun `S031 RF-10 una marca sin nombre tiene el valor de nombre nulo`() {
        val session = open("geometrias.kml")

        features(session, "Geometrias (puntos)").count { it.values.first() == GpkgValue.Null } shouldBe 1
        session.close()
    }

    @Test
    fun `S031 RF-9 una geometria vacia es un objeto ignorado que consume su clave`() {
        val session = open("ignorados.kml")
        val rows = features(session, "Mezcla (puntos)")

        rows.map { it.fid } shouldBe listOf(1L, 2L, 3L, 4L)
        rows.map { it.ignored } shouldBe listOf(null, null, GpkgIgnoredReason.EMPTY_GEOMETRY, GpkgIgnoredReason.EMPTY_GEOMETRY)
        rows[2].geometry shouldBe null
        session.close()
    }

    @Test
    fun `S031 RF-8 las geometrias de todos los ficheros coinciden con lo esperado vertice a vertice`() {
        var checked = 0
        val expected = KmlFixtures.expected("esperado.csv").groupBy { row ->
            Triple(row.getValue("archivo"), row.getValue("capa"), row.getValue("fid").toLong())
        }
        for ((name, row) in KmlFixtures.files.filter { it.value.getValue("resultado") == "OK" }) {
            val session = open(name)
            for (layer in session.layers) {
                for (feature in features(session, layer.table)) {
                    val parts = expected.getValue(Triple(name, layer.table, feature.fid))
                    if (parts.first().getValue("estado") == "ignorado_vacia") {
                        feature.ignored shouldBe GpkgIgnoredReason.EMPTY_GEOMETRY
                        continue
                    }
                    val decoded = GpkgGeometryReader.read(feature.geometry!!).shouldBeInstanceOf<GpkgGeometry.Parts>().parts
                    decoded.size shouldBe parts.size
                    for ((part, expectedPart) in decoded.zip(parts)) {
                        val vertices = expectedPart.getValue("vertices_kml").split(";").map { it.split(" ") }
                        part.x.toList() shouldBe vertices.map { it[0].toDouble() }
                        part.y.toList() shouldBe vertices.map { it[1].toDouble() }
                        part.z?.toList() shouldBe if (vertices[0][2] == "_") null else vertices.map { it[2].toDouble() }
                        part.isPolygonRing shouldBe (expectedPart.getValue("contorno") == "SI")
                        checked++
                    }
                }
            }
            session.close()
        }
        (checked > 80) shouldBe true
    }

    @Test
    fun `S031 RF-13 las cabeceras traen la envolvente de cada objeto con geometria`() {
        val session = open("ignorados.kml")
        val layer = session.layers.first { it.table == "Mezcla (puntos)" }
        val heads = ArrayList<Pair<Long, ByteArray?>>()

        session.forEachHead(layer) { fid, head -> heads += fid to head }

        heads.map { it.first } shouldBe listOf(1L, 2L)
        for ((_, head) in heads) GpkgGeometryReader.header(head!!)!!.envelope!!.minX.let { (it < 0) shouldBe true }
        session.forEachIndexRow(layer) { _, _, _, _, _ -> error("sin indice") } shouldBe false
        session.close()
    }

    @Test
    fun `S031 RF-13 las geometrias por clave salen en el orden pedido y se saltan las claves que no existen`() {
        val session = open("geometrias.kml")
        val layer = session.layers.first { it.table == "Geometrias (puntos)" }
        val got = ArrayList<Long>()

        session.forEachGeometry(layer, longArrayOf(3, 1, 99, 0)) { fid, geometry ->
            got += fid
            (geometry != null) shouldBe true
        }

        got shouldBe listOf(3L, 1L)
        session.close()
    }

    @Test
    fun `S031 RF-7 un proyecto en ED50 no se puede usar como datum de un KML`() {
        val result = runBlocking { KmlReader(cache).read(KmlFixtures.input("sin-ns.kml")) }
        val store = result.shouldBeInstanceOf<KmlReadResult.Read>().store
        var failed = false
        try {
            KmlSession(store, GeodeticDatum.ED50)
        } catch (e: IllegalArgumentException) {
            failed = true
        }

        failed shouldBe true
        store.close()
    }

    @Test
    fun `S031 RF-1 abrir un fichero da la sesion y abrir uno que no vale da el motivo`() {
        val reader = KmlReader(cache)

        val opened = runBlocking { reader.open(KmlFixtures.input("con-doc.kmz"), GeodeticDatum.ETRS89) }
        opened.shouldBeInstanceOf<KmlOpenResult.Opened>().session.layers.size shouldBe 2
        opened.session.close()
        val failed = runBlocking { reader.open(KmlFixtures.input("texto.kml"), GeodeticDatum.ETRS89) }
        failed.shouldBeInstanceOf<KmlOpenResult.Failed>().reason shouldBe KmlOpenFailure.NOT_KML
        cache.listFiles().orEmpty().toList() shouldBe emptyList()
    }

    @Test
    fun `S031 RF-19 el dibujo toma el nombre del fichero`() {
        val session = open("con-doc.kmz")

        session.drawingName shouldBe "con-doc.kmz"
        session.validObjects shouldBe 2
        session.close()
    }
}
