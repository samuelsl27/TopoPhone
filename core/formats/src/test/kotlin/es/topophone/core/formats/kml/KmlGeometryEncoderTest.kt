package es.topophone.core.formats.kml

import es.topophone.core.formats.gpkg.GpkgEnvelope
import es.topophone.core.formats.gpkg.GpkgGeometry
import es.topophone.core.formats.gpkg.GpkgGeometryReader
import es.topophone.core.formats.gpkg.GpkgPartKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KmlGeometryEncoderTest {

    private fun vertices(text: String) = KmlCoordinates.parse(text)!!

    private fun point(text: String, absolute: Boolean = false): KmlPart =
        KmlPart(vertices(text), contour = false, absolute = absolute, isPoint = true)

    private fun line(text: String, absolute: Boolean = false) = KmlPart(vertices(text), contour = false, absolute = absolute)

    private fun ring(text: String, absolute: Boolean = false) = KmlPart(vertices(text), contour = true, absolute = absolute)

    private fun read(parts: List<KmlPart>) =
        GpkgGeometryReader.read(KmlGeometryEncoder.encode(parts)).shouldBeInstanceOf<GpkgGeometry.Parts>().parts

    @Test
    fun `S031 RF-7 un punto se lee como un punto en longitud y latitud`() {
        val parts = read(listOf(point("-3.7038,40.4168")))

        parts.single().kind shouldBe GpkgPartKind.POINT
        parts.single().x.toList() shouldBe listOf(-3.7038)
        parts.single().y.toList() shouldBe listOf(40.4168)
        parts.single().z shouldBe null
    }

    @Test
    fun `S031 RF-8 una linea conserva sus vertices en orden`() {
        val parts = read(listOf(line("1,2 3,4 5,6")))

        parts.single().kind shouldBe GpkgPartKind.LINE
        parts.single().isPolygonRing shouldBe false
        parts.single().x.toList() shouldBe listOf(1.0, 3.0, 5.0)
        parts.single().y.toList() shouldBe listOf(2.0, 4.0, 6.0)
    }

    @Test
    fun `S031 RF-8 un contorno de poligono se lee como una linea marcada como anillo`() {
        val parts = read(listOf(ring("0,0 1,0 1,1 0,0")))

        parts.single().kind shouldBe GpkgPartKind.LINE
        parts.single().isPolygonRing shouldBe true
        parts.single().vertexCount shouldBe 4
    }

    @Test
    fun `S031 RF-8 varias partes se leen en el mismo orden con su tipo y su marca de anillo`() {
        val parts = read(listOf(point("0,0"), line("1,1 2,2"), ring("0,0 1,0 1,1 0,0"), ring("0.1,0.1 0.2,0.1 0.2,0.2 0.1,0.1")))

        parts.map { it.kind to it.isPolygonRing } shouldBe listOf(
            GpkgPartKind.POINT to false,
            GpkgPartKind.LINE to false,
            GpkgPartKind.LINE to true,
            GpkgPartKind.LINE to true,
        )
        parts[3].x.toList() shouldBe listOf(0.1, 0.2, 0.2, 0.1)
    }

    @Test
    fun `S031 RF-13 el blob lleva Z solo si todas las partes son absolutas y traen altitud`() {
        read(listOf(point("1,2,10", absolute = true))).single().z?.toList() shouldBe listOf(10.0)
        read(listOf(line("1,2,10 3,4,20", absolute = true))).single().z?.toList() shouldBe listOf(10.0, 20.0)
        read(listOf(ring("0,0,5 1,0,5 1,1,5 0,0,5", absolute = true))).single().z?.toList() shouldBe listOf(5.0, 5.0, 5.0, 5.0)
    }

    @Test
    fun `S031 RF-14 una parte que no es absoluta o sin altitud completa deja el blob sin Z`() {
        read(listOf(point("1,2,10", absolute = false))).single().z shouldBe null
        read(listOf(line("1,2,10 3,4", absolute = true))).single().z shouldBe null
        read(listOf(point("1,2,10", absolute = true), point("3,4,20", absolute = false))).all { it.z == null } shouldBe true
    }

    @Test
    fun `S031 RF-13 con todas las partes absolutas la Z de cada una se conserva`() {
        val parts = read(listOf(point("1,2,10", absolute = true), line("3,4,20 5,6,30", absolute = true)))

        parts[0].z?.toList() shouldBe listOf(10.0)
        parts[1].z?.toList() shouldBe listOf(20.0, 30.0)
    }

    @Test
    fun `S031 RF-7 la cabecera lleva la envolvente de todas las partes y no esta vacia`() {
        val blob = KmlGeometryEncoder.encode(listOf(point("-3.7,40.4"), line("-3.9,40.1 -3.5,40.9")))
        val header = GpkgGeometryReader.header(blob)!!

        header.empty shouldBe false
        header.envelope shouldBe GpkgEnvelope(-3.9, -3.5, 40.1, 40.9)
        header.srsId shouldBe 0
        KmlGeometryEncoder.envelope(listOf(point("1,2"))) shouldBe GpkgEnvelope(1.0, 1.0, 2.0, 2.0)
    }

    @Test
    fun `S031 RF-7 las coordenadas conservan toda su precision`() {
        val parts = read(listOf(point("-3.703800000000123,40.416800000000456")))

        parts.single().x[0] shouldBe -3.703800000000123
        parts.single().y[0] shouldBe 40.416800000000456
    }

    @Test
    fun `S031 RF-7 un objeto sin partes no se puede codificar`() {
        assertThrows<IllegalArgumentException> { KmlGeometryEncoder.encode(emptyList()) }
    }

    @Test
    fun `S031 RF-8 lo que sale del parser para los ficheros de ejemplo se lee igual desde el blob`() {
        var checked = 0
        for (name in listOf("geometrias.kml", "altitudes.kml", "carpetas-anidadas.kml", "estilos.kml")) {
            val placemarks = KmlFixtures.file(name).inputStream().use { input ->
                val out = ArrayList<KmlPlacemark>()
                KmlParser().parse(ByteArrayInputStream(input.readBytes())) { out += it }
                out
            }
            for (placemark in placemarks) {
                for (group in listOf(placemark.points, placemark.lines)) {
                    if (group.parts.isEmpty()) continue
                    val back = read(group.parts)
                    back.size shouldBe group.parts.size
                    for ((original, decoded) in group.parts.zip(back)) {
                        decoded.x.toList() shouldBe original.vertices.lon.toList()
                        decoded.y.toList() shouldBe original.vertices.lat.toList()
                        decoded.isPolygonRing shouldBe original.contour
                        checked++
                    }
                }
            }
        }
        (checked > 40) shouldBe true
    }
}
