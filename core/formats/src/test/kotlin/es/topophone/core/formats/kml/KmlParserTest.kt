package es.topophone.core.formats.kml

import es.topophone.core.model.cad.GpkgIgnoredReason
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.util.zip.ZipFile
import org.junit.jupiter.api.Test

class KmlParserTest {

    private class Parsed(val placemarks: List<KmlPlacemark>, val ignored: Map<GpkgIgnoredReason, Int>)

    private fun parse(text: String): Parsed {
        val out = ArrayList<KmlPlacemark>()
        val result = KmlParser().parse(ByteArrayInputStream(text.toByteArray())) { out += it }
        return Parsed(out, result.ignored)
    }

    private fun parseFile(path: String): Parsed {
        val out = ArrayList<KmlPlacemark>()
        val result = KmlFixtures.file(path).inputStream().use { KmlParser().parse(it) { p -> out += p } }
        return Parsed(out, result.ignored)
    }

    /** Un KMZ con su KML principal (el de [KmlFileKind.mainEntry]) y vínculos internos. */
    private fun parseKmz(path: String): Parsed {
        val out = ArrayList<KmlPlacemark>()
        ZipFile(KmlFixtures.file(path)).use { zip ->
            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            val main = KmlFileKind.mainEntry(names)!!
            val entries = KmzEntries(names) { name -> zip.getEntry(name)?.let { ByteArrayInputStream(zip.getInputStream(it).readBytes()) } }
            val result = zip.getInputStream(zip.getEntry(main)).use { KmlParser().parse(it, entries, main) { p -> out += p } }
            return Parsed(out, result.ignored)
        }
    }

    private fun placemark(inner: String, head: String = "") =
        "<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>$head<Placemark>$inner</Placemark></Document></kml>"

    // ---------- carpetas y rutas (RF-5) ----------

    @Test
    fun `S031 RF-5 la ruta de una marca son sus carpetas sin el Document raiz y sin las carpetas sin nombre`() {
        val parsed = parseFile("carpetas-anidadas.kml")

        parsed.placemarks.associate { it.name to it.path } shouldBe mapOf(
            "Suelto A" to emptyList(),
            "Eje" to listOf("Obra"),
            "Hito" to listOf("Obra"),
            "P1" to listOf("Obra", "Parcelas", "Norte"),
            "P2" to listOf("Obra", "Parcelas", "Norte"),
            "L1" to listOf("Obra", "Parcelas", "Norte"),
            "S1" to listOf("Obra", "Parcelas", "Sur"),
            "En carpeta sin nombre" to listOf("Obra"),
            "En documento anidado" to listOf("Obra", "Anexo"),
            "Suelto B" to emptyList(),
        )
    }

    @Test
    fun `S031 RF-5 las marcas salen en el orden del fichero`() {
        parseFile("carpetas-anidadas.kml").placemarks.map { it.name } shouldBe
            listOf("Suelto A", "Eje", "Hito", "P1", "P2", "L1", "S1", "En carpeta sin nombre", "En documento anidado", "Suelto B")
    }

    // ---------- geometrias (RF-8) ----------

    @Test
    fun `S031 RF-8 un punto, una linea, un anillo y un poligono con hueco dan sus partes`() {
        val byName = parseFile("geometrias.kml").placemarks.associateBy { it.name }

        byName.getValue("Punto").points.parts.map { it.vertices.size } shouldBe listOf(1)
        byName.getValue("Linea").lines.parts.map { it.vertices.size to it.contour } shouldBe listOf(3 to false)
        byName.getValue("Anillo").lines.parts.map { it.vertices.size to it.contour } shouldBe listOf(5 to false)
        byName.getValue("Parcela").lines.parts.map { it.contour } shouldBe listOf(true, true)
        byName.getValue("Parcela con dos huecos").lines.parts.size shouldBe 3
    }

    @Test
    fun `S031 RF-8 una MultiGeometry reparte sus geometrias entre puntos y lineas, tambien anidada`() {
        val byName = parseFile("geometrias.kml").placemarks.associateBy { it.name }

        byName.getValue("Dos puntos").points.parts.size shouldBe 2
        byName.getValue("Mixta").points.parts.size shouldBe 1
        byName.getValue("Mixta").lines.parts.size shouldBe 1
        byName.getValue("Dos lineas").lines.parts.size shouldBe 2
        byName.getValue("Anidada").points.parts.size shouldBe 2
        byName.getValue("Anidada").lines.parts.size shouldBe 1
    }

