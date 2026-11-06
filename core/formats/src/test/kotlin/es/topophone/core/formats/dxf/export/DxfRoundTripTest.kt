package es.topophone.core.formats.dxf.export

import es.topophone.core.formats.dxf.DxfReader
import es.topophone.core.formats.dxf.DxfSink
import es.topophone.core.formats.dxf.DxfTables
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test

/** Lo escrito se relee con el lector de DXF de S040 sin diferencias (S029/RF-3, RF-4, RF-6, RF-10 y criterio de ida y vuelta). */
class DxfRoundTripTest {
    private class Collected : DxfSink {
        val elements = mutableListOf<Pair<String, CadEntity>>()
        val ignored = mutableListOf<CadIgnored>()
        var tables = DxfTables(emptyMap())

        override fun tables(tables: DxfTables) {
            this.tables = tables
        }

        override fun element(layer: String, entity: CadEntity) {
            elements += layer to entity
        }

        override fun ignored(ignored: CadIgnored) {
            this.ignored += ignored
        }
    }

    private val green = DxfTestData.code("ARBOL", 0x008000)
    private val red = DxfTestData.code("Borde 1", 0xFF0000)
    private val pts = listOf(
        DxfTestData.point("A", 440_000.1234, 4_474_000.7654, 650.1234, green),
        DxfTestData.point("B", 440_010.5, 4_474_010.25, null, red, "Esquina"),
        DxfTestData.point("C", 440_020.0001, 4_474_000.0002, 651.5, null),
        DxfTestData.point("D", 440_030.75, 4_474_020.5, 652.0, red),
    )
    private val lines = listOf(
        DxfTestData.line("L", false, red, pts[0], pts[1], pts[2]),
        DxfTestData.line("P", true, red, pts[0], pts[1], pts[2], pts[3]),
    )

    private fun roundTrip(options: DxfExportOptions, project: es.topophone.core.model.project.Project = DxfTestData.project()): Collected {
        val build = DxfDocumentBuilder.build(pts, lines, pts, project, options)
        val out = ByteArrayOutputStream()
        DxfWriter.write(build.document, out)
        return Collected().also { DxfReader.read(ByteArrayInputStream(out.toByteArray()), LinearUnit.METRE, it) }
    }

    @Test
    fun `S029 RF-4 los puntos vuelven en su capa y con sus coordenadas a menos de 0 05 mm`() {
        val result = roundTrip(DxfExportOptions(labels = emptySet()))

        val points = result.elements.filter { it.second.kind == CadEntityKind.POINT }
        points.map { it.first } shouldBe listOf("ARBOL", "BORDE_1", "SIN_CODIGO", "BORDE_1")
        points.forEachIndexed { i, (_, entity) ->
            val p = pts[i].coordinates
            entity.easting[0] shouldBe (p.easting!! plusOrMinus 5e-5)
            entity.northing[0] shouldBe (p.northing!! plusOrMinus 5e-5)
            val z = entity.height?.get(0) ?: 0.0
            z shouldBe ((p.height ?: 0.0) plusOrMinus 5e-5) // sin altura se escribe Z = 0 (RF-9)
        }
        points[0].second.height?.get(0) shouldBe (650.1234 plusOrMinus 5e-5)
    }

    @Test
    fun `S029 RF-3 la polilinea y el poligono vuelven con sus vertices y el poligono cerrado`() {
        val result = roundTrip(DxfExportOptions(labels = emptySet()))

        val polylines = result.elements.filter { it.second.kind == CadEntityKind.POLYLINE }
        polylines.size shouldBe 2
        polylines.map { it.first } shouldBe listOf("BORDE_1", "BORDE_1")
        val open = polylines[0].second
        open.vertexCount shouldBe 3
        open.easting.toList() shouldBe listOf(440_000.1234, 440_010.5, 440_020.0001)
        val closed = polylines[1].second
        // un polígono cerrado se lee con el primer vértice repetido al final o con la marca de anillo
        (closed.vertexCount == 5 || closed.polygonRing) shouldBe true
        closed.easting.take(4) shouldBe listOf(440_000.1234, 440_010.5, 440_020.0001, 440_030.75)
    }

    @Test
    fun `S029 RF-6 las capas vuelven con el color ACI mas cercano al del codigo`() {
        val result = roundTrip(DxfExportOptions(labels = emptySet()))

        val layers = result.tables.layers
        layers.getValue("ARBOL").aci shouldBe AciColor.nearest(0x008000)
        layers.getValue("BORDE_1").aci shouldBe 1
        layers.getValue("SIN_CODIGO").aci shouldBe 7
    }

    @Test
    fun `S029 RF-7 las etiquetas son TEXT y el lector las ignora sin romper nada`() {
        val result = roundTrip(DxfExportOptions(labels = DxfLabel.entries.toSet()))

        result.elements.count { it.second.kind == CadEntityKind.POINT } shouldBe 4
        result.elements.count { it.second.kind == CadEntityKind.POLYLINE } shouldBe 2
        result.ignored.all { it.dxfType == "TEXT" } shouldBe true
        result.ignored.size shouldBe 8 // A: nombre y cota, B: nombre y descripción, C y D: nombre y cota
    }

    @Test
    fun `S029 RF-10 RF-12 las coordenadas globales vuelven con nueve decimales a menos de 5e-10 grados`() {
        val geographic = listOf(
            DxfTestData.point("G1", lat = 40.123456789, lon = -3.987654321, ellipsoidal = 700.5),
            DxfTestData.point("G2", lat = 28.4636012, lon = -16.2518045, ellipsoidal = 100.25),
        )
        val options = DxfExportOptions(labels = emptySet(), coordinates = DxfCoordinates.GLOBAL)
        val build = DxfDocumentBuilder.build(geographic, emptyList(), geographic, DxfTestData.project(), options)
        val out = ByteArrayOutputStream()
        DxfWriter.write(build.document, out)
        val result = Collected().also { DxfReader.read(ByteArrayInputStream(out.toByteArray()), LinearUnit.METRE, it) }

        result.elements.forEachIndexed { i, (_, entity) ->
            entity.easting[0] shouldBe (geographic[i].coordinates.ground.longitudeDeg plusOrMinus 5e-10)
            entity.northing[0] shouldBe (geographic[i].coordinates.ground.latitudeDeg plusOrMinus 5e-10)
            entity.height?.get(0) shouldBe (geographic[i].coordinates.ground.ellipsoidalHeightM!! plusOrMinus 5e-10)
        }
    }

    @Test
    fun `S029 RF-4 el fichero completo se lee entero`() {
        val result = roundTrip(DxfExportOptions(labels = DxfLabel.entries.toSet()))

        result.elements.size shouldBe 6
        (result.tables.layers.size >= 6) shouldBe true
    }
}
