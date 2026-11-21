package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Una superficie de LandXML en un SC de la biblioteca pasada al del proyecto frente a pyproj (S057/RF-14; constitución nº 2: ≤ 1 mm). */
class SurfaceProjectorLibraryTest {
    private val catalog = CrsCatalog.bundled

    private data class Vector(
        val origin: Int,
        val project: Int,
        val lat: Double,
        val lon: Double,
        val x: Double,
        val y: Double,
        val e: Double,
        val n: Double,
    )

    private val vectors = SurveyFixtures.rows("reproyeccion.csv").map {
        val n = it.drop(4).map(String::toDouble)
        Vector(it[0].toInt(), it[1].toInt(), n[0], n[1], n[2], n[3], n[4], n[5])
    }

    private fun corrections(projectEpsg: Int) = if (projectEpsg == 32634) CorrectionsDatum.ITRF_WGS84 else CorrectionsDatum.ETRS89

    private fun definition(epsg: Int, frame: CorrectionsDatum, at: GeoPoint): CrsDefinition =
        when (val r = catalog.build(epsg, frame, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            else -> error("EPSG:$epsg: $r")
        }

    private fun projectOf(v: Vector): ProjectCrs {
        val frame = corrections(v.project)
        return LibraryCrs(definition(v.project, frame, GeoPoint(v.lat, v.lon)), frame)
    }

    private fun sourceOf(v: Vector) = CadSourceCrs.Library(definition(v.origin, corrections(v.project), GeoPoint(v.lat, v.lon)))

    @Test
    fun `S057 RF-14 los vertices de una superficie salen como en pyproj a menos de 1 mm y la cota solo cambia de unidad`() {
        for ((_, group) in vectors.groupBy { it.origin }) {
            val first = group.first()
            val projector = SurfaceProjector(projectOf(first), sourceOf(first), LinearUnit.METRE)

            val out = projector.convert(
                DoubleArray(group.size) { group[it].x },
                DoubleArray(group.size) { group[it].y },
                DoubleArray(group.size) { 100.0 + it },
            ).shouldBeInstanceOf<SurfaceProjection.Converted>()

            group.forEachIndexed { i, v ->
                out.easting[i] shouldBe (v.e plusOrMinus 1e-3)
                out.northing[i] shouldBe (v.n plusOrMinus 1e-3)
                out.height[i] shouldBe (100.0 + i plusOrMinus 1e-12)
            }
        }
    }

    @Test
    fun `S057 RF-14 la extension aproximada con las cuatro esquinas se calcula desde el SC de la biblioteca`() {
        val v = vectors.first { it.origin == 3844 }
        val projector = SurfaceProjector(projectOf(v), sourceOf(v))

        val extent = projector.cornersExtent(v.x - 500.0, v.x + 500.0, v.y - 500.0, v.y + 500.0).shouldNotBeNull()

        (extent.minEasting < v.e && v.e < extent.maxEasting) shouldBe true
        (extent.minNorthing < v.n && v.n < extent.maxNorthing) shouldBe true
        (extent.maxEasting - extent.minEasting > 900.0) shouldBe true
    }

    @Test
    fun `S057 RF-12 una superficie con un vertice fuera del ambito del SC de origen se rechaza entera`() {
        val v = vectors.first { it.origin == 2154 }
        val projector = SurfaceProjector(projectOf(v), sourceOf(v))

        val out = projector.convert(doubleArrayOf(v.x, 5_000_000.0), doubleArrayOf(v.y, 5_000_000.0), doubleArrayOf(1.0, 2.0))

        out.shouldBeInstanceOf<SurfaceProjection.Rejected>().reason shouldBe CadIgnoredReason.OUTSIDE_CRS_AREA
    }
}