    @Test
    fun `S031 RF-10 una marca sin nombre tiene el nombre vacio`() {
        val sinNombre = parseFile("geometrias.kml").placemarks.filter { it.name.isEmpty() }

        sinNombre.size shouldBe 1
        sinNombre.single().points.parts.size shouldBe 1
    }

    @Test
    fun `S031 RF-8 los vertices se leen en longitud y latitud tal como estan`() {
        val point = parseFile("carpetas-anidadas.kml").placemarks.first { it.name == "Suelto A" }.points.parts.single().vertices

        point.lon[0] shouldBe -3.7038
        point.lat[0] shouldBe 40.4168
    }

    // ---------- vacias e ignoradas (RF-9) ----------

    @Test
    fun `S031 RF-9 las geometrias vacias se cuentan en su grupo y las marcas validas se entregan`() {
        val parsed = parseFile("ignorados.kml")
        val byName = parsed.placemarks.associateBy { it.name }

        byName.getValue("Punto vacio").points.empty shouldBe 1
        byName.getValue("Linea de un vertice").lines.empty shouldBe 1
        byName.getValue("Poligono roto").lines.empty shouldBe 1
        byName.getValue("Anillo roto").lines.empty shouldBe 1
        byName.getValue("Coordenadas ilegibles").points.empty shouldBe 1
        byName.getValue("Valido").points.parts.size shouldBe 1
    }

    @Test
    fun `S031 RF-9 los elementos que no se importan se cuentan por motivo`() {
        parseFile("ignorados.kml").ignored shouldBe mapOf(
            GpkgIgnoredReason.NETWORK_LINK to 2,
            GpkgIgnoredReason.OVERLAY to 3,
            GpkgIgnoredReason.MODEL to 2,
            GpkgIgnoredReason.TRACK to 2,
            GpkgIgnoredReason.TOUR to 1,
        )
    }

    @Test
    fun `S031 RF-9 una marca con solo un modelo o un recorrido no se entrega y una con modelo y punto entrega el punto`() {
        val names = parseFile("ignorados.kml").placemarks.map { it.name }

        names.contains("Modelo") shouldBe false
        names.contains("Pista") shouldBe false
        names.contains("Pistas") shouldBe false
        names.contains("Modelo y punto") shouldBe true
        names.contains("Solo una nota") shouldBe false
    }

    @Test
    fun `S031 RF-3 un KML sin ninguna marca con geometria no entrega marcas`() {
        parseFile("sin-marcas.kml").placemarks shouldBe emptyList()
        parseFile("solo-ignorados.kml").ignored shouldBe mapOf(GpkgIgnoredReason.NETWORK_LINK to 1)
    }

    // ---------- nombre y atributos (RF-10, RF-11) ----------

    @Test
    fun `S031 RF-11 la descripcion sale sin etiquetas HTML aunque venga en CDATA o con entidades`() {
        val byName = parseFile("atributos.kml").placemarks.associateBy { it.name }

        byName.getValue("Poste").description shouldBe "Poste de hormigón\nLínea 2\nLínea 3 & fin"
        byName.getValue("Arqueta").description shouldBe "Tapa rota"
        byName.getValue("Escapes").description shouldBe "Uno\nDos"
        byName.getValue("Sin datos").description shouldBe ""
    }

    @Test
    fun `S031 RF-11 los datos extendidos de Data y de SimpleData se leen en orden y como texto`() {
        val byName = parseFile("atributos.kml").placemarks.associateBy { it.name }

        byName.getValue("Poste").data.entries.map { it.key to it.value } shouldBe
            listOf("altura" to "12.5", "material" to "hormigón", "name" to "dup")
        byName.getValue("Arqueta").data.entries.map { it.key to it.value } shouldBe listOf("diametro" to "0.60", "altura" to "1")
        byName.getValue("Escapes").data["vacio"] shouldBe ""
        byName.getValue("Escapes").data["mezcla"] shouldBe "a & b"
        byName.getValue("Escapes").data["html"] shouldBe "<b>negrita</b>"
        byName.getValue("Sin datos").data shouldBe emptyMap()
    }

    // ---------- color (RF-12) ----------

