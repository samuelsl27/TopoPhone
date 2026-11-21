package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/** Un eje en un SC de la biblioteca pasado al del proyecto, frente a pyproj (S057/RF-14, RF-11; constitución nº 2: ≤ 1 mm en los extremos). */
class AlignmentProjectorLibraryTest {
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

    /** Una recta del primer al segundo vértice del caso en el SC de origen. */
    private fun axis(a: Vector, b: Vector) = AlignmentDraft(
        listOf(
            AlignmentElement(
                kind = ElementKind.LINE,
                startChainage = 0.0,
                length = hypot(b.x - a.x, b.y - a.y),
                startEasting = a.x,
                startNorthing = a.y,
                startAzimuth = atan2(b.x - a.x, b.y - a.y),
                startCurvature = 0.0,
                endCurvature = 0.0,
            ),
        ),
    )

    @Test
    fun `S057 RF-14 los extremos de un eje salen en el proyecto como en pyproj a menos de 1 mm`() {
        for ((_, group) in vectors.groupBy { it.origin }) {
            val a = group[0]
            val b = group[1]

            val result = AlignmentProjector(
                projectOf(a),
                sourceOf(a),
            ).convert(axis(a, b)).shouldBeInstanceOf<AlignmentProjection.Converted>()

            result.approximated shouldBe true
            val geometry = AlignmentGeometry(result.elements)
            val start = geometry.pointAt(geometry.startChainage).shouldNotBeNull()
            val end = geometry.pointAt(geometry.endChainage).shouldNotBeNull()
            start.easting shouldBe (a.e plusOrMinus 1e-3)
            start.northing shouldBe (a.n plusOrMinus 1e-3)
            end.easting shouldBe (b.e plusOrMinus 1e-3)
            end.northing shouldBe (b.n plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S057 RF-14 un eje con el origen igual al proyecto en un SC de la biblioteca queda tal cual`() {
        val a = vectors.first { it.origin == 2154 }
        val project = LibraryCrs(definition(2154, CorrectionsDatum.ETRS89, GeoPoint(a.lat, a.lon)))
        val draft = AlignmentDraft(
            listOf(AlignmentElement(ElementKind.LINE, 0.0, 100.0, a.x, a.y, 0.0, 0.0, 0.0)),
        )

        val result = AlignmentProjector(project).convert(draft).shouldBeInstanceOf<AlignmentProjection.Converted>()

        result.approximated shouldBe false
        result.elements shouldBe draft.elements
    }

    @Test
    fun `S057 RF-14 un proyecto geografico sigue rechazando el eje y uno proyectado de la biblioteca no`() {
        val a = vectors.first { it.origin == 2154 }
        val draft = axis(a, vectors.first { it.origin == 2154 && it !== a })

        AlignmentProjector(SpainCrs.ETRS89_GEOGRAPHIC, sourceOf(a)).convert(draft)
            .shouldBeInstanceOf<AlignmentProjection.Rejected>().issue shouldBe AlignmentIssue.GeographicProject
        AlignmentProjector(projectOf(a), sourceOf(a)).convert(draft).shouldBeInstanceOf<AlignmentProjection.Converted>()
    }

    @Test
    fun `S057 RF-12 un eje que sale del ambito del SC de origen se rechaza`() {
        val a = vectors.first { it.origin == 2154 }
        val far = AlignmentDraft(listOf(AlignmentElement(ElementKind.LINE, 0.0, 100.0, 5_000_000.0, 5_000_000.0, 0.0, 0.0, 0.0)))

        AlignmentProjector(projectOf(a), sourceOf(a)).convert(far)
            .shouldBeInstanceOf<AlignmentProjection.Rejected>().issue shouldBe AlignmentIssue.OutsideCrsArea
    }
}
