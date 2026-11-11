package es.topophone.core.model.cad

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Lo que gana el modelo de los dibujos con la importación de Shapefile (S030/RF-16, RF-20, RF-26). */
class ShapefileTypesTest {

    @Test
    fun `S030 RF-26 el origen de un dibujo puede ser un Shapefile`() {
        CadOrigin.valueOf("SHAPEFILE") shouldBe CadOrigin.SHAPEFILE
        CadOrigin.entries.map { it.name } shouldBe listOf("DXF", "GEOPACKAGE", "SHAPEFILE")
    }

    @Test
    fun `S030 RF-16 los objetos borrados y los MultiPatch tienen su motivo de ignorado`() {
        GpkgIgnoredReason.entries shouldContain GpkgIgnoredReason.DELETED_RECORD
        GpkgIgnoredReason.entries shouldContain GpkgIgnoredReason.MULTIPATCH
        val layer = GpkgLayerSummary(
            table = "parcelas",
            imported = 3,
            outsideArea = 1,
            ignored = mapOf(GpkgIgnoredReason.DELETED_RECORD to 2, GpkgIgnoredReason.MULTIPATCH to 1),
        )
        layer.ignoredTotal shouldBe 3
    }

    @Test
    fun `S030 RF-20 el resumen suma los objetos sin atributos de todas las capas`() {
        val summary = GpkgImportSummary(
            layers = listOf(
                GpkgLayerSummary("vertices", imported = 5, outsideArea = 0, withoutAttributes = 2),
                GpkgLayerSummary("ejes", imported = 3, outsideArea = 1, withoutAttributes = 3),
                GpkgLayerSummary("parcelas", imported = 2, outsideArea = 0),
            ),
        )
        summary.withoutAttributesTotal shouldBe 5
        summary.importedTotal shouldBe 10
        GpkgLayerSummary("vacia", imported = 0, outsideArea = 0).withoutAttributes shouldBe 0
    }
}