    @Test
    fun `S031 RF-12 los colores salen de IconStyle para los puntos y de LineStyle para las lineas`() {
        val byName = parseFile("estilos.kml").placemarks.associateBy { it.name }
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()

        byName.getValue("Rojo").iconColor shouldBe red
        byName.getValue("Linea azul").lineColor shouldBe blue
        byName.getValue("Poligono azul").lineColor shouldBe blue
        byName.getValue("Punto con estilo de linea").iconColor shouldBe null
        byName.getValue("Con StyleMap").iconColor shouldBe red
        byName.getValue("StyleMap en linea").iconColor shouldBe 0xFF00FF00.toInt()
        byName.getValue("Estilo en la marca").iconColor shouldBe 0xFF332211.toInt()
        byName.getValue("Punto y linea").iconColor shouldBe 0xFF00FF00.toInt()
        byName.getValue("Punto y linea").lineColor shouldBe 0xFFFFFF00.toInt()
        byName.getValue("Con alfa").iconColor shouldBe 0xFF332211.toInt()
    }

    @Test
    fun `S031 RF-12 sin estilo, con uno que no existe, de otro fichero, que no vale o declarado despues no hay color`() {
        val byName = parseFile("estilos.kml").placemarks.associateBy { it.name }

        val sinColor = listOf(
            "Sin estilo",
            "Estilo sin color",
            "Estilo declarado despues",
            "Estilo de otro fichero",
            "Estilo que no existe",
            "Color que no vale",
        )
        for (name in sinColor) byName.getValue(name).iconColor shouldBe null
    }

    // ---------- altitudes (RF-13, RF-14) ----------

    @Test
    fun `S031 RF-13 solo llevan Z las partes absolutas con altitud en todos sus vertices`() {
        val byName = parseFile("altitudes.kml").placemarks.associateBy { it.name }

        byName.getValue("Abs punto").points.parts.single().hasZ shouldBe true
        byName.getValue("Abs linea").lines.parts.single().hasZ shouldBe true
        byName.getValue("Abs poligono").lines.parts.all { it.hasZ } shouldBe true
        for (name in listOf("Clamp con z", "Relativa", "Sin modo con z", "Fondo marino", "Abs sin z")) {
            byName.getValue(name).points.parts.single().hasZ shouldBe false
        }
        byName.getValue("Abs parcial").lines.parts.single().hasZ shouldBe false
    }

    @Test
    fun `S031 RF-14 una geometria con altitud y sin modo absoluto conserva los vertices pero no se marca absoluta`() {
        val part = parseFile("altitudes.kml").placemarks.first { it.name == "Clamp con z" }.points.parts.single()

        part.absolute shouldBe false
        part.vertices.alt?.toList() shouldBe listOf(10.0)
    }

    @Test
    fun `S031 RF-13 el modo de cada geometria de una MultiGeometry es el suyo`() {
        val parts = parseFile("altitudes.kml").placemarks.first { it.name == "Mixta" }.points.parts

        parts.map { it.absolute } shouldBe listOf(true, false)
    }

    // ---------- espacios de nombres ----------

    @Test
    fun `S031 RF-2 se leen los KML con BOM y de cualquier version 2 o sin espacio de nombres`() {
        for (name in listOf("ns20-bom.kml", "ns22-ogc.kml", "sin-ns.kml")) {
            parseFile(name).placemarks.map { it.name } shouldBe listOf("Un punto")
        }
    }

    // ---------- vinculos internos (RF-2b) ----------

    @Test
    fun `S031 RF-2b los vinculos internos de un KMZ se leen como carpetas con el nombre del vinculo`() {
        val parsed = parseKmz("enlaces-internos.kmz")

        parsed.placemarks.associate { it.name to it.path } shouldBe mapOf(
            "A1" to listOf("Capa A", "Zona"),
            "A2" to listOf("Capa A", "Zona"),
            "Suelta de A" to listOf("Capa A"),
            "B1" to listOf("b"),
            "U1" to listOf("Con Url"),
            "Marca de doc" to listOf("Carpeta"),
            "C1" to listOf("Carpeta", "Anidado"),
            "D1" to listOf("Carpeta", "Anidado", "Hondo"),
            "E1" to listOf("Carpeta", "Anidado", "Hondo", "Mas hondo"),
        )
    }

    @Test
    fun `S031 RF-2b los vinculos que no valen se cuentan como ignorados`() {
        // http, fichero que falta, que sale del KMZ, ruta absoluta, el propio doc.kml, el repetido, un XML que no es KML, el ciclo y el hondo
        parseKmz("enlaces-internos.kmz").ignored shouldBe mapOf(GpkgIgnoredReason.NETWORK_LINK to 9)
    }

