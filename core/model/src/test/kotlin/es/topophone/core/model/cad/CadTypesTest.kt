package es.topophone.core.model.cad

import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los tipos del dibujo importado y de su importación (S040/RF-7, RF-8, RF-16, RF-18, RF-23). */
class CadTypesTest {

    @Test
    fun `S040 RF-6 y RF-8 por defecto el sistema es el del proyecto y se usan las cotas`() {
        val options = CadImportOptions()

        options.source shouldBe CadSourceCrs.Project
        options.useElevations shouldBe true
    }

    @Test
    fun `S040 RF-7 por defecto la unidad es el metro`() {
        CadImportOptions().unit shouldBe LinearUnit.METRE
        CadImportOptions(unit = LinearUnit.US_SURVEY_FOOT).unit shouldBe LinearUnit.US_SURVEY_FOOT
    }

    @Test
    fun `S040 RF-6 otro sistema lleva su datum y su huso`() {
        val source = CadSourceCrs.Utm(GeodeticDatum.ED50, 30)

        source.datum shouldBe GeodeticDatum.ED50
        source.zone shouldBe 30
        CadImportOptions(source = source).source shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
    }

    @Test
    fun `S040 RF-26 una capa de diseno es de puntos o de lineas`() {
        DesignLayerKind.entries.toSet() shouldBe setOf(DesignLayerKind.POINTS, DesignLayerKind.LINES)
    }

    @Test
    fun `S040 RF-14 y RF-29 los tipos de elemento son los de la ficha`() {
        CadEntityKind.entries.map { it.name } shouldBe
            listOf("POINT", "LINE", "POLYLINE", "ARC", "CIRCLE", "SPLINE", "BLOCK_INSERT")
    }

    @Test
    fun `S040 RF-25 el blanco o negro del DXF se marca para pintarlo del color de contraste`() {
        CadColor.AUTO_CONTRAST.autoContrast shouldBe true
        CadColor.rgb(0xFF0000).argb shouldBe 0xFFFF0000.toInt()
        CadColor.rgb(0x336699).autoContrast shouldBe false
    }

    @Test
    fun `S040 RF-18 el resumen suma lo importado y lo ignorado`() {
        val summary = CadImportSummary(
            imported = mapOf(CadEntityKind.POINT to 3, CadEntityKind.LINE to 4),
            ignored = mapOf(
                CadIgnored(CadIgnoredReason.UNSUPPORTED_TYPE, "TEXT") to 2,
                CadIgnored(CadIgnoredReason.OUTSIDE_CRS_AREA) to 1,
            ),
            layers = 2,
            incomplete = false,
        )

        summary.importedTotal shouldBe 7
        summary.ignoredTotal shouldBe 3
    }

    @Test
    fun `S040 RF-16 y RF-17 cada motivo de lo ignorado tiene su nombre`() {
        CadIgnoredReason.entries.map { it.name }.toSet() shouldBe setOf(
            "UNSUPPORTED_TYPE",
            "OUTSIDE_CRS_AREA",
            "OUTSIDE_ED50_GRID",
            "BLOCK_CYCLE",
            "BLOCK_TOO_LARGE",
            "UNDEFINED_BLOCK",
            "INVALID_VALUE",
        )
    }

    @Test
    fun `S040 RF-23 un elemento esta con cota si el dibujo usa cotas y el elemento trae Z`() {
        val drawing = drawing(hasElevations = true)

        drawing.hasElevation(entityHasZ = true) shouldBe true
        drawing.hasElevation(entityHasZ = false) shouldBe false
        drawing.copy(hasElevations = false).hasElevation(entityHasZ = true) shouldBe false
    }

    @Test
    fun `S040 RF-22 un dibujo nuevo no esta incompleto y no tiene extension`() {
        val drawing = drawing(hasElevations = false)

        drawing.incomplete shouldBe false
        drawing.extent shouldBe null
    }

    @Test
    fun `S040 RF-26 una capa de diseno nueva llega visible salvo que se diga otra cosa`() {
        val layer = DesignLayer("l", "d", "Ejes", DesignLayerKind.LINES, CadColor.rgb(0xFF0000))

        layer.visible shouldBe true
        layer.entityCount shouldBe 0
        layer.copy(visible = false).visible shouldBe false
    }

    @Test
    fun `S040 RF-29 la referencia a un elemento lleva su capa su trozo y su posicion`() {
        CadEntityRef("l", 7L, 3) shouldBe CadEntityRef("l", 7L, 3)
        CadEntityRef("l", 7L, 3).index shouldBe 3
    }

    private fun drawing(hasElevations: Boolean) =
        CadDrawing("d", "p", "plano.dxf", 0L, CadSourceCrs.Project, LinearUnit.METRE, useElevations = true, hasElevations = hasElevations)
}
