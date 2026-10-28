package es.topophone.core.survey

import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.SurfaceExtent
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La extensión de un ráster en el sistema del proyecto, para la pantalla de importación y el resumen (S045/RF-11, RF-16). */
class RasterFootprintTest {

    private val expected = RasterTestFiles.table("extensiones.csv").associateBy { it.getValue("fichero") }

    private fun footprint(name: String, crs: ProjectCrs, source: CadSourceCrs): RasterFootprint {
        val h = RasterTestFiles.load(name).header
        return RasterFootprint.of(crs, source, h.originX, h.originY, h.stepX, h.stepY, h.columns, h.rows)
    }

    private fun inside(f: RasterFootprint) = (f as RasterFootprint.Inside).extent

    private fun SurfaceExtent.shouldMatch(name: String, tolerance: Double) {
        val row = expected.getValue(name)
        minEasting shouldBe (row.getValue("min_este").toDouble() plusOrMinus tolerance)
        maxEasting shouldBe (row.getValue("max_este").toDouble() plusOrMinus tolerance)
        minNorthing shouldBe (row.getValue("min_norte").toDouble() plusOrMinus tolerance)
        maxNorthing shouldBe (row.getValue("max_norte").toDouble() plusOrMinus tolerance)
    }

    @Test
    fun `S045 RF-11 un raster en el sistema del proyecto tiene como extension el recuadro de sus centros de celda`() {
        inside(
            footprint("f32-sin-tiras-area.tif", ProjectCrs.ETRS89_UTM30, CadSourceCrs.Project),
        ).shouldMatch("f32-sin-tiras-area.tif", 1e-9)
        inside(footprint("esquina.asc", ProjectCrs.ETRS89_UTM30, CadSourceCrs.Project)).shouldMatch("esquina.asc", 1e-9)
        inside(footprint("f32-sin-tiras-area.tif", ProjectCrs.ETRS89_UTM30, CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)))
            .shouldMatch("f32-sin-tiras-area.tif", 1e-3)
    }

    @Test
    fun `S045 RF-11 un raster en otro huso o en geograficas se pasa al proyecto con pyproj a menos de 1 mm`() {
        inside(
            footprint("f32-utm29.tif", ProjectCrs.ETRS89_UTM30, CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)),
        ).shouldMatch("f32-utm29.tif", 1e-3)
        inside(footprint("f32-geograficas.tif", ProjectCrs.ETRS89_UTM30, CadSourceCrs.Geographic(GeodeticDatum.ETRS89)))
            .shouldMatch("f32-geograficas.tif", 1e-3)
    }

    @Test
    fun `S045 RF-11 el centro de la extension es el centro del recuadro`() {
        val extent = inside(footprint("f32-sin-tiras-area.tif", ProjectCrs.ETRS89_UTM30, CadSourceCrs.Project))

        extent.centerEasting shouldBe (440200.0 plusOrMinus 1e-9)
        extent.centerNorthing shouldBe (4473830.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S045 RF-3 un raster fuera del ambito del sistema del proyecto o de la rejilla de ED50 no tiene extension`() {
        val far = RasterFootprint.of(
            ProjectCrs.ETRS89_UTM30,
            CadSourceCrs.Geographic(GeodeticDatum.ETRS89),
            20.0,
            40.0,
            0.001,
            0.001,
            10,
            10,
        )
        far shouldBe RasterFootprint.Outside(CadIgnoredReason.OUTSIDE_CRS_AREA)

        val canarias = RasterFootprint.of(
            ProjectCrs.ETRS89_UTM30,
            CadSourceCrs.Utm(GeodeticDatum.ED50, 28),
            400000.0,
            3100000.0,
            10.0,
            10.0,
            10,
            10,
        )
        canarias shouldBe RasterFootprint.Outside(CadIgnoredReason.OUTSIDE_ED50_GRID)
    }
}
