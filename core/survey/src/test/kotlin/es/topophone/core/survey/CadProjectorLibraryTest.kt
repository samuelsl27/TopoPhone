package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/**
 * Un dibujo en un SC de la biblioteca (otro país) pasado al del proyecto frente a pyproj (S057/RF-13; constitución nº 2: ≤ 1 mm). Los vértices son
 * los de `fixtures/geodesia/reproyeccion.csv`.
 */
class CadProjectorLibraryTest {
    private val catalog = CrsCatalog.bundled

    private data class Vector(
        val origin: Int,
        val project: Int,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val x: Double,
        val y: Double,
        val easting: Double,
        val northing: Double,
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

    private fun libraryProject(v: Vector): ProjectCrs {
        val frame = corrections(v.project)
        return LibraryCrs(definition(v.project, frame, GeoPoint(v.latitudeDeg, v.longitudeDeg)), frame)
    }

    private fun source(v: Vector) =
        CadSourceCrs.Library(definition(v.origin, corrections(v.project), GeoPoint(v.latitudeDeg, v.longitudeDeg)))

    private fun line(vararg points: Pair<Double, Double>, z: Double? = null) = CadEntity(
        CadEntityKind.POLYLINE,
        points.map { it.first }.toDoubleArray(),
        points.map { it.second }.toDoubleArray(),
        z?.let { DoubleArray(points.size) { z } },
        null,
    )

    private fun converted(projector: CadProjector, entity: CadEntity) =
        projector.convert(entity).shouldBeInstanceOf<CadProjection.Converted>().entity

    @Test
    fun `S057 RF-13 cada vertice de un SC de otro pais sale en el proyecto como en pyproj a menos de 1 mm`() {
        val failures = vectors.groupBy { it.origin }.flatMap { (_, group) ->
            val first = group.first()
            val projector = CadProjector(libraryProject(first), CadImportOptions(source = source(first)))
            val entity = converted(projector, line(*group.map { it.x to it.y }.toTypedArray()))
            group.mapIndexedNotNull { i, v ->
                val error = hypot(entity.easting[i] - v.easting, entity.northing[i] - v.northing)
                "EPSG:${v.origin} → ${v.project} vértice $i: $error m".takeIf { error > 0.001 }
            }
        }
        failures.shouldBeEmpty()
    }

    @Test
    fun `S057 RF-13 con un proyecto de Espana da lo mismo que con la biblioteca`() {
        val v = vectors.first { it.origin == 23030 }
        val projector = CadProjector(SpainCrs.ETRS89_UTM30, CadImportOptions(source = source(v)))

        val entity = converted(projector, line(v.x to v.y))

        hypot(entity.easting[0] - v.easting, entity.northing[0] - v.northing) shouldBe (0.0 plusOrMinus 0.001)
    }

    @Test
    fun `S057 RF-3 la unidad elegida se aplica al origen y la altura pasa a metros sin convertirse de sistema`() {
        val v = vectors.first { it.origin == 3844 }
        val feet = 0.3048
        val projector = CadProjector(
            libraryProject(v),
            CadImportOptions(source = source(v), unit = LinearUnit.INTERNATIONAL_FOOT, useElevations = true),
        )

        val entity = converted(projector, line(v.x / feet to v.y / feet, z = 100.0))

        hypot(entity.easting[0] - v.easting, entity.northing[0] - v.northing) shouldBe (0.0 plusOrMinus 0.001)
        entity.height!![0] shouldBe (30.48 plusOrMinus 1e-9)
    }

    @Test
    fun `S057 RF-13 en un proyecto geografico un dibujo de la biblioteca sale en grados`() {
        val v = vectors.first { it.origin == 23030 }
        val projector = CadProjector(SpainCrs.ETRS89_GEOGRAPHIC, CadImportOptions(source = source(v)))
        val expected = SpainEngine(SpainCrs.ETRS89_UTM30, { error("sin geoide") }, { error("sin geoide") })
            .groundFromGrid(v.easting, v.northing).shouldBeInstanceOf<GroundResult.Ok>()

        val entity = converted(projector, line(v.x to v.y))

        entity.easting[0] shouldBe (expected.longitudeDeg plusOrMinus 2e-8)
        entity.northing[0] shouldBe (expected.latitudeDeg plusOrMinus 2e-8)
    }

    @Test
    fun `S057 RF-12 un vertice fuera del ambito del SC de origen rechaza el elemento entero`() {
        val v = vectors.first { it.origin == 2154 }
        val projector = CadProjector(libraryProject(v), CadImportOptions(source = source(v)))

        val result = projector.convert(line(v.x to v.y, 5_000_000.0 to 5_000_000.0))

        result.shouldBeInstanceOf<CadProjection.Rejected>().reason shouldBe CadIgnoredReason.OUTSIDE_CRS_AREA
    }

    @Test
    fun `S057 RF-12 un vertice que cae fuera de la rejilla de la transformacion rechaza el elemento`() {
        val v = vectors.first { it.origin == 23030 }
        val definition = definition(23030, CorrectionsDatum.ETRS89, GeoPoint(v.latitudeDeg, v.longitudeDeg))
        val north = es.topophone.core.geodesy.catalog.CrsDefinitions.resolve(definition, BundledLibraryResources.grids)!!.projection!!
            .forward(46.0, -3.7)!!
        val projector = CadProjector(libraryProject(v), CadImportOptions(source = CadSourceCrs.Library(definition)))

        val result = projector.convert(line(north.eastingM to north.northingM))

        result.shouldBeInstanceOf<CadProjection.Rejected>().reason shouldBe CadIgnoredReason.OUTSIDE_ED50_GRID
    }

    @Test
    fun `S057 RF-13 con el origen igual al proyecto en un SC de la biblioteca el dibujo entra tal cual`() {
        val v = vectors.first { it.origin == 2154 }
        val project = libraryProject(v)
        val projector = CadProjector(project, CadImportOptions(source = CadSourceCrs.Project))

        val entity = converted(projector, line(v.easting to v.northing))

        entity.easting[0] shouldBe (v.easting plusOrMinus 1e-9)
        entity.northing[0] shouldBe (v.northing plusOrMinus 1e-9)
    }

    @Test
    fun `S057 RF-13 con el origen igual al proyecto un vertice fuera del ambito del proyecto se rechaza`() {
        val v = vectors.first { it.origin == 2154 }
        val projector = CadProjector(libraryProject(v), CadImportOptions(source = CadSourceCrs.Project))

        projector.convert(line(5_000_000.0 to 5_000_000.0)).shouldBeInstanceOf<CadProjection.Rejected>()
    }
}
