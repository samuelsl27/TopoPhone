package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Test

/**
 * 100.000 puntos de un dibujo en un SC de la biblioteca pasados al SC del proyecto en menos de 2 s con un cambio de datum por parámetros y en menos
 * de 4 s con una rejilla (S057/RNF; RNF-T13).
 */
class ReprojectionPerformanceTest {
    private val catalog = CrsCatalog.bundled
    private val count = 100_000

    private fun definition(epsg: Int, frame: CorrectionsDatum, at: GeoPoint): CrsDefinition =
        when (val r = catalog.build(epsg, frame, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            is CrsBuildResult.Unavailable -> error("EPSG:$epsg: $r")
        }

    /** Un dibujo de [count] puntos en una malla de 10 m alrededor de ([x], [y]). */
    private fun grid(x: Double, y: Double): CadEntity {
        val side = 317 // 317 × 317 > 100.000
        val xs = DoubleArray(count) { x + (it % side) * 10.0 }
        val ys = DoubleArray(count) { y + (it / side) * 10.0 }
        return CadEntity(CadEntityKind.POLYLINE, xs, ys, null, null)
    }

    private fun timed(project: ProjectCrs, source: CadSourceCrs, x: Double, y: Double): Long {
        val projector = CadProjector(project, CadImportOptions(source = source))
        projector.convert(grid(x, y)).shouldBeInstanceOf<CadProjection.Converted>() // calienta la JVM y comprueba que entra
        val entity = grid(x, y)
        var rejected = 0
        val millis = measureTimeMillis { if (projector.convert(entity) !is CadProjection.Converted) rejected++ }
        rejected shouldBe 0
        return millis
    }

    @Test
    fun `S057 RNF 100000 puntos con un cambio de datum por parametros tardan menos de 2 s`() {
        val at = GeoPoint(38.0, 23.5)
        val project = LibraryCrs(definition(32634, CorrectionsDatum.ITRF_WGS84, at), CorrectionsDatum.ITRF_WGS84)
        val source = CadSourceCrs.Library(definition(2100, CorrectionsDatum.ITRF_WGS84, at)) // GGRS87 / Greek Grid, Helmert

        timed(project, source, 456_101.0, 4_205_932.0) shouldBeLessThan 2_000L
    }

    @Test
    fun `S057 RNF 100000 puntos con una rejilla tardan menos de 4 s`() {
        val at = GeoPoint(40.4, -3.7)
        val source = CadSourceCrs.Library(definition(23030, CorrectionsDatum.ETRS89, at)) // ED50 / UTM 30N con SPED2ETV2

        timed(ProjectCrs.ETRS89_UTM30, source, 440_595.0, 4_472_467.0) shouldBeLessThan 4_000L
    }
}
