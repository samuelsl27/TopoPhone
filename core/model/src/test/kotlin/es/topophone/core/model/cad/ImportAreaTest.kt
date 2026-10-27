package es.topophone.core.model.cad

import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.LinearUnit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/** La zona de importación de un GeoPackage y los tipos de sus capas (S048/RF-5, RF-13, RF-16, RF-25). */
class ImportAreaTest {

    private val madrid = CentralPoint(40.4168, -3.7038)

    @Test
    fun `S048 RF-13 el radio va de 100 m a 100 km`() {
        ImportArea.Circle(madrid, 100.0).radiusM shouldBe 100.0
        ImportArea.Circle(madrid, 100_000.0).radiusM shouldBe 100_000.0
        shouldThrow<IllegalArgumentException> { ImportArea.Circle(madrid, 99.99) }
        shouldThrow<IllegalArgumentException> { ImportArea.Circle(madrid, 100_000.01) }
        shouldThrow<IllegalArgumentException> { ImportArea.Circle(madrid, Double.NaN) }
        shouldThrow<IllegalArgumentException> { ImportArea.Circle(madrid, -5.0) }
    }

    @Test
    fun `S048 RF-13 la zona propuesta es de 5 km alrededor del centro`() {
        ImportArea.proposed(madrid) shouldBe ImportArea.Circle(madrid, 5_000.0)
        ImportArea.DEFAULT_RADIUS_M shouldBe 5_000.0
    }

    @Test
    fun `S048 RF-13 todo el contenido es una zona sin centro ni radio`() {
        val area: ImportArea = ImportArea.All

        area shouldBe ImportArea.All
        area shouldNotBe ImportArea.proposed(madrid)
    }

    @Test
    fun `S048 RF-16 el limite son 500000 objetos`() {
        ImportArea.MAX_OBJECTS shouldBe 500_000
    }

    @Test
    fun `S048 RF-5 el tipo de la capa sale del tipo de geometria que declara`() {
        GpkgLayerKind.of("POINT") shouldBe GpkgLayerKind.POINTS
        GpkgLayerKind.of("multipoint") shouldBe GpkgLayerKind.POINTS
        GpkgLayerKind.of("LINESTRING") shouldBe GpkgLayerKind.LINES
        GpkgLayerKind.of("MULTILINESTRING") shouldBe GpkgLayerKind.LINES
        GpkgLayerKind.of("POLYGON") shouldBe GpkgLayerKind.POLYGONS
        GpkgLayerKind.of(" MultiPolygon ") shouldBe GpkgLayerKind.POLYGONS
        GpkgLayerKind.of("GEOMETRY") shouldBe GpkgLayerKind.MIXED
        GpkgLayerKind.of("GEOMETRYCOLLECTION") shouldBe GpkgLayerKind.MIXED
    }

    @Test
    fun `S048 RF-5 la capa dice su tipo y sus columnas`() {
        val layer =
            GpkgLayerInfo("parcelas", "geom", "MULTIPOLYGON", 12, 25830, listOf(GpkgColumn("nombre", "TEXT")), hasSpatialIndex = true)

        layer.kind shouldBe GpkgLayerKind.POLYGONS
        layer.columns.single().name shouldBe "nombre"
        GpkgLayerInfo("x", "geom", "POINT", 0, 4326, emptyList()).hasSpatialIndex shouldBe false
    }

    @Test
    fun `S048 RF-25 el resumen suma importados, fuera de la zona e ignorados de todas las capas`() {
        val summary = GpkgImportSummary(
            listOf(
                GpkgLayerSummary("a", imported = 10, outsideArea = 4, ignored = mapOf(GpkgIgnoredReason.CURVE_GEOMETRY to 2)),
                GpkgLayerSummary(
                    "b",
                    imported = 5,
                    outsideArea = 0,
                    ignored = mapOf(GpkgIgnoredReason.EMPTY_GEOMETRY to 1, GpkgIgnoredReason.OUTSIDE_CRS_AREA to 3),
                    omittedAttributes = 7,
                ),
            ),
            leftOutTables = listOf("censo", "teselas"),
            wgs84Layers = listOf("a"),
        )

        summary.importedTotal shouldBe 15
        summary.outsideAreaTotal shouldBe 4
        summary.ignoredTotal shouldBe 6
        summary.layers[1].ignoredTotal shouldBe 4
        summary.layers[1].omittedAttributes shouldBe 7
        summary.leftOutTables shouldBe listOf("censo", "teselas")
    }

    @Test
    fun `S048 RF-26 un dibujo viene de un DXF salvo que se diga lo contrario`() {
        val drawing = CadDrawing("d", "p", "f.dxf", 1, CadSourceCrs.Project, LinearUnit.METRE, true, true)

        drawing.origin shouldBe CadOrigin.DXF
        drawing.copy(origin = CadOrigin.GEOPACKAGE).origin shouldBe CadOrigin.GEOPACKAGE
    }
}