    @Test
    fun `S031 RF-2b un KMZ de varias capas de QGIS se lee por sus vinculos`() {
        val parsed = parseKmz("qgis/qgis-libkml-multicapa.kmz")

        parsed.placemarks.map { it.path.single() }.distinct() shouldBe listOf("puntos", "lineas", "poligonos")
        parsed.placemarks.size shouldBe 7
        parsed.ignored shouldBe emptyMap()
    }

    @Test
    fun `S031 RF-2b en un KML suelto los vinculos se ignoran`() {
        val link = "<NetworkLink><Link><href>a.kml</href></Link></NetworkLink>"
        val parsed = parse(placemark("<name>P</name><Point><coordinates>1,2</coordinates></Point>", link))

        parsed.placemarks.size shouldBe 1
        parsed.ignored shouldBe mapOf(GpkgIgnoredReason.NETWORK_LINK to 1)
    }

    @Test
    fun `S031 RF-2b un vinculo con Url se sigue igual que con Link`() {
        val parsed = parseKmz("enlaces-internos.kmz")

        parsed.placemarks.any { it.name == "U1" } shouldBe true
    }

    // ---------- los KML de QGIS ----------

    @Test
    fun `S031 RF-5 el KML del controlador KML de QGIS da una capa por Folder o Document con su nombre`() {
        val parsed = parseFile("qgis/qgis-kml.kml")

        parsed.placemarks.map { it.path.single() }.distinct() shouldBe listOf("puntos", "lineas", "poligonos")
        parsed.placemarks.size shouldBe 7
    }

    @Test
    fun `S031 RF-13 los KML de QGIS no declaran modo de altitud y no llevan Z`() {
        for (name in listOf("qgis/qgis-kml.kml", "qgis/qgis-libkml.kml")) {
            parseFile(name).placemarks.all { p -> (p.points.parts + p.lines.parts).none { it.hasZ } } shouldBe true
        }
    }

    // ---------- XML cortado ----------

    @Test
    fun `S031 RF-3 un KML cortado lanza un error de formato`() {
        var failed = false
        try {
            parseFile("danado.kml")
        } catch (e: es.topophone.core.formats.xml.XmlFormatException) {
            failed = true
        }

        failed shouldBe true
    }

    @Test
    fun `S031 RF-3 un KML enlazado por un KMZ y cortado deja danado todo el KMZ`() {
        var failed = false
        try {
            parseKmz("enlace-danado.kmz")
        } catch (e: es.topophone.core.formats.xml.XmlFormatException) {
            failed = true
        }

        failed shouldBe true
    }

    @Test
    fun `S031 RF-3 lo que no es un KML lanza KmlDamagedException`() {
        var failed = false
        try {
            parse("<gpx><wpt/></gpx>")
        } catch (e: KmlDamagedException) {
            failed = true
        }

        failed shouldBe true
    }

    // ---------- resolucion de vinculos ----------

    @Test
    fun `S031 RF-2b un vinculo se resuelve relativo al KML que lo contiene`() {
        val names = listOf("doc.kml", "layers/a.kml", "sub/c.kml", "sub/d.kml", "LAYERS/B.KML")

        KmlLinks.resolve("doc.kml", "layers/a.kml", names) shouldBe "layers/a.kml"
        KmlLinks.resolve("sub/c.kml", "d.kml", names) shouldBe "sub/d.kml"
        KmlLinks.resolve("sub/c.kml", "../layers/a.kml", names) shouldBe "layers/a.kml"
        KmlLinks.resolve("doc.kml", "./layers/a.kml#frag", names) shouldBe "layers/a.kml"
        KmlLinks.resolve("doc.kml", "layers/A.KML", names) shouldBe "layers/a.kml"
        KmlLinks.resolve("doc.kml", "layers/b.kml", names) shouldBe "LAYERS/B.KML"
    }

    @Test
    fun `S031 RF-2b un vinculo a http, absoluto, que sale del KMZ o que no es un kml no se resuelve`() {
        val names = listOf("doc.kml", "layers/a.kml", "imagen.png")

        val noValen = listOf(
            "http://example.org/a.kml",
            "https://x/a.kml",
            "file:///a.kml",
            "/layers/a.kml",
            "\\layers\\a.kml",
            "../fuera.kml",
            "layers/falta.kml",
            "imagen.png",
            "",
            "#solo",
            "layers/",
        )
        for (href in noValen) KmlLinks.resolve("doc.kml", href, names) shouldBe null
    }
}
