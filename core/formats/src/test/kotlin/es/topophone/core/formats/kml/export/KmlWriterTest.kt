package es.topophone.core.formats.kml.export

import io.kotest.matchers.shouldBe
import java.io.File
import java.io.StringWriter
import org.junit.jupiter.api.Test

/** Los ejemplos de `fixtures/formatos/kml-exportado/`, que genera `tools/formatos/generar_kml_exportado.py` (S031). */
object KmlExportFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    val folder: File get() = File(root, "fixtures/formatos/kml-exportado")

    val examples = listOf("con-codigos", "sin-codigos", "con-fotos")

    fun bytes(name: String): ByteArray = File(folder, name).readBytes()

    private fun field(text: String) = text.replace("\\n", "\n").replace("\\p", "|")

    /** El documento de `entrada-<example>.txt`: una línea por elemento, campos separados por `|`. */
    fun document(example: String): KmlDocument {
        var name = ""
        val styles = ArrayList<KmlStyleDef>()
        val folders = ArrayList<Pair<String, MutableList<KmlMark>>>()
        class Draft(
            val kind: String,
            val name: String,
            val description: String,
            val style: String,
            val absolute: Boolean,
            val photos: List<String>,
        ) {
            val data = ArrayList<Pair<String, String>>()
            val vertices = ArrayList<KmlVertex>()
        }
        var draft: Draft? = null

        fun flush() {
            val d = draft ?: return
            val geometry = when (d.kind) {
                "point" -> KmlGeometry.Point(d.vertices.single())
                "line" -> KmlGeometry.Line(d.vertices)
                else -> KmlGeometry.Polygon(d.vertices)
            }
            folders.last().second += KmlMark(d.name, d.description, d.style.ifEmpty { null }, d.data, geometry, d.absolute, d.photos)
            draft = null
        }
        for (line in String(bytes("entrada-$example.txt"), Charsets.UTF_8).lines().filter { it.isNotBlank() }) {
            val p = line.split("|")
            when (p[0]) {
                "doc" -> name = field(p[1])
                "style" -> styles += KmlStyleDef(p[1], parseBgr(p[2]))
                "folder" -> {
                    flush()
                    folders += field(p[1]) to ArrayList()
                }
                "mark" -> {
                    flush()
                    val photos = p.getOrNull(6)?.split(";")?.filter { it.isNotEmpty() }.orEmpty()
                    draft = Draft(p[1], field(p[2]), field(p[3]), p[4], p[5] == "absolute", photos)
                }
                "data" -> draft!!.data += field(p[1]) to field(p[2])
                "vertex" -> draft!!.vertices += KmlVertex(p[1].toDouble(), p[2].toDouble(), p[3].toDoubleOrNull())
                else -> error("Línea desconocida: $line")
            }
        }
        flush()
        return KmlDocument(name, styles, folders.map { KmlFolder(it.first, it.second) })
    }

    /** `aabbggrr` a ARGB. */
    private fun parseBgr(text: String): Int {
        val value = text.toLong(16)
        val b = ((value shr 16) and 0xFF).toInt()
        val g = ((value shr 8) and 0xFF).toInt()
        val r = (value and 0xFF).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

class KmlWriterTest {

    private fun write(document: KmlDocument): String = StringWriter().also { KmlWriter.write(document, it) }.toString()

    private fun mark(
        name: String = "P",
        description: String = "",
        data: List<Pair<String, String>> = emptyList(),
        geometry: KmlGeometry = KmlGeometry.Point(KmlVertex(-3.7038, 40.4168)),
        absolute: Boolean = false,
        photos: List<String> = emptyList(),
        style: String? = null,
    ) = KmlMark(name, description, style, data, geometry, absolute, photos)

    private fun one(mark: KmlMark, styles: List<KmlStyleDef> = emptyList()) =
        write(KmlDocument("D", styles, listOf(KmlFolder("F", listOf(mark)))))

    @Test
    fun `S031 RF-21 el KML escrito coincide byte a byte con el de referencia de tools`() {
        for (example in KmlExportFixtures.examples) {
            val written = write(KmlExportFixtures.document(example)).toByteArray(Charsets.UTF_8)

            written.toList() shouldBe KmlExportFixtures.bytes("$example.kml").toList()
        }
    }

    @Test
    fun `S031 RF-21 el fin de linea es LF y el documento termina con un salto de linea`() {
        val text = write(KmlExportFixtures.document("con-codigos"))

        text.contains('\r') shouldBe false
        text.endsWith("</kml>\n") shouldBe true
    }

    @Test
    fun `S031 RF-21 los nombres, las descripciones y los valores escapan ampersand y los menor y mayor que`() {
        val text = one(mark(name = "A & B <c>", description = "x < y > z & w", data = listOf("n" to "1 & 2")))

        text.contains("<name>A &amp; B &lt;c&gt;</name>") shouldBe true
        text.contains("<description>x &lt; y &gt; z &amp; w</description>") shouldBe true
        text.contains("<Data name=\"n\"><value>1 &amp; 2</value></Data>") shouldBe true
    }

    @Test
    fun `S031 RF-21 el nombre de un dato extendido escapa tambien las comillas`() {
        val text = one(mark(data = listOf("a\"b<c" to "v")))

        text.contains("<Data name=\"a&quot;b&lt;c\"><value>v</value></Data>") shouldBe true
    }

    @Test
    fun `S031 RF-21 un valor vacio es value vacio y una descripcion vacia no escribe el elemento`() {
        val text = one(mark(description = "", data = listOf("n" to "")))

        text.contains("<Data name=\"n\"><value/></Data>") shouldBe true
        text.contains("<description>") shouldBe false
    }

    @Test
    fun `S031 RF-21 una marca sin datos extendidos no escribe ExtendedData`() {
        one(mark()).contains("<ExtendedData>") shouldBe false
    }

    @Test
    fun `S031 RF-23 un estilo de codigo lleva el color en icono y linea y el relleno con alfa 80`() {
        val text = one(mark(style = "ARBOL"), listOf(KmlStyleDef("ARBOL", 0xFFFF0000.toInt())))

        text.contains("<IconStyle><color>ff0000ff</color></IconStyle>") shouldBe true
        text.contains("<LineStyle><color>ff0000ff</color></LineStyle>") shouldBe true
        text.contains("<PolyStyle><color>800000ff</color></PolyStyle>") shouldBe true
        text.contains("<styleUrl>#ARBOL</styleUrl>") shouldBe true
    }

    @Test
    fun `S031 RF-24 la longitud y la latitud llevan 9 decimales`() {
        val text = one(mark(geometry = KmlGeometry.Point(KmlVertex(-3.7038, 40.416800000049))))

        text.contains("<coordinates>-3.703800000,40.416800000</coordinates>") shouldBe true
    }

    @Test
    fun `S031 RF-26 con altura absoluta se escribe la altitudeMode y la altura con 4 decimales`() {
        val text = one(mark(geometry = KmlGeometry.Point(KmlVertex(-3.7038, 40.4168, 646.377)), absolute = true))

        text.contains("<altitudeMode>absolute</altitudeMode>") shouldBe true
        text.contains("<coordinates>-3.703800000,40.416800000,646.3770</coordinates>") shouldBe true
    }

    @Test
    fun `S031 RF-27 una marca pegada al suelo no escribe altitud aunque sus vertices la tengan`() {
        val text = one(mark(geometry = KmlGeometry.Point(KmlVertex(-3.7038, 40.4168, 646.377)), absolute = false))

        text.contains("<altitudeMode>") shouldBe false
        text.contains("<coordinates>-3.703800000,40.416800000</coordinates>") shouldBe true
    }

    @Test
    fun `S031 RF-22 una linea y un poligono escriben sus vertices separados por un espacio`() {
        val line = one(mark(geometry = KmlGeometry.Line(listOf(KmlVertex(1.0, 2.0), KmlVertex(3.0, 4.0)))))
        val ring = listOf(KmlVertex(0.0, 0.0), KmlVertex(1.0, 0.0), KmlVertex(1.0, 1.0), KmlVertex(0.0, 0.0))
        val polygon = one(mark(geometry = KmlGeometry.Polygon(ring)))

        line.contains("<LineString>") shouldBe true
        line.contains("<coordinates>1.000000000,2.000000000 3.000000000,4.000000000</coordinates>") shouldBe true
        polygon.contains("<outerBoundaryIs>") shouldBe true
        polygon.contains(
            "<coordinates>0.000000000,0.000000000 1.000000000,0.000000000 1.000000000,1.000000000 0.000000000,0.000000000</coordinates>",
        ) shouldBe true
    }

    @Test
    fun `S031 RF-29 una marca con fotos lleva la descripcion en CDATA con una imagen por foto`() {
        val text = one(mark(description = "Poste & base\nalto", photos = listOf("P_1.jpg", "P_2.jpg")))

        text.contains(
            "<description><![CDATA[Poste &amp; base<br/>alto" +
                "<br/><img src=\"files/P_1.jpg\" width=\"400\"/><br/><img src=\"files/P_2.jpg\" width=\"400\"/>]]></description>",
        ) shouldBe true
    }

    @Test
    fun `S031 RF-29 sin fotos la descripcion no lleva CDATA`() {
        one(mark(description = "Tapa")).contains("CDATA") shouldBe false
    }

    @Test
    fun `S031 RF-21 un documento sin carpetas es un KML valido`() {
        val text = write(KmlDocument("Vacío", emptyList(), emptyList()))

        text shouldBe "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n" +
            "  <Document>\n    <name>Vacío</name>\n  </Document>\n</kml>\n"
    }
}
